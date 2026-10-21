package com.music.bitchord.ios.canvas

import com.music.bitchord.data.appHttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import kotlin.coroutines.cancellation.CancellationException
import platform.Foundation.NSLocale
import platform.Foundation.countryCode
import platform.Foundation.currentLocale

/**
 * One client for every canvas lookup, so they share a connection pool rather
 * than each provider standing up its own. The Android side borrows the app's
 * OkHttp client, which carries its own timeouts; Ktor's Darwin engine would
 * otherwise wait on NSURLSession's minute-long default, and a canvas that
 * takes that long to find has missed the track it was for.
 */
private val canvasHttp by lazy {
    appHttpClient {
        install(HttpTimeout) {
            requestTimeoutMillis = 10_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 10_000
        }
    }
}

/**
 * A plain GET returning the body, or null for anything that isn't a 2xx or
 * that throws. Canvas is decoration: no provider failure is allowed to reach
 * the caller, and none of these hosts are ours to depend on.
 */
internal suspend fun canvasGet(url: String, headers: Map<String, String> = emptyMap()): String? =
    canvasGetWithStatus(url, headers).second

/**
 * Same as [canvasGet], but keeps the status code even on failure — for the
 * few callers where "it wasn't a 2xx" needs to say *which* code, rather than
 * collapsing every kind of failure into the same null.
 */
internal suspend fun canvasGetWithStatus(url: String, headers: Map<String, String> = emptyMap()): Pair<Int, String?> =
    try {
        val response = canvasHttp.get(url) {
            headers.forEach { (name, value) -> header(name, value) }
        }
        response.status.value to if (response.status.isSuccess()) response.bodyAsText() else null
    } catch (e: CancellationException) {
        // A cancelled lookup is the caller moving on, not a provider failing;
        // swallowing it would leave the coroutine running to no one.
        throw e
    } catch (_: Throwable) {
        -1 to null
    }

/** What a POST came back with: the status (-1 when it threw), the body on a 2xx, and why it threw. */
internal class CanvasPostResult(val code: Int, val body: ByteArray?, val error: String? = null)

/**
 * A POST of [body] as [contentType]. The body is handed back raw, since one
 * caller reads protobuf off it; [CanvasPostResult.body] is null for a non-2xx.
 */
internal suspend fun canvasPost(
    url: String,
    body: ByteArray,
    contentType: ContentType,
    headers: Map<String, String> = emptyMap(),
): CanvasPostResult =
    try {
        val response: HttpResponse = canvasHttp.post(url) {
            headers.forEach { (name, value) -> header(name, value) }
            // Content-Type is the body's to set; Ktor refuses it as a plain header.
            setBody(ByteArrayContent(body, contentType))
        }
        CanvasPostResult(
            code = response.status.value,
            body = if (response.status.isSuccess()) response.readRawBytes() else null,
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        CanvasPostResult(code = -1, body = null, error = e.message ?: e::class.simpleName)
    }

/**
 * [base] with [params] as its query string, each name and value percent-encoded
 * the way OkHttp's `addQueryParameter` does (spaces as `%20`, not `+`).
 */
internal fun canvasUrl(base: String, vararg params: Pair<String, String>): String =
    if (params.isEmpty()) {
        base
    } else {
        base + "?" + params.joinToString("&") { (name, value) ->
            "${name.encodeURLParameter()}=${value.encodeURLParameter()}"
        }
    }

/** The browser UA these catalog endpoints expect; they 403 an unknown one. */
internal const val CANVAS_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/122.0.0.0 Safari/537.36"

/**
 * The device's two-letter region, lowercased, or null when it doesn't have a
 * sane one. The providers' storefronts and catalogues are regional.
 */
internal fun deviceCountry(): String? = runCatching {
    NSLocale.currentLocale.countryCode
}.getOrNull()?.takeIf { it.length == 2 }?.lowercase()
