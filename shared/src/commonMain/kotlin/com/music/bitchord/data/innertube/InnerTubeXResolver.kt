package com.music.bitchord.data.innertube

import com.music.bitchord.data.MonotonicClock
import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.InnerTubeLogLevel
import com.metrolist.innertubex.InnerTubeLogger
import com.metrolist.innertubex.cipher.PlayerConfigRepository
import com.metrolist.innertubex.cipher.RemotePlayerConfigStore
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.InnerTubeExtractor
import com.metrolist.innertubex.extraction.PoTokenResult
import com.metrolist.innertubex.extraction.TokenProvider
import com.metrolist.innertubex.extraction.TokenProviderCapabilities

import com.metrolist.innertubex.extraction.strategy.PoTokenProviderKind
import com.metrolist.innertubex.extraction.YtConfigParserImpl
import com.metrolist.innertubex.extraction.generateClientPlaybackNonce
import com.metrolist.innertubex.models.YouTubeLocale
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.appHttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlin.concurrent.Volatile
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json

/** YouTube stream extraction through InnerTubeX's live-benchmarked client catalog and cipher tiers. */
object InnerTubeXResolver {

    private const val TAG = "BitChord"

    /** A stream InnerTubeX found, and the identity its media fetch has to wear. */
    class Extracted(
        val videoId: String,
        val url: String,
        val kbps: Int,
        val mimeType: String,
        val loudnessDb: Double?,
        val clientName: String,
        /** InnerTubeX's catalog id for the exact client variant; what exclusions key on. */
        val profileId: String,
        val headers: Map<String, String>,
    )

    /**
     * Where the remote player config is remembered between runs — the phone's
     * SharedPreferences, a properties file on the desktop.
     */
    interface ConfigStore {
        fun getString(key: String): String?
        fun getLong(key: String): Long?
        fun putString(key: String, value: String)
        fun putLong(key: String, value: Long)
    }

    /** Whether InnerTubeX's DEBUG lines reach the track log — the phone's debug builds. */
    @Volatile
    var debugLogging: Boolean = false

    /**
     * Where the preprocessed EJS players are kept, across processes. Solving a
     * player from scratch is the 8.7s step; a preprocessed one is reused per
     * player version, so only the first launch after YouTube rotates its player
     * pays it.
     */
    interface PlayerCache {
        fun read(key: String): String?

        /** Stores [value] under [key], or forgets [key] when [value] is null. */
        fun write(key: String, value: String?)
    }

    private var prefs: ConfigStore? = null
    private var poTokens: TokenProvider? = null
    private var players: PlayerCache? = null
    private var warmup: Job? = null

    /**
     * @param playerCache where the preprocessed EJS players are kept.
     * @param poTokenProvider the platform's BotGuard minter — the phone's
     *   WebView one. Null where there is none, which leaves the clients that
     *   need a PoToken out of the catalog.
     */
    fun init(playerCache: PlayerCache, store: ConfigStore, poTokenProvider: TokenProvider?) {
        prefs = store
        poTokens = poTokenProvider
        players = playerCache
        scope.launch {
            cipherService.setPreprocessedPlayerCache(::readPlayer, ::writePlayer)
            warm(WARM_DELAY_MS)
        }
    }

    /**
     * Pays the cold costs before a track needs them: the player config, the EJS
     * solver (8.7s in QuickJS when zemer-cipher lacks the player's hash), and the
     * BotGuard WebView behind WEB_REMIX's PoToken. Measured cold, those put an
     * age-restricted track 15-20s from first audio when paid on the play path.
     */
    private fun warm(delayMs: Long) {
        warmup?.cancel()
        warmup = scope.launch {
            delay(delayMs)
            val start = MonotonicClock.nowMs()
            runCatching {
                innerTube.cookie = Innertube.cookie
                innerTube.visitorData = Innertube.ensureVisitorData()
                innerTube.locale = YouTubeLocale(gl = Innertube.currentRegion, hl = Innertube.currentLanguage)
                extractor.prewarm()
            }.onFailure { if (it is CancellationException) throw it }
                .onFailure { TrackLog.w(TAG, "InnerTubeX warm-up failed: ${it.message}") }
                .onSuccess { TrackLog.d(TAG, "InnerTubeX warmed in ${MonotonicClock.nowMs() - start}ms") }
        }
    }

    private fun readPlayer(key: String): String? = players?.read(key)

    private fun writePlayer(key: String, value: String?) {
        players?.write(key, value)
    }

    /** The installed PoToken provider, or one that can mint nothing when there is not one. */
    private val tokenProvider = object : TokenProvider {
        override val capabilities: TokenProviderCapabilities
            get() = poTokens?.capabilities ?: TokenProviderCapabilities(providers = emptySet(), usesWebView = false)

        override suspend fun getPoToken(videoId: String, visitorData: String, cookie: String?): PoTokenResult? =
            poTokens?.getPoToken(videoId, visitorData, cookie)

        override suspend fun close() {
            poTokens?.close()
        }
    }

    private val http = appHttpClient {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 20_000
        }
        expectSuccess = false
    }

    private val logger = InnerTubeLogger { event ->
        if (event.level == InnerTubeLogLevel.DEBUG && !debugLogging) return@InnerTubeLogger
        val details = if (event.details.isEmpty()) "" else event.details.entries.joinToString(prefix = " [", postfix = "]") { "${it.key}=${it.value}" }
        val line = "ITX ${event.tag}: ${event.message}$details"
        if (event.level == InnerTubeLogLevel.INFO || event.level == InnerTubeLogLevel.DEBUG) TrackLog.d(TAG, line) else TrackLog.w(TAG, line)
    }

    private val repository = object : PlayerConfigRepository {
        override val enabled: Boolean get() = prefs != null
        override val sourceUrl: String = PLAYER_CONFIG_URL
        override val defaultSourceUrl: String = PLAYER_CONFIG_URL
        override var cachedJson: String
            get() = prefs?.getString("json").orEmpty()
            set(value) { prefs?.putString("json", value) }
        override var cachedAtMs: Long
            get() = prefs?.getLong("cached_at_ms") ?: 0L
            set(value) { prefs?.putLong("cached_at_ms", value) }
        override var cachedSourceUrl: String
            get() = prefs?.getString("source_url").orEmpty()
            set(value) { prefs?.putString("source_url", value) }
        override var cachedEtag: String
            get() = prefs?.getString("etag").orEmpty()
            set(value) { prefs?.putString("etag", value) }
    }

    private val innerTube = InnerTube(http, logger = logger)
    private val remoteStore = RemotePlayerConfigStore(http, repository, logger)
    private val cipherService = YouTubeCipherService(http, remoteStore, logger)
    private val extractor = InnerTubeExtractor(
        configParser = YtConfigParserImpl(http, innerTube, remoteStore, logger),
        cipherService = cipherService,
        innerTube = innerTube,
        tokenProvider = tokenProvider,
        logger = logger,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Guards [minted] and [excluded]: extraction, playback and session changes run on different threads. */
    private val lock = SynchronizedObject()

    /** What each minted URL was, so its fetch can be dressed and its refusal attributed. */
    private val minted = HashMap<String, Extracted>()

    /** Clients refused a track mid-playback, by videoId, and until when. */
    private val excluded = HashMap<String, HashMap<String, Long>>()

    /**
     * InnerTubeX picks one format per ask and has no bitrate ceiling of its own:
     * [maxKbps] only chooses between its smallest and best rungs, and
     * [requireM4a] asks for its best AAC-in-MP4.
     *
     * @return a stream for [videoId], or null when every client InnerTubeX would
     *   try is refused or [skipClients] has ruled it out.
     * @throws com.metrolist.innertubex.extraction.StreamResolveException with a
     *   reason when InnerTubeX exhausted its catalog.
     */
    suspend fun extract(
        videoId: String,
        maxKbps: Int,
        skipClients: Set<String> = emptySet(),
        requireM4a: Boolean = false,
    ): Extracted? {
        innerTube.cookie = Innertube.cookie
        innerTube.visitorData = Innertube.ensureVisitorData()
        innerTube.locale = YouTubeLocale(gl = Innertube.currentRegion, hl = Innertube.currentLanguage)
        val stream = extractor.extract(
            videoId = videoId,
            hints = ContentHints().withStreamCapabilities(allowHls = false, allowSabr = false, allowBoundedRange = true),
            excludedClients = excludedFor(videoId) + skipClients,
            audioQuality = when {
                requireM4a -> AudioQuality.MP4
                maxKbps <= LOW_KBPS -> AudioQuality.LOW
                else -> AudioQuality.AUTO
            },
            clientPlaybackNonce = generateClientPlaybackNonce(),
        ) ?: return null
        check(stream.sabrBootstrap == null) { "SABR is not supported by this playback engine" }
        val mime = stream.mimeType.orEmpty()
        val extracted = Extracted(
            videoId = videoId,
            url = stream.audioUrl,
            kbps = (stream.bitrate ?: 0) / 1000,
            mimeType = if (stream.codecs.isNullOrBlank()) mime else "$mime; codecs=\"${stream.codecs}\"",
            loudnessDb = stream.loudnessDb,
            clientName = stream.clientName,
            profileId = stream.profileId,
            headers = stream.headers,
        )
        synchronized(lock) {
            if (minted.size >= MAX_REMEMBERED) minted.clear()
            minted[extracted.url] = extracted
        }
        return extracted
    }

    /** A music video's picture: a video-only stream, and the identity its fetch has to wear. */
    class VideoStream(
        val videoId: String,
        val url: String,
        val width: Int,
        val height: Int,
        val mimeType: String,
        /** The RFC 6381 codec string, e.g. `avc1.640028`; what iOS checks it can decode. */
        val codecs: String,
        val headers: Map<String, String>,
        /** Every height this video is offered at, largest first: what a quality picker lists. */
        val heights: List<Int>,
        /** InnerTubeX's catalog id for the client that minted [url]; what a refusal skips. */
        val profileId: String,
        /** Clients asked and passed over because their stream was in a codec not wanted. */
        val codecRejected: List<String> = emptyList(),
    ) {
        val aspect: Float get() = if (height > 0) width.toFloat() / height else 0f
    }

    /**
     * The picture for [videoId], at most [maxHeight] tall, for a player that shows it over
     * its own audio (as upstream's MusicVideoEngine and the web videoDeck do). The audio
     * this resolves alongside is ignored.
     *
     * InnerTubeX can't be asked for a codec, and ranks VP9 the same as H.264; AVPlayer
     * plays only H.264 progressive video. With [requireCodecPrefix] set, a stream in any
     * other codec passes that client over for this call only and asks the next, up to
     * [budget] asks. Nothing is remembered here, and the shared exclusions audio uses are
     * read but never written: a picture's trouble must not cost the song its client.
     *
     * @param skipClients profiles refused for this picture already (the caller remembers them).
     * @return null when no client gave a video-only stream in a wanted codec.
     */
    suspend fun extractVideo(
        videoId: String,
        maxHeight: Int,
        skipClients: Set<String> = emptySet(),
        ageRestricted: Boolean = false,
        requireCodecPrefix: String? = null,
        budget: Int = VIDEO_CLIENT_BUDGET,
    ): VideoStream? {
        val skip = skipClients.toMutableSet()
        val rejected = ArrayList<String>()
        repeat(budget) {
            val stream = extractVideoOnce(videoId, maxHeight, skip, ageRestricted) ?: return null
            if (requireCodecPrefix == null || stream.codecs.startsWith(requireCodecPrefix)) {
                return if (rejected.isEmpty()) stream else VideoStream(
                    stream.videoId, stream.url, stream.width, stream.height, stream.mimeType, stream.codecs,
                    stream.headers, stream.heights, stream.profileId, rejected.toList(),
                )
            }
            TrackLog.d(TAG, "video: ${stream.profileId} offered ${stream.codecs.ifBlank { "?" }}, not $requireCodecPrefix; asking another client", about = videoId)
            rejected += "${stream.profileId}:${stream.codecs}"
            skip += stream.profileId
        }
        return null
    }

    private suspend fun extractVideoOnce(
        videoId: String,
        maxHeight: Int,
        skipClients: Set<String>,
        ageRestricted: Boolean,
    ): VideoStream? {
        innerTube.cookie = Innertube.cookie
        innerTube.visitorData = Innertube.ensureVisitorData()
        innerTube.locale = YouTubeLocale(gl = Innertube.currentRegion, hl = Innertube.currentLanguage)
        val stream = extractor.extract(
            videoId = videoId,
            hints = ContentHints(
                isAgeRestricted = true.takeIf { ageRestricted },
                wantVideo = true,
                maxVideoHeight = maxHeight,
            ).withStreamCapabilities(allowHls = false, allowSabr = false, allowBoundedRange = true),
            excludedClients = excludedFor(videoId) + skipClients,
            audioQuality = AudioQuality.LOW,
            clientPlaybackNonce = generateClientPlaybackNonce(),
        ) ?: return null
        // A live broadcast's video-only URL is a window that ends in seconds; no picture beats a frozen one.
        val url = stream.videoUrl?.takeUnless(::isLiveUrl) ?: return null
        val width = stream.videoWidth ?: 0
        val height = stream.videoHeight ?: 0
        val mime = stream.videoMimeType.orEmpty()
        val codecs = stream.videoCodecs.orEmpty()
        TrackLog.d(
            TAG,
            "video: ${width}x$height $mime $codecs via ${stream.profileId}" + if (ageRestricted) " (age-restricted)" else "",
            about = videoId,
        )
        return VideoStream(
            videoId = videoId,
            url = url,
            width = width,
            height = height,
            mimeType = mime,
            codecs = codecs,
            headers = stream.headers,
            heights = (stream.availableVideoHeights.orEmpty() + height).filter { it > 0 }.distinct().sortedDescending(),
            profileId = stream.profileId,
        )
    }

    /** A live window's URL (`live=1` or `source=yt_live_broadcast`), as upstream's StreamResolver.isLiveUrl. */
    private fun isLiveUrl(url: String): Boolean {
        val query = url.substringAfter('?', "").split('&')
        return "live=1" in query || "source=yt_live_broadcast" in query
    }

    /**
     * A picture's URL was refused mid-playback: the same signal [onRefused] acts on for
     * audio, minus the exclusion. The picture keeps its own, so a video-only refusal
     * doesn't bench a client the audio is fine on.
     */
    fun onVideoRefused() {
        scope.launch { runCatching { cipherService.refreshAfterStreamRejection() } }
    }

    private val _sessionGeneration = MutableStateFlow(0)

    /** Bumped on every sign-in and sign-out, so "no picture here" verdicts kept elsewhere can tell they are stale. */
    val sessionGeneration: StateFlow<Int> = _sessionGeneration.asStateFlow()

    /** Headers the media fetch for [url] must carry, when InnerTubeX minted it. */
    fun headersFor(url: String): Map<String, String>? = synchronized(lock) { minted[url]?.headers }

    /**
     * Records that googlevideo refused [url] mid-playback.
     *
     * @return the videoId it was minted for, or null when InnerTubeX did not mint it.
     */
    fun onRefused(url: String): String? {
        val refused = synchronized(lock) { minted.remove(url) } ?: return null
        exclude(refused.videoId, refused.profileId)
        // A refused ciphered URL is the signal the remote cipher config may be stale; it has its own cooldown.
        scope.launch { runCatching { cipherService.refreshAfterStreamRejection() } }
        return refused.videoId
    }

    fun exclude(videoId: String, profileId: String) {
        TrackLog.w(TAG, "InnerTubeX: $profileId refused $videoId; skipping it for this track", about = videoId)
        val until = MonotonicClock.nowMs() + EXCLUDE_MS
        synchronized(lock) { excluded.getOrPut(videoId) { HashMap() }[profileId] = until }
    }

    fun onSessionChanged() {
        synchronized(lock) {
            excluded.clear()
            minted.clear()
        }
        // A new cookie or visitor means a new player config and PoToken binding.
        if (prefs != null) warm(0)
        _sessionGeneration.update { it + 1 }
    }

    private fun excludedFor(videoId: String): Set<String> {
        val now = MonotonicClock.nowMs()
        return synchronized(lock) {
            val entries = excluded[videoId] ?: return emptySet()
            entries.entries.removeAll { it.value <= now }
            entries.keys.toSet()
        }
    }

    private const val PLAYER_CONFIG_URL =
        "https://raw.githubusercontent.com/ZemerTeam/zemer-cipher/master/library/src/main/assets/player_configs.json"
    private const val LOW_KBPS = 64
    /** Clients a picture may ask before giving up: upstream's MAX_REFUSED_CLIENTS. */
    const val VIDEO_CLIENT_BUDGET = 3
    private const val EXCLUDE_MS = 10 * 60 * 1000L
    private const val MAX_REMEMBERED = 64
    /** Off the cold-start path; the first tap on a track is rarely sooner. */
    private const val WARM_DELAY_MS = 2_000L
}
