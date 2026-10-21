package com.music.bitchord.ios

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.appHttpClient
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.durationMillis
import com.music.bitchord.data.wallClockMs
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import platform.CoreCrypto.CC_MD5
import platform.CoreCrypto.CC_MD5_DIGEST_LENGTH
import platform.Foundation.NSBundle
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Last.fm's web API as the phone's `LastFM` object speaks it: every call a signed form POST
 * (parameters sorted, concatenated, the secret appended, MD5'd). The key and secret are the
 * build's (`BitChordLastfmApiKey` / `BitChordLastfmSecret` in Info.plist, filled from the
 * repository's secrets), or the listener's own when the build carries none.
 */
@OptIn(ExperimentalForeignApi::class)
internal object IosLastFm {
    private const val ENDPOINT = "https://ws.audioscrobbler.com/2.0/"
    private const val USER_AGENT = "BitChord (https://github.com/kushagrasinghx/BitChord)"

    private val http by lazy { appHttpClient {} }
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val builtInKey: String =
        (NSBundle.mainBundle.objectForInfoDictionaryKey("BitChordLastfmApiKey") as? String).orEmpty().trim()
    private val builtInSecret: String =
        (NSBundle.mainBundle.objectForInfoDictionaryKey("BitChordLastfmSecret") as? String).orEmpty().trim()

    /** Whether this build was made with a Last.fm key of its own. */
    val hasBuiltInKey: Boolean get() = builtInKey.isNotEmpty() && builtInSecret.isNotEmpty()

    val apiKey: String get() = builtInKey.ifEmpty { IosSettings.lastfmApiKey.flow.value.trim() }
    private val secret: String get() = builtInSecret.ifEmpty { IosSettings.lastfmSecret.flow.value.trim() }

    /** A key and secret to sign with, from the build or the listener. */
    val configured: Boolean get() = apiKey.isNotEmpty() && secret.isNotEmpty()

    class LastFmException(val code: Int, override val message: String) : Exception(message)

    class Session(val name: String, val key: String)

    /** Username and password for a session key: `auth.getMobileSession`, as the phone signs in. */
    suspend fun mobileSession(username: String, password: String): Result<Session> = runCatching {
        val body = call("auth.getMobileSession", sessionKey = null, extra = mapOf("username" to username, "password" to password))
        val session = json.parseToJsonElement(body).jsonObject["session"]?.jsonObject
            ?: throw LastFmException(0, "No session in the reply")
        Session(
            name = session["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            key = session["key"]?.jsonPrimitive?.contentOrNull ?: throw LastFmException(0, "No session key in the reply"),
        )
    }

    suspend fun updateNowPlaying(sessionKey: String, artist: String, track: String, album: String?, duration: Int?) = runCatching {
        call(
            "track.updateNowPlaying",
            sessionKey,
            buildMap {
                put("artist", artist)
                put("track", track)
                album?.takeIf { it.isNotBlank() }?.let { put("album", it) }
                duration?.takeIf { it > 0 }?.let { put("duration", it.toString()) }
            },
        )
    }

    suspend fun scrobble(sessionKey: String, artist: String, track: String, timestamp: Long, album: String?, duration: Int?) = runCatching {
        call(
            "track.scrobble",
            sessionKey,
            buildMap {
                put("artist[0]", artist)
                put("track[0]", track)
                put("timestamp[0]", timestamp.toString())
                album?.takeIf { it.isNotBlank() }?.let { put("album[0]", it) }
                duration?.takeIf { it > 0 }?.let { put("duration[0]", it.toString()) }
            },
        )
    }

    private suspend fun call(method: String, sessionKey: String?, extra: Map<String, String>): String {
        val key = apiKey
        val secret = secret
        if (key.isEmpty() || secret.isEmpty()) throw LastFmException(10, "No Last.fm API key")
        val params = buildMap {
            put("method", method)
            put("api_key", key)
            sessionKey?.let { put("sk", it) }
            putAll(extra)
        }
        val signature = md5Hex(params.entries.sortedBy { it.key }.joinToString("") { it.key + it.value } + secret)
        val response = http.post(ENDPOINT) {
            header("User-Agent", USER_AGENT)
            setBody(
                FormDataContent(
                    Parameters.build {
                        params.forEach { (k, v) -> append(k, v) }
                        append("api_sig", signature)
                        append("format", "json")
                    },
                ),
            )
        }
        val text = response.bodyAsText()
        val error = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()?.let { reply ->
            reply["error"]?.jsonPrimitive?.intOrNull?.let { code ->
                LastFmException(code, reply["message"]?.jsonPrimitive?.contentOrNull ?: "Last.fm error $code")
            }
        }
        if (error != null) throw error
        if (!response.status.isSuccess()) throw LastFmException(response.status.value, response.status.description)
        return text
    }

    internal fun md5Hex(text: String): String {
        val bytes = text.encodeToByteArray()
        val digest = UByteArray(CC_MD5_DIGEST_LENGTH)
        bytes.usePinned { input ->
            digest.usePinned { output ->
                @Suppress("DEPRECATION")
                CC_MD5(if (bytes.isEmpty()) null else input.addressOf(0), bytes.size.convert(), output.addressOf(0))
            }
        }
        return digest.joinToString("") { it.toString(16).padStart(2, '0') }
    }
}

/** ListenBrainz's submit-listens, with the listener's own user token. */
internal object IosListenBrainz {
    private const val API_URL = "https://api.listenbrainz.org/1/submit-listens"
    private val http by lazy { appHttpClient {} }

    suspend fun playingNow(token: String, song: Song, positionMs: Long, durationMs: Long, primaryOnly: Boolean): Boolean =
        submit(token, "playing_now", listen(song, durationMs, primaryOnly, listenedAt = null) { put("position_ms", positionMs) })

    suspend fun finished(token: String, song: Song, startMs: Long, endMs: Long, durationMs: Long, primaryOnly: Boolean): Boolean {
        // A listen dated before ListenBrainz existed is refused; a bad clock is dated now instead.
        val listenedAt = (startMs / 1000).takeIf { it >= MIN_LISTEN_TS } ?: (wallClockMs() / 1000)
        return submit(
            token,
            "single",
            listen(song, durationMs, primaryOnly, listenedAt) {
                put("start_ms", startMs)
                put("end_ms", endMs)
            },
        )
    }

    private fun listen(
        song: Song,
        durationMs: Long,
        primaryOnly: Boolean,
        listenedAt: Long?,
        extraInfo: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
    ): JsonObject = buildJsonObject {
        listenedAt?.let { put("listened_at", it) }
        putJsonObject("track_metadata") {
            put("artist_name", if (primaryOnly) song.artist.primaryArtist() else song.artist)
            put("track_name", song.title)
            song.albumName?.takeIf { it.isNotBlank() }?.let { put("release_name", it) }
            putJsonObject("additional_info") {
                // The API refuses a zero length, and it is optional.
                if (durationMs > 0) put("duration_ms", durationMs)
                extraInfo()
                put("submission_client", "BitChord")
            }
        }
    }

    private suspend fun submit(token: String, type: String, listen: JsonObject): Boolean = runCatching {
        val body = buildJsonObject {
            put("listen_type", type)
            put("payload", buildJsonArray { add(listen) })
        }
        val response = http.post(API_URL) {
            header("Authorization", "Token $token")
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        if (!response.status.isSuccess()) TrackLog.w("ListenBrainz", "$type refused: ${response.status.value} ${response.bodyAsText()}")
        response.status.isSuccess()
    }.getOrElse {
        TrackLog.w("ListenBrainz", "$type failed: ${it.message}")
        false
    }

    private const val MIN_LISTEN_TS = 1033430400L
}

/** The first credited artist, for services that read joint credits badly; "AC/DC" stays whole. */
internal fun String.primaryArtist(): String = split(PRIMARY_ARTIST_SEPARATOR, limit = 2).first().trim().ifBlank { this }

private val PRIMARY_ARTIST_SEPARATOR = Regex("""\s*,\s*|\s+&\s+|\s+＆\s+""")

/**
 * What the player tells the scrobbling services, on the phone's terms. Last.fm: "now playing" on
 * start, and a scrobble once the song has played [scrobbleDelayPercent] of its length, or
 * [scrobbleDelaySeconds], whichever comes first — paused time not counted, songs of
 * [minSongDuration] or less never. ListenBrainz: "playing now" on every start and resume, and a
 * finished listen only when a track actually ended, never for a skip.
 */
internal object IosScrobbler {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var current: Song? = null
    private var currentDurationMs = 0L
    private var startedAtMs = 0L
    private var playing = false

    private var scrobbleJob: Job? = null
    private var scrobbleRemainingMs = 0L
    private var timerStartedAt = 0L
    private var scrobbled = false

    private val lastfmActive: Boolean
        get() = IosSettings.lastfmEnabled.flow.value && IosSettings.lastfmSessionKey.flow.value.isNotBlank() && IosLastFm.configured

    private val listenBrainzToken: String?
        get() = IosSettings.listenBrainzToken.flow.value.trim().takeIf { IosSettings.listenBrainzEnabled.flow.value && it.isNotEmpty() }

    /**
     * The player moved from [previous] to [song]; [ended] says whether [previous] played to its
     * end (an advance or a crossfade) rather than being skipped.
     */
    fun onSongChanged(previous: Song?, ended: Boolean, song: Song?) {
        val previousStart = startedAtMs
        val previousDuration = currentDurationMs
        if (previous != null && ended && previousStart > 0L) {
            listenBrainzToken?.let { token ->
                val end = wallClockMs()
                scope.launch {
                    IosListenBrainz.finished(token, previous, previousStart, end, previousDuration, IosSettings.listenBrainzPrimaryArtistOnly.flow.value)
                }
            }
        }
        scrobbleJob?.cancel()
        scrobbleJob = null
        scrobbleRemainingMs = 0L
        timerStartedAt = 0L
        scrobbled = false
        current = song
        currentDurationMs = song?.durationMillis()?.coerceAtLeast(0L) ?: 0L
        startedAtMs = 0L
        playing = false
    }

    /** The player's real length for the playing track, once it knows it. */
    fun onDuration(durationMs: Long) {
        if (durationMs > 0L) currentDurationMs = durationMs
    }

    fun onPlaying(isPlaying: Boolean, positionMs: Long) {
        val song = current ?: return
        if (isPlaying == playing) return
        playing = isPlaying
        if (isPlaying) {
            val first = startedAtMs == 0L
            if (first) {
                startedAtMs = wallClockMs()
                startTimer(song)
                if (lastfmActive && IosSettings.lastfmNowPlaying.flow.value) nowPlaying(song)
            } else {
                resumeTimer(song)
            }
            listenBrainzToken?.let { token ->
                scope.launch {
                    IosListenBrainz.playingNow(token, song, positionMs, currentDurationMs, IosSettings.listenBrainzPrimaryArtistOnly.flow.value)
                }
            }
        } else {
            pauseTimer()
        }
    }

    private fun startTimer(song: Song) {
        val seconds = (currentDurationMs / 1000).toInt()
        if (seconds <= 0 || seconds <= IosSettings.scrobbleMinDuration.flow.value) return
        val threshold = (seconds * 1000L * IosSettings.scrobbleDelayPercent.flow.value).roundToLong()
        scrobbleRemainingMs = min(threshold, IosSettings.scrobbleDelaySeconds.flow.value * 1000L)
        resumeTimer(song)
    }

    private fun resumeTimer(song: Song) {
        if (scrobbled || scrobbleRemainingMs <= 0L) return
        scrobbleJob?.cancel()
        timerStartedAt = wallClockMs()
        scrobbleJob = scope.launch {
            delay(scrobbleRemainingMs)
            scrobbleJob = null
            scrobbleRemainingMs = 0L
            scrobble(song)
        }
    }

    private fun pauseTimer() {
        scrobbleJob?.cancel()
        scrobbleJob = null
        if (timerStartedAt != 0L) {
            scrobbleRemainingMs = (scrobbleRemainingMs - (wallClockMs() - timerStartedAt)).coerceAtLeast(0L)
            timerStartedAt = 0L
        }
    }

    private fun artistFor(song: Song): String =
        if (IosSettings.lastfmPrimaryArtistOnly.flow.value) song.artist.primaryArtist() else song.artist

    private fun scrobble(song: Song) {
        scrobbled = true
        if (!lastfmActive || !IosSettings.lastfmScrobbleEnabled.flow.value) return
        val key = IosSettings.lastfmSessionKey.flow.value
        val timestamp = startedAtMs / 1000
        val duration = (currentDurationMs / 1000).toInt()
        scope.launch {
            IosLastFm.scrobble(key, artistFor(song), song.title, timestamp, song.albumName, duration)
                .onFailure { TrackLog.w("LastFm", "scrobble failed: ${it.message}") }
        }
    }

    private fun nowPlaying(song: Song) {
        val key = IosSettings.lastfmSessionKey.flow.value
        val duration = (currentDurationMs / 1000).toInt()
        scope.launch {
            IosLastFm.updateNowPlaying(key, artistFor(song), song.title, song.albumName, duration)
                .onFailure { TrackLog.w("LastFm", "now playing failed: ${it.message}") }
        }
    }
}
