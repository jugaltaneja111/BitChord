package com.music.bitchord.ios

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.appHttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import platform.Foundation.NSString
import platform.Foundation.decomposedStringWithCanonicalMapping

/**
 * A handwritten signature for an artist, from the listener's own artist-signature API
 * (github.com/itsmeadarsh2008/artist-signature). [aspect] is width over height, for laying it
 * out before it loads; [format] is the asset's, lowercased (`svg`, `png`, …), "" when unknown.
 */
internal data class ArtistSignature(val imageUrl: String, val aspect: Float, val format: String = "")

/**
 * The phone's ArtistSignatureRepository: a name-keyed lookup against the configured base URL,
 * remembered for the session, never throwing, and null for "nothing known" — an unset provider,
 * an artist with no signature, or a name the API calls ambiguous (it answers `matches[]` rather
 * than guess).
 *
 * When there are several, the best wins, in the server's own order: a redistributable license,
 * then SVG, then how well it is verified, then a panoramic shape (wide images are usually whole
 * signatures; square ones are often fragments); a bigger image breaks a tie.
 */
internal object IosArtistSignatures {
    private const val TAG = "ArtistSignature"
    private const val CACHE_SIZE = 64
    private const val MAX_BASE_URL_LENGTH = 500

    private val http by lazy { appHttpClient { install(HttpTimeout) } }
    private val json = Json { ignoreUnknownKeys = true }

    private class Entry(val signature: ArtistSignature?)

    private val cache = LinkedHashMap<String, Entry>()

    private val _updates = MutableStateFlow(0)
    /** Ticks whenever a lookup settles or the cache is cleared. */
    val updates: StateFlow<Int> = _updates.asStateFlow()

    fun normalizeBaseUrl(raw: String): String = raw.trim().trimEnd('/').take(MAX_BASE_URL_LENGTH)

    fun isValidBaseUrl(raw: String): Boolean {
        val base = normalizeBaseUrl(raw)
        return base.startsWith("http://") || base.startsWith("https://")
    }

    /** The base URL to ask, or null while the provider is off or unset. */
    fun effectiveBaseUrl(): String? {
        if (!IosSettings.signatureApiEnabled.flow.value) return null
        return normalizeBaseUrl(IosSettings.signatureApiUrl.flow.value).takeIf(::isValidBaseUrl)
    }

    fun cached(artist: String, base: String?): ArtistSignature? {
        if (base.isNullOrEmpty()) return null
        return cache[key(artist, base) ?: return null]?.signature
    }

    suspend fun signatureFor(artist: String, base: String?): ArtistSignature? {
        if (base.isNullOrEmpty()) return null
        val key = key(artist, base) ?: return null
        cache[key]?.let { return it.signature }
        val entry = runCatching { lookup(artist, base) }
            .onFailure { TrackLog.d(TAG, "lookup failed for $artist: ${it.message}") }
            .getOrNull() ?: return null
        cache[key] = entry
        while (cache.size > CACHE_SIZE) cache.remove(cache.keys.first())
        _updates.value++
        return entry.signature
    }

    fun clear() {
        cache.clear()
        _updates.value++
    }

    /** Whether `GET base/v1/health` answers 2xx. Never throws. */
    suspend fun checkHealth(raw: String): Boolean = runCatching {
        val base = normalizeBaseUrl(raw)
        if (!isValidBaseUrl(base)) return false
        http.get("$base/v1/health") { timeout { requestTimeoutMillis = 15_000 } }.status.isSuccess()
    }.getOrDefault(false)

    private suspend fun lookup(artist: String, base: String): Entry? {
        val response = http.get("$base/v1/signatures?artist=${artist.encodeURLParameter()}") {
            timeout { requestTimeoutMillis = 15_000 }
        }
        if (!response.status.isSuccess()) return null
        return Entry(parse(response.bodyAsText()))
    }

    private fun parse(body: String): ArtistSignature? {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        if ("signatures" !in root && "matches" in root) return null
        val signatures = root["signatures"] as? JsonArray ?: return null
        var best: ArtistSignature? = null
        var bestScore = Int.MIN_VALUE
        var bestArea = -1L
        for (element in signatures) {
            val sig = element as? JsonObject ?: continue
            val url = urlOf(sig) ?: continue
            val score = rank(sig, url)
            val area = areaOf(sig)
            if (best == null || score > bestScore || (score == bestScore && area > bestArea)) {
                bestScore = score
                bestArea = area
                best = ArtistSignature(url, aspectOf(sig), formatOf(sig, url))
            }
        }
        return best
    }

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }
    private fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject

    private fun formatOf(sig: JsonObject, url: String): String =
        sig.obj("asset")?.string("format")?.lowercase()?.takeIf(String::isNotBlank)
            ?: sig.string("format")?.lowercase()?.takeIf(String::isNotBlank)
            ?: url.substringBefore('?').substringAfterLast('.', "").lowercase()

    private fun rank(sig: JsonObject, url: String): Int {
        val license = when (sig.obj("license")?.string("status")?.lowercase()) {
            "known" -> 2
            "requires_review" -> 1
            else -> 0
        }
        val format = when (formatOf(sig, url)) {
            "svg" -> 2
            "png" -> 1
            else -> 0
        }
        val verification = when (sig.string("verification")?.lowercase()) {
            "artist_verified" -> 3
            "verified" -> 2
            "unverified" -> 1
            else -> 0
        }
        val panoramic = if (aspectOf(sig) >= 1.4f) 1 else 0
        return license * 1000 + format * 100 + verification * 10 + panoramic
    }

    private fun urlOf(sig: JsonObject): String? {
        sig.obj("asset")?.string("url")?.takeIf { it.startsWith("http") }?.let { return it }
        (sig["asset"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.startsWith("http") }?.let { return it }
        return listOf("url", "original_url", "originalUrl").firstNotNullOfOrNull { name ->
            sig.string(name)?.takeIf { it.startsWith("http") }
        }
    }

    private fun sizeOf(sig: JsonObject): Pair<Int, Int>? {
        val asset = sig.obj("asset")
        val w = sig.int("width") ?: asset?.int("width") ?: return null
        val h = sig.int("height") ?: asset?.int("height") ?: return null
        return w to h
    }

    private fun aspectOf(sig: JsonObject): Float = sizeOf(sig)?.let { (w, h) -> w.toFloat() / h } ?: 2.5f

    private fun areaOf(sig: JsonObject): Long = sizeOf(sig)?.let { (w, h) -> w.toLong() * h } ?: 0L

    /** Case, accents and punctuation don't tell artists apart, as with Apple's lookup on the phone. */
    @Suppress("CAST_NEVER_SUCCEEDS")
    private fun key(artist: String, base: String): String? {
        val folded = (artist.lowercase() as NSString).decomposedStringWithCanonicalMapping
            .filter { it.isLetterOrDigit() }
        if (folded.isEmpty()) return null
        return "${normalizeBaseUrl(base)}\n$folded"
    }
}
