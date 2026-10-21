package com.music.bitchord.ios.sources

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AudioQuality
import com.music.bitchord.data.sources.StreamFormat
import com.music.bitchord.data.sources.TrackMatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What the iOS player and Settings need from the sources, in one place.
 *
 * Everything behind this is a port of the phone's `data/sources` package —
 * [SourceRegistry], [SourceResolver] and the three [MusicSource]s — and this
 * object is the seam: it takes the [Song] the player already holds and the
 * [StreamRequest] it has already decided on, where the phone reads both off a
 * `bitchord://source?…` URI and its own settings at ExoPlayer's open time.
 *
 * ### How a source track sits in the queue
 *
 * As an ordinary [Song] whose [Song.videoId] is a track key,
 * `src:<configId>::<trackId>` (see [SourceRegistry.trackKey]), rather than a
 * YouTube video id. Everything else the resolver needs is the row's own
 * metadata, read by [TrackMatcher.targetOf]: [Song.title], [Song.artist] and
 * [Song.durationText] (`M:SS`) for the cross-source match, with
 * [Song.albumName], [Song.isExplicit] and [Song.isVideo] narrowing it when the
 * row has them, and [Song.sourceQuality] saying which mix an addon row is. A
 * YouTube song keeps its bare video id and the same fields drive
 * [substituteForYouTube].
 */
internal object IosSources {

    private const val TAG = "BitChord"

    /** Loads stored configs; called once at launch on the main thread. */
    fun init() = SourceRegistry.init()

    val configs: StateFlow<List<SourceConfig>> get() = SourceRegistry.configs

    /** True when the song is a row from a non-YouTube source (a track key/uri, not a bare video id). */
    fun isSourceTrack(song: Song): Boolean = SourceRegistry.parseTrackKey(song.videoId) != null

    /**
     * A source-backed song: its stream from its own source, or the best
     * fallback (the phone's `SourceResolver.resolve`).
     *
     * An addon row is played strictly as itself — its own mix, and never
     * YouTube's copy — because addon rows reach the queue only by being picked
     * from the addon search; a JioSaavn row may fall back to anything enabled,
     * YouTube included. Null when nothing enabled can serve it, after which
     * [youtubeFallback] is the last rung.
     */
    suspend fun resolve(song: Song, request: StreamRequest): ResolvedStream? {
        val (configId, trackId) = SourceRegistry.parseTrackKey(song.videoId) ?: return null
        val pickedMix = if (SourceRegistry.config(configId)?.kind == SourceKind.ADDON) {
            SourceResolver.mixOf(song)
        } else {
            null
        }
        val found = SourceResolver.resolve(
            configId = configId,
            trackId = trackId,
            target = TrackMatcher.targetOf(song),
            request = request,
            pickedMix = pickedMix,
        ) ?: return null
        return found.toResolved()
    }

    /**
     * A YouTube song: a stream from a source the user ranked above YouTube, or
     * null (the phone's `substituteForYouTube`).
     *
     * Null is the ordinary answer — nothing ranks above YouTube, or nothing
     * that does holds this recording — and the caller then plays YouTube as it
     * always has. Video rows are never substituted.
     */
    suspend fun substituteForYouTube(song: Song, request: StreamRequest): ResolvedStream? {
        if (isSourceTrack(song)) return null
        return SourceResolver.substituteForYouTube(TrackMatcher.targetOf(song), request)?.toResolved()
    }

    /**
     * A source-backed song whose stream failed: YouTube's match for it (the
     * phone's `youtubeFallback`), as a video id to play.
     *
     * A strict match — shared artist, and the length to within two seconds
     * when the row knows it — because this replaces the listener's pick with a
     * different upload. Null when YouTube has nothing that close.
     */
    suspend fun youtubeFallback(song: Song): String? {
        if (!isSourceTrack(song)) return null
        return SourceResolver.youtubeFallback(TrackMatcher.targetOf(song))?.videoId
    }

    /**
     * The read-ahead variant of [substituteForYouTube]: asks only the sources
     * quick enough to be worth asking before a track is reached (JioSaavn).
     * A caller that uses the answer should play that same stream when the
     * track starts, not race again — see [SourceResolver.prefetchSubstitute].
     */
    suspend fun prefetchSubstitute(song: Song, request: StreamRequest): ResolvedStream? {
        if (isSourceTrack(song)) return null
        return SourceResolver.prefetchSubstitute(TrackMatcher.targetOf(song), request)?.toResolved()
    }

    /**
     * Whether anything outranks YouTube under [request], without a search — so
     * the player can skip [substituteForYouTube] entirely when it could only
     * come back null.
     */
    fun canSubstituteForYouTube(request: StreamRequest): Boolean =
        SourceResolver.canSubstituteForYouTube(request)

    /**
     * The request a stream should be made under for the ceiling in force — the
     * phone's `requestForNow`, given the ceiling rather than reading it.
     *
     * Lossless asks every source for bit-exact audio. High asks for the best
     * lossy rendition, which also keeps the addons out of the walk (see
     * [SourceRegistry.permits]). Medium and Low reach YouTube alone, which is
     * what the phone's `AudioQuality.permits` says for both; Medium's
     * `Int.MAX_VALUE` ceiling is carried as a [StreamRequest.Capped] of that
     * size purely so the two rungs are told apart from High.
     */
    fun requestFor(ceiling: AudioQuality): StreamRequest = when (ceiling) {
        AudioQuality.LOSSLESS -> StreamRequest.Lossless
        AudioQuality.HIGH -> StreamRequest.Best
        AudioQuality.MEDIUM, AudioQuality.LOW -> StreamRequest.Capped(ceiling.maxKbps)
    }

    /**
     * The phone's Search tab "Addons" source: every enabled addon asked for
     * [query] at once.
     *
     * A slow or failing addon only costs its own rows — each gets
     * [ADDON_SEARCH_TIMEOUT_MS] — and the list is the rest of them, round-robin
     * so no single catalogue buries the others. Only addons: JioSaavn and
     * YouTube rows are never mixed in, on the phone or here (YouTube has its own
     * search, and JioSaavn exists to stand in for YouTube tracks, not to be
     * browsed). The rows come back as track-keyed [Song]s ready to queue; their
     * play is strict, see [resolve]. Empty when no addon is enabled — the phone
     * says "No addons are enabled. Add one in Settings → Sources." then.
     */
    suspend fun search(query: String): List<Song> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val addons = SourceRegistry.active().filterIsInstance<AddonSource>()
        if (addons.isEmpty()) return emptyList()
        val perAddon = coroutineScope {
            addons.map { addon ->
                async {
                    withTimeoutOrNull(ADDON_SEARCH_TIMEOUT_MS) {
                        runCatching { addon.search(q, limit = ADDON_SEARCH_LIMIT) }
                            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                            .getOrDefault(emptyList())
                    } ?: emptyList<Song>().also {
                        TrackLog.w(TAG, "${addon.displayName}: search for \"$q\" timed out")
                    }
                }
            }.map { it.await() }
        }
        return buildList {
            val longest = perAddon.maxOfOrNull { it.size } ?: 0
            for (i in 0 until longest) perAddon.forEach { rows -> rows.getOrNull(i)?.let(::add) }
        }
    }

    /** Whether any addon is enabled, i.e. whether [search] can say anything at all. */
    fun hasSearchableAddons(): Boolean = SourceRegistry.active().any { it is AddonSource }

    private fun Pair<MusicSource, SourceStream>.toResolved(): ResolvedStream {
        val (source, stream) = this
        return ResolvedStream(
            url = stream.url,
            headers = stream.headers,
            format = stream.format,
            dash = stream.dash,
            sourceName = source.displayName,
            durationMs = stream.durationSec?.let { it * 1000L },
        )
    }

    private const val ADDON_SEARCH_TIMEOUT_MS = 12_000L
    private const val ADDON_SEARCH_LIMIT = 20
}

internal data class ResolvedStream(
    val url: String,
    val headers: Map<String, String>,
    val format: StreamFormat,
    /** A DASH manifest; see [SourceStream.dash]. */
    val dash: Boolean = false,
    val sourceName: String,      // display name of the source that served it, for "stats for nerds"
    val durationMs: Long?,
)
