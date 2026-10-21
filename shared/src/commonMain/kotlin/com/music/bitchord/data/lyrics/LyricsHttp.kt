package com.music.bitchord.data.lyrics

import com.music.bitchord.data.appHttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.URLBuilder
import io.ktor.http.isSuccess
import io.ktor.http.takeFrom
import kotlinx.serialization.json.Json

/**
 * Shared plumbing for the lyric providers.
 *
 * They are all raced against each other by [LyricsRepository], so a provider
 * that hangs holds up the whole lookup. [LYRICS_TIMEOUT_MS] is deliberately
 * far shorter than the stream-oriented timeouts: a lyric that arrives after
 * the second chorus is of no use to anyone, and the fallbacks behind it are
 * the better answer.
 */
private const val LYRICS_TIMEOUT_MS = 6_000L
private const val LYRICS_CONNECT_TIMEOUT_MS = 3_000L

internal const val LYRICS_AGENT = "BitChord (https://github.com/bitchord)"

val lyricsJson = Json { ignoreUnknownKeys = true; isLenient = true }

// On the JVM this shares the player's OkHttp connection pool and DNS; only the
// deadline, set per request, differs.
private val client by lazy { appHttpClient { install(HttpTimeout) } }

/**
 * Body of a successful GET with [headers], or null for any failure at all.
 * [timeoutMs] bounds the whole call, [connectTimeoutMs] reaching the host.
 */
internal suspend fun lyricsFetch(
    url: String,
    headers: Map<String, String>,
    timeoutMs: Long = LYRICS_TIMEOUT_MS,
    connectTimeoutMs: Long = LYRICS_CONNECT_TIMEOUT_MS,
): String? = runCatching {
    val response = client.get(url) {
        headers.forEach { (name, value) -> header(name, value) }
        timeout {
            requestTimeoutMillis = timeoutMs
            connectTimeoutMillis = connectTimeoutMs
        }
    }
    if (response.status.isSuccess()) response.bodyAsText() else null
}.getOrNull()

/** Body of a successful GET, or null for any failure at all. */
internal suspend fun lyricsGet(url: String): String? = lyricsFetch(
    url,
    mapOf("User-Agent" to LYRICS_AGENT, "Accept" to "application/json"),
)

/**
 * Body of an authenticated provider GET without service-specific browser headers.
 *
 * PaxSenix's authenticated routes often have to query an upstream catalogue
 * before answering, so this matches PaxSenix's own client timeout rather than
 * the fast one: valid requests are not discarded while its backend is still
 * resolving a track.
 */
internal suspend fun lyricsGetBearer(url: String, bearer: String): String? {
    if (bearer.isBlank()) return null
    return lyricsFetch(
        url,
        mapOf(
            "User-Agent" to LYRICS_AGENT,
            "Accept" to "application/json, text/plain, */*",
            "Authorization" to "Bearer $bearer",
        ),
        timeoutMs = 15_000L,
        connectTimeoutMs = 10_000L,
    )
}

/**
 * [lyricsGet], with a bearer token and the headers Apple's own web player
 * sends alongside one — `amp-api.music.apple.com` answers a token with no
 * `Origin` at all the same way it answers a wrong one, with a 403.
 */
internal suspend fun lyricsGetAuthorized(url: String, bearer: String): String? = lyricsFetch(
    url,
    mapOf(
        "User-Agent" to LYRICS_AGENT,
        "Accept" to "application/json",
        "Authorization" to "Bearer $bearer",
        "Origin" to "https://music.apple.com",
        "Referer" to "https://music.apple.com/",
    ),
)

/** A built request URL; [toString] is the URL itself. */
internal class LyricsUrl(private val value: String) {
    override fun toString() = value
}

/** Query-string building in the shape the providers were written against. */
internal class LyricsUrlBuilder(base: String) {
    private val builder = URLBuilder(base)

    fun addQueryParameter(name: String, value: String?): LyricsUrlBuilder = apply {
        if (value != null) builder.parameters.append(name, value)
    }

    fun build(): LyricsUrl = LyricsUrl(builder.buildString())
}

internal fun String.toLyricsUrlBuilder() = LyricsUrlBuilder(this)

/** [reference], absolute or relative, read against [base]; null if it is no URL at all. */
internal fun resolveLyricsUrl(base: String, reference: String): String? = runCatching {
    URLBuilder(base).takeFrom(reference).buildString()
}.getOrNull()
