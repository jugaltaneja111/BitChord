package com.music.bitchord.data

import com.music.bitchord.data.innertube.Innertube
import com.music.bitchord.data.innertube.InnertubeParser
import com.music.bitchord.data.model.Song
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * What the Song Info panel shows. Upstream's v1.9 `SongInfoRepository`, in common code: the
 * watch page is read with Ktor instead of OkHttp, its scripts found by their assignments
 * instead of through Jsoup, and the release date checked by hand instead of `java.time`.
 */
data class SongInfo(
    val album: String? = null,
    val releaseYear: String? = null,
    val releaseDate: String? = null,
    val views: String? = null,
    val likes: String? = null,
    val comments: String? = null,
    val failed: Boolean = false,
)

/**
 * YouTube-style short form of a raw count: 950 → "950", 1234 → "1.2K",
 * 100000 → "100K", 2345678 → "2.3M". Truncated rather than rounded, as YouTube
 * does, so 999999 never shows as "1000K". Text that isn't a plain number is
 * returned unchanged. [decimalSeparator] is the locale's; the phone reads it
 * from `DecimalFormatSymbols`.
 */
fun compactCount(raw: String, decimalSeparator: Char = '.'): String {
    val n = raw.toLongOrNull()?.takeIf { it >= 0 } ?: return raw
    val (unit, suffix) = when {
        n >= 1_000_000_000 -> 1_000_000_000L to "B"
        n >= 1_000_000 -> 1_000_000L to "M"
        n >= 1_000 -> 1_000L to "K"
        else -> return n.toString()
    }
    val whole = n / unit
    if (whole >= 10) return "$whole$suffix"
    val tenth = n % unit * 10 / unit
    return if (tenth == 0L) "$whole$suffix" else "$whole$decimalSeparator$tenth$suffix"
}

/** Loaded only when requested. Counts belong to this exact upload, never a search match. */
object SongInfoRepository {
    private val client by lazy {
        appHttpClient { install(HttpTimeout) { requestTimeoutMillis = 15_000 } }
    }

    private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
    private val YEAR = Regex("(?:19|20)[0-9]{2}")

    suspend fun load(song: Song): SongInfo = withContext(Dispatchers.Default) {
        if (song.localUri != null || !VIDEO_ID.matches(song.videoId)) {
            return@withContext SongInfo(album = song.albumName)
        }
        coroutineScope {
            val statistics = async {
                attempt {
                    val response = client.get("https://www.youtube.com/watch") {
                        header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                        // Without a consent answer an EU request is redirected to
                        // consent.youtube.com, whose page has no player response.
                        // SOCS=CAI is "reject all": it answers the question and
                        // opts into nothing.
                        header("Cookie", "SOCS=CAI")
                        parameter("v", song.videoId)
                        parameter("hl", Innertube.currentLanguage)
                    }
                    check(response.status.value == 200)
                    SongInfoParser.parse(response.bodyAsText(), song.videoId)
                }
            }
            var album = song.albumName
            var year: String? = null
            val release = attempt {
                val linked = if (song.albumId == null) {
                    YtMusicRepository.trackLinks(song.videoId).getOrThrow()
                } else {
                    song
                }
                album = album ?: linked.albumName
                linked.albumId?.let { id ->
                    val header = InnertubeParser.parseBrowseHeader(Innertube.browse(id))
                    album = album ?: header?.title
                    year = header?.subtitle?.split('•')?.map { it.trim() }?.firstOrNull { it.matches(YEAR) }
                }
            }
            (statistics.await().getOrNull() ?: SongInfo(failed = true))
                .copy(album = album, releaseYear = year)
                .let { it.copy(failed = it.failed || release.isFailure) }
        }
    }

    private suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
}

internal object SongInfoParser {
    fun parse(html: String, videoId: String): SongInfo {
        val player = extract(html, "ytInitialPlayerResponse") ?: error("Missing player metadata")
        val details = player.obj("videoDetails")
        check(details.str("videoId") == videoId) { "Metadata belongs to another track" }
        val data = extract(html, "ytInitialData")
        // Only the current video's primary renderer: recommendations also carry counts.
        val primary = data?.find("videoPrimaryInfoRenderer")?.firstOrNull()
        val oldLike = primary?.find("toggleButtonRenderer")?.firstOrNull {
            it.obj("defaultIcon").str("iconType") == "LIKE"
        }?.get("defaultText")?.text()
        val newLike = primary?.find("likeButtonViewModel")?.firstOrNull()
            ?.find("buttonViewModel")?.firstOrNull()?.str("title")
        val comments = data?.find("commentsHeaderRenderer")?.firstNotNullOfOrNull {
            it["countText"]?.text()?.withCount()
        }
            ?: data?.find("commentsEntryPointHeaderRenderer")?.firstOrNull()
                ?.get("commentCount")?.text()?.withCount()
            ?: data?.find("engagementPanelSectionListRenderer")?.firstOrNull {
                it.str("targetId") == "engagement-panel-comments-section" ||
                    it.str("panelIdentifier") == "engagement-panel-comments-section"
            }?.find("engagementPanelTitleHeaderRenderer")?.firstOrNull()
                ?.get("contextualInfo")?.text()?.withCount()
        return SongInfo(
            releaseDate = releaseDate(details.str("shortDescription")),
            views = details.str("viewCount")?.takeIf { it.toLongOrNull()?.let { n -> n >= 0 } == true },
            likes = (newLike ?: oldLike)?.withCount(),
            comments = comments,
            failed = data == null,
        )
    }

    private val RELEASED_ON = Regex("Released on:\\s*(\\d{4})-(\\d{2})-(\\d{2})", RegexOption.IGNORE_CASE)

    /**
     * Catalogue uploads explicitly state this in their description. Upload/publish dates
     * describe the video and must never substitute for a recording's release date.
     */
    internal fun releaseDate(description: String?): String? {
        val match = description?.lineSequence()?.firstNotNullOfOrNull { RELEASED_ON.matchEntire(it.trim()) }
            ?: return null
        val (y, m, d) = match.destructured
        val year = y.toInt()
        val month = m.toInt()
        val day = d.toInt()
        if (month !in 1..12 || day < 1) return null
        val leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
        val days = when (month) {
            2 -> if (leap) 29 else 28
            4, 6, 9, 11 -> 30
            else -> 31
        }
        return if (day <= days) "$y-$m-$d" else null
    }

    /**
     * The object literal assigned to [name] in the page's scripts. Balanced extraction
     * tolerates braces inside titles and the statements that follow in the same script.
     */
    private fun extract(html: String, name: String): JsonObject? {
        // `var x = {`, `x = {` and `window["x"] = {` all name it; anything else (`x = null`) is passed.
        val pattern = Regex("$name(?:\"])?\\s*=\\s*")
        var from = 0
        while (true) {
            val assignment = pattern.find(html, from) ?: return null
            val start = assignment.range.last + 1
            from = start
            if (html.getOrNull(start) != '{') continue
            balancedEnd(html, start)?.let { end ->
                runCatching { Json.parseToJsonElement(html.substring(start, end + 1)) as? JsonObject }
                    .getOrNull()
                    ?.let { return it }
            }
        }
    }

    /** Where the object opening at [start] closes, with strings (and their escapes) skipped. */
    private fun balancedEnd(text: String, start: Int): Int? {
        var depth = 0
        var quoted = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            if (quoted) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> quoted = false
                }
            } else {
                when (c) {
                    '"' -> quoted = true
                    '{' -> depth++
                    '}' -> if (--depth == 0) return i
                }
            }
        }
        return null
    }

    private fun JsonObject.obj(key: String) = this[key] as? JsonObject ?: JsonObject(emptyMap())
    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun String.withCount(): String? = takeIf { it.any(Char::isDigit) }
    private fun JsonElement.text(): String? = (this as? JsonObject)?.let {
        it.str("simpleText") ?: (it["runs"] as? JsonArray)?.joinToString("") { run ->
            (run as? JsonObject)?.str("text").orEmpty()
        }
    }?.takeIf { it.isNotBlank() }
    private fun JsonElement.find(key: String): List<JsonObject> = when (this) {
        is JsonObject -> listOfNotNull(this[key] as? JsonObject) + values.flatMap { it.find(key) }
        is JsonArray -> flatMap { it.find(key) }
        else -> emptyList()
    }
}
