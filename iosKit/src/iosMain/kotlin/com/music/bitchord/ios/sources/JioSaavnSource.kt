package com.music.bitchord.ios.sources

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.appHttpClient
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.StreamFormat
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import platform.CoreCrypto.CCCrypt
import platform.CoreCrypto.kCCAlgorithmDES
import platform.CoreCrypto.kCCDecrypt
import platform.CoreCrypto.kCCKeySizeDES
import platform.CoreCrypto.kCCOptionECBMode
import platform.CoreCrypto.kCCOptionPKCS7Padding
import platform.CoreCrypto.kCCSuccess
import platform.posix.size_tVar

private const val TAG = "BitChord"

/**
 * JioSaavn's catalogue, as one of the sources: up to 320kbps AAC in MP4, which
 * AVPlayer plays natively.
 *
 * Off until the listener opts in — see [SourceConfig.enabled] — because its
 * catalogue files different recordings under the same title and artist, and a
 * match on those can play the wrong one. [SourceResolver.matchAndStream] refuses
 * to guess between conflicting albums for exactly this source.
 */
internal class JioSaavnSource(
    override val config: SourceConfig,
) : MusicSource, SourceRegistry.ConfigBacked {

    override val configId: String get() = config.id
    override val kind: SourceKind get() = SourceKind.JIOSAAVN
    override val displayName: String get() = config.label.ifBlank { SourceKind.JIOSAAVN.label }

    /** Always Ok since the API endpoints don't need authentication to search. */
    override suspend fun health(): SourceHealth = SourceHealth.Ok()

    /** [waitForAll] and [request] are moot: one endpoint, one catalogue, no tiers to ask at. */
    override suspend fun search(
        query: String,
        limit: Int,
        waitForAll: Boolean,
        request: StreamRequest?,
    ): List<Song> {
        TrackLog.d(TAG, "▶ JioSaavn searchSongs() query=\"$query\" limit=$limit")
        val results = prioritizeExplicit(JioSaavnService.searchSongs(query))
        TrackLog.d(
            TAG,
            "  ✓ JioSaavn returned ${results.size} tracks" + results.take(5)
                .joinToString(prefix = ": ", separator = "; ") {
                    "'${it.title}' by '${it.moreInfo.artistMap.primaryArtists.joinToString(", ") { a -> a.name }}' " +
                        "${it.moreInfo.duration}s id=${it.id} album='${it.moreInfo.album}' explicit=${it.explicitContent}"
                }.takeIf { results.isNotEmpty() }.orEmpty(),
        )
        return results.take(limit).map { raw ->
            val primaryArtists = raw.moreInfo.artistMap.primaryArtists.joinToString(", ") { it.name }
            // The 500px artwork rather than the 150px the search answers with,
            // and over https, which App Transport Security insists on anyway.
            val thumbnail = raw.image
                .replace(Regex("150x150|50x50"), "500x500")
                .replace(Regex("^http://"), "https://")
            Song(
                videoId = SourceRegistry.trackKey(config.id, raw.id),
                title = raw.title,
                artist = primaryArtists.ifBlank { "Unknown Artist" },
                // Keep the release identity JioSaavn already gave us. Its
                // catalogue contains different recordings under the same
                // title and artist (notably the two "Brown Rang" rows), so
                // dropping this left duration to choose between them.
                albumName = raw.moreInfo.album.ifBlank { null },
                thumbnailUrl = thumbnail,
                // Seconds from the catalogue, as the "M:SS" every row carries.
                durationText = raw.moreInfo.duration.toIntOrNull()?.let { seconds ->
                    "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
                },
                sourceQuality = StreamChecks.HIGH,
                isExplicit = raw.isExplicit,
            )
        }
    }

    override suspend fun stream(trackId: String, request: StreamRequest): SourceStream? {
        TrackLog.d(TAG, "▶ JioSaavn getStreamUrl() trackId=$trackId request=$request")
        val stream = JioSaavnService.getStreamUrl(trackId)
        if (stream == null || stream.url.isBlank()) {
            TrackLog.w(TAG, "  ✗ JioSaavn had no stream URL for $trackId")
            return null
        }
        // A rendition this thin is worse than the YouTube stream it would be
        // replacing. Refused here rather than handed up, because a miss lets the
        // resolver step over this source to the next one, whereas a stream
        // returned and then rejected is simply the track not being upgraded.
        if (stream.kbps != null && stream.kbps <= MIN_USABLE_KBPS) {
            TrackLog.w(TAG, "  ✗ JioSaavn only offered ${stream.kbps}kbps for $trackId; not worth playing")
            return null
        }
        // The rate the URL will really serve, not a flat 320 — see
        // [selectBestSaavnStream]. `mp4` is the container; the codec inside is
        // AAC, which AVPlayer decodes. Still put through the same gate every
        // other source's answer is, so a CDN that one day starts handing back
        // something else is refused here and not by the player.
        val format = StreamFormat(
            codec = stream.url.substringBefore('?').substringAfterLast('.').lowercase()
                .takeIf { it == "mp3" || it == "aac" } ?: "mp4",
            kbps = stream.kbps,
        )
        if (!DeviceCodecs.decodes(format)) {
            TrackLog.w(TAG, "  ✗ JioSaavn offered ${format.summary} for $trackId, which AVPlayer can't decode")
            return null
        }
        if (StreamChecks.malformed(stream.url)) {
            TrackLog.w(TAG, "  ✗ JioSaavn handed back a malformed URL for $trackId")
            return null
        }
        TrackLog.d(TAG, "  ✓ JioSaavn ${stream.kbps ?: "?"}kbps ${stream.url.take(96)}")
        return SourceStream(url = stream.url, format = format)
    }

    private companion object {
        /**
         * The lowest rendition worth taking over YouTube.
         *
         * JioSaavn files a track at 48, 96, 160 or 320. The first two are below
         * what YouTube already serves, so taking one is a downgrade dressed as
         * an upgrade.
         */
        const val MIN_USABLE_KBPS = 96
    }
}

// ── The JioSaavn API ─────────────────────────────────────────────────────
//
// The phone's `JioSaavnService`, which lives in its app module rather than in
// shared code because it leans on `android.util.Base64` and `javax.crypto` for
// the stream-URL decryption. Ported here with CommonCrypto's DES in their place.

@Serializable
internal data class RawArtistMapItem(
    val id: String = "",
    val name: String = "",
)

@Serializable
internal data class RawArtistMap(
    @SerialName("primary_artists") val primaryArtists: List<RawArtistMapItem> = emptyList(),
)

@Serializable
internal data class RawMoreInfo(
    @SerialName("album_id") val albumId: String = "",
    val album: String = "",
    @SerialName("encrypted_media_url") val encryptedMediaUrl: String = "",
    val duration: String = "",
    /**
     * Whether a 320kbps rendition exists, as `"true"`/`"false"`.
     *
     * The catalogue states this per track and it is frequently false. Asking
     * the CDN for `_320` anyway does not produce one.
     */
    @SerialName("320kbps") val has320: String = "",
    val artistMap: RawArtistMap = RawArtistMap(),
) {
    val supports320: Boolean get() = has320.equals("true", ignoreCase = true)
}

/** A decoded CDN URL and the bitrate it will really deliver. */
internal data class SaavnStream(val url: String, val kbps: Int?)

/**
 * Select the highest JioSaavn CDN rendition without losing URL parameters.
 *
 * The encrypted URL normally names the 96 kbps file and the catalogue states
 * whether the parallel 320 kbps object exists. CDN URLs are not guaranteed to
 * end at `.mp4`: cache-busting or access query parameters may follow it. An
 * end-anchored match misses those URLs, leaves `_96.mp4` in place, and still
 * reports 320 upstream — which is what the phone once did.
 */
internal fun selectBestSaavnStream(decryptedUrl: String, supports320: Boolean): SaavnStream? {
    if (decryptedUrl.isBlank()) return null
    val rendition = Regex(
        "_(48|96|160|320)\\.(mp4|aac|mp3)(?=[?#]|$)",
        RegexOption.IGNORE_CASE,
    ).find(decryptedUrl)
        // An unrecognised URL must not be labelled 320 merely because the
        // catalogue says that rendition exists; no rewrite was actually made.
        ?: return SaavnStream(decryptedUrl, null)

    val offered = rendition.groupValues[1].toInt()
    if (!supports320) return SaavnStream(decryptedUrl, offered)

    val extension = rendition.groupValues[2]
    return SaavnStream(decryptedUrl.replaceRange(rendition.range, "_320.$extension"), 320)
}

@Serializable
internal data class RawSongItem(
    val id: String = "",
    val title: String = "",
    val image: String = "",
    /** JioSaavn sends this as the string `"1"` or `"0"`. */
    @SerialName("explicit_content") val explicitContent: String = "",
    @SerialName("more_info") val moreInfo: RawMoreInfo = RawMoreInfo(),
) {
    val isExplicit: Boolean
        get() = explicitContent == "1" || explicitContent.equals("true", ignoreCase = true)
}

@Serializable
internal data class RawSearchResponse(
    val results: List<RawSongItem> = emptyList(),
)

/** Put uncensored catalogue rows ahead of their clean duplicates. */
internal fun prioritizeExplicit(songs: List<RawSongItem>): List<RawSongItem> =
    songs.sortedByDescending { it.isExplicit }

@OptIn(ExperimentalEncodingApi::class)
internal object JioSaavnService {

    // https://www.jiosaavn.com/api.php
    private val BASE_URL = Base64.decode("aHR0cHM6Ly93d3cuamlvc2Fhdm4uY29tL2FwaS5waHA=").decodeToString()

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private val client by lazy {
        appHttpClient {
            install(HttpTimeout) {
                requestTimeoutMillis = 6_000
                connectTimeoutMillis = 4_000
                socketTimeoutMillis = 6_000
            }
            expectSuccess = false
        }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.saavnHeaders() {
        header("Accept", "application/json")
        header(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/134.0.0.0 Safari/537.36",
        )
        header("X-Forwarded-For", "49.36.0.1")
        header("X-Real-IP", "49.36.0.1")
        header("Accept-Language", "en-IN,en;q=0.9")
        header("Cookie", "explicit_content=1")
        parameter("_format", "json")
        parameter("_marker", "0")
        parameter("api_version", "4")
        parameter("ctx", "android")
    }

    /**
     * The media URL JioSaavn hands out is DES-ECB encrypted under a fixed key,
     * base64-encoded. The phone uses `javax.crypto`; CommonCrypto's `CCCrypt`
     * is the same cipher with PKCS#7 padding, which for DES's 8-byte block is
     * byte-for-byte what Java calls PKCS5.
     */
    @OptIn(ExperimentalForeignApi::class)
    private fun decryptUrl(encryptedUrl: String): String {
        if (encryptedUrl.isBlank()) return ""
        return try {
            val key = "38346591".encodeToByteArray()
            val input = Base64.decode(encryptedUrl.filterNot { it.isWhitespace() })
            if (input.isEmpty()) return ""
            val output = ByteArray(input.size + 8)
            val moved = memScoped {
                val count = alloc<size_tVar>()
                val status = key.usePinned { k ->
                    input.usePinned { i ->
                        output.usePinned { o ->
                            CCCrypt(
                                kCCDecrypt,
                                kCCAlgorithmDES,
                                kCCOptionPKCS7Padding or kCCOptionECBMode,
                                k.addressOf(0),
                                kCCKeySizeDES.convert(),
                                null,
                                i.addressOf(0),
                                input.size.convert(),
                                o.addressOf(0),
                                output.size.convert(),
                                count.ptr,
                            )
                        }
                    }
                }
                if (status != kCCSuccess) -1 else count.value.toInt()
            }
            if (moved < 0) "" else output.copyOf(moved).decodeToString().trim()
        } catch (e: Exception) {
            TrackLog.e(TAG, "JioSaavn URL decryption failed", e)
            ""
        }
    }

    /**
     * The best CDN URL this track really has, and the bitrate it will deliver.
     *
     * The rewrite to `_320` is conditional on [RawMoreInfo.supports320] rather
     * than applied to everything. Rewriting unconditionally is not an upgrade —
     * the CDN has no 320 rendition to serve for a track that hasn't got one —
     * and a 96kbps stream advertised as 320 looks like an upgrade over YouTube
     * when it is a third of the bitrate.
     */
    private fun bestStream(encryptedUrl: String, supports320: Boolean): SaavnStream? =
        selectBestSaavnStream(decryptUrl(encryptedUrl), supports320)

    suspend fun searchSongs(query: String): List<RawSongItem> = try {
        val response = client.get(BASE_URL) {
            saavnHeaders()
            parameter("__call", "search.getResults")
            parameter("q", query)
            parameter("p", "1")
            parameter("n", "10")
        }
        if (response.status != HttpStatusCode.OK) {
            TrackLog.w(TAG, "Saavn search failed: HTTP ${response.status.value}")
            emptyList()
        } else {
            json.decodeFromString<RawSearchResponse>(response.bodyAsText()).results
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        TrackLog.w(TAG, "Saavn search error: ${e.message}")
        emptyList()
    }

    suspend fun getStreamUrl(saavnSongId: String): SaavnStream? = try {
        val response = client.get(BASE_URL) {
            saavnHeaders()
            parameter("__call", "song.getDetails")
            parameter("pids", saavnSongId)
        }
        if (response.status != HttpStatusCode.OK) {
            TrackLog.w(TAG, "Saavn getDetails failed: HTTP ${response.status.value}")
            null
        } else {
            // `song.getDetails` does not answer with the `{"songs":[…]}`
            // envelope `search.getResults` uses. It answers with a map keyed by
            // the id that was asked for — `{"5xceLLNe":{…}}` — so both shapes
            // are read here so neither endpoint changing its mind breaks the
            // other.
            val root = json.parseToJsonElement(response.bodyAsText()) as? JsonObject
            val songElement = root?.let { (it["songs"] as? JsonArray)?.firstOrNull() ?: it.values.firstOrNull { v -> v is JsonObject } }
            if (songElement == null) {
                TrackLog.w(TAG, "Saavn getDetails held no song for $saavnSongId")
                null
            } else {
                val rawSong = json.decodeFromJsonElement(RawSongItem.serializer(), songElement)
                bestStream(rawSong.moreInfo.encryptedMediaUrl, rawSong.moreInfo.supports320)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        TrackLog.w(TAG, "Saavn getDetails error: ${e.message}")
        null
    }
}
