package com.music.bitchord.ios.canvas

import io.ktor.http.encodeURLParameter
import kotlinx.cinterop.BetaInteropApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import platform.Foundation.NSString
import platform.Foundation.create
import platform.Foundation.decomposedStringWithCanonicalMapping

/**
 * The looping clip Apple Music shows at the top of some artists' pages — the phone's
 * AppleArtistArtRepository, for its video only, since the iPhone's artist page keeps YouTube's
 * photograph. Two public requests: the iTunes search turns the name into an Apple Music page,
 * and that page's embedded hydration JSON names the clip. None of it is an API Apple publishes,
 * so every surprise is "no clip". An answer is only taken when Apple's name is the one asked
 * for, and every settled answer, a clip or none, is remembered for the session.
 */
internal object AppleArtistVideo {
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val known = HashMap<String, String?>()

    /** The clip's HLS playlist for [artistName], or null when Apple has none. Never throws. */
    suspend fun videoFor(artistName: String): String? {
        val key = artistName.normalized()
        if (key.isEmpty()) return null
        mutex.withLock { if (key in known) return known[key] }
        // Null from lookup is a network hiccup rather than an answer, and isn't remembered.
        val found = try {
            lookup(artistName, key)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return null
        mutex.withLock { known[key] = found.value }
        return found.value
    }

    private class Found(val value: String?)

    private suspend fun lookup(artistName: String, key: String): Found? {
        val headers = mapOf("User-Agent" to USER_AGENT)
        val search = canvasGet(
            "https://itunes.apple.com/search?term=${artistName.encodeURLParameter()}&entity=musicArtist&limit=5",
            headers,
        ) ?: return null
        val results = json.parseToJsonElement(search).jsonObject["results"] as? JsonArray ?: return Found(null)
        val match = results.mapNotNull { it as? JsonObject }
            .firstOrNull { (it["artistName"]?.jsonPrimitive?.contentOrNull).orEmpty().normalized() == key }
            ?: return Found(null)
        val page = match["artistLinkUrl"]?.jsonPrimitive?.contentOrNull?.substringBefore('?')?.ifBlank { null }
            ?: match["artistId"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0 }?.let { "https://music.apple.com/us/artist/$it" }
            ?: return Found(null)
        val html = canvasGet(page, headers) ?: return null
        return Found(parse(html))
    }

    private fun parse(html: String): String? {
        val marker = html.indexOf("id=\"serialized-server-data\"")
        if (marker < 0) return null
        val start = html.indexOf('>', marker) + 1
        val end = html.indexOf("</script>", start)
        if (start <= 0 || end < 0) return null
        val header = findHeader(runCatching { json.parseToJsonElement(html.substring(start, end)) }.getOrNull()) ?: return null
        val clip = (header["videoArtwork"] as? JsonObject)?.get("dictionary")?.let { it as? JsonObject }
            ?.get("motionArtistSquare1x1") as? JsonObject
        return clip?.get("video")?.jsonPrimitive?.contentOrNull?.takeIf { it.startsWith("http") }
    }

    /** The first object anywhere in [node] that has an `artistLogo` key: the page's header. */
    private fun findHeader(node: JsonElement?): JsonObject? {
        when (node) {
            is JsonObject -> {
                if ("artistLogo" in node) return node
                node.values.forEach { child -> findHeader(child)?.let { return it } }
            }
            is JsonArray -> node.forEach { child -> findHeader(child)?.let { return it } }
            else -> Unit
        }
        return null
    }

    /** Case, accents and punctuation are not what makes two names different. */
    @OptIn(BetaInteropApi::class)
    private fun String.normalized(): String =
        NSString.create(string = lowercase()).decomposedStringWithCanonicalMapping
            .filter { it.isLetterOrDigit() }
}
