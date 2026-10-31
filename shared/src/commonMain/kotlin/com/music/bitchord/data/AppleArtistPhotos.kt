package com.music.bitchord.data

import com.music.bitchord.data.DebugLog as Log
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodeURLQueryComponent
import io.ktor.http.isSuccess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * An artist's photo as Apple Music shows it — the square portrait on their
 * artist page — found from nothing but their name. Top Picks draws its
 * artists with these rather than YouTube's channel art.
 *
 * Two public, unauthenticated requests, both small:
 *  - the iTunes search API turns the name into the artist's Apple Music
 *    page, taken only when the name it gives back is the name asked for *and*
 *    that Apple artist shares a song or release title with the YouTube
 *    artist's own page ([AppleCatalogue]) — a name alone has put another
 *    "Krsna"'s face on KR$NA;
 *  - that page's `og:image`, read off the first few kilobytes of the page —
 *    the tag sits near the top of a document that runs to most of a megabyte,
 *    so the rest is never downloaded.
 *
 * The photo comes back as an `is1-ssl.mzstatic.com` template whose last path
 * segment is the size; callers ask for the size they draw at (see the UI's
 * `croppedAt`). Answers, misses included, are kept for the process and handed
 * to [seed] / [snapshot] to outlive it, so a row rebuilt on every Home open
 * looks each artist up once.
 */
object AppleArtistPhotos {

    private const val TAG = "AppleArtistPhotos"
    private const val HEAD_LIMIT = 64 * 1024
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0 Safari/537.36"

    /**
     * [KEY_PREFIX] + normalised name → photo, or "" for an artist Apple has no
     * (proven) photo of.
     */
    private val known = HashMap<String, String>()
    private val knownLock = Mutex()

    /**
     * Marks answers made with the catalogue check. Answers stored before it
     * were matched on name alone, so [seed] drops anything without it.
     */
    private const val KEY_PREFIX = "v2|"

    private val client get() = AppleCatalogue.http

    private val json = Json { ignoreUnknownKeys = true }

    private val OG_IMAGE = Regex("""property="og:image"\s+content="([^"]+)"""")

    /** Answers from an earlier process. */
    suspend fun seed(photos: Map<String, String>) = knownLock.withLock {
        photos.forEach { (key, url) ->
            if (key.startsWith(KEY_PREFIX) && (url.isEmpty() || isArtistPhoto(url)) && key !in known) known[key] = url
        }
    }

    /** Everything known now, misses included, for [seed] next time. */
    suspend fun snapshot(): Map<String, String> = knownLock.withLock { HashMap(known) }

    /**
     * [artistName]'s Apple Music photo, or null when Apple has none that
     * provably is them. [catalogue] is the YouTube artist's own song and
     * release titles ([AppleCatalogue.titlesOf]); it is only asked for on a
     * cache miss, since it may cost a page fetch. With no titles nothing can be
     * proved, and nothing is asked or remembered. Never throws.
     */
    suspend fun photoFor(artistName: String, catalogue: suspend () -> Collection<String>): String? {
        val name = artistName.matchKey()
        if (name.isEmpty()) return null
        val key = KEY_PREFIX + name
        knownLock.withLock { known[key] }?.let { return it.ifEmpty { null } }
        val titles = AppleCatalogue.keys(runCatching { catalogue() }.getOrDefault(emptyList()))
        if (titles.isEmpty()) return null
        val found = runCatching { lookup(artistName, name, titles) }
            .onFailure { Log.d(TAG, "lookup failed for $artistName: $it") }
            .getOrElse { return null } // a network miss: not remembered
            ?: ""
        knownLock.withLock { known[key] = found }
        return found.ifEmpty { null }
    }

    /**
     * The same search the artist page's header art makes
     * (`AppleArtistArtRepository`), so a Top Picks card shows the face that
     * page does: the US store, names compared on their letters and digits
     * alone — "Vishal-Shekhar" and "Vishal & Shekhar" are one name — and the
     * best-known match first.
     *
     * Apple can list the same artist twice, and the lesser entry often has no
     * photo, only one of their record covers. So a match whose page has no
     * real photo passes the turn to the next one with the same name. Every
     * candidate must first share a title with [titles]; one that doesn't is
     * somebody else and is never counted against [MATCHES_TRIED].
     */
    private suspend fun lookup(artistName: String, key: String, titles: Set<String>): String? {
        val url = "https://itunes.apple.com/search?term=${artistName.encodeURLQueryComponent()}" +
            "&entity=musicArtist&limit=5"
        val response = client.get(url) { header("User-Agent", USER_AGENT) }
        if (!response.status.isSuccess()) return null
        val results = json.parseToJsonElement(response.bodyAsText()).jsonObject["results"]?.jsonArray ?: return null
        var tried = 0
        for (result in results.map { it.jsonObject }) {
            if (tried >= MATCHES_TRIED) break
            if (result.string("artistName").orEmpty().matchKey() != key) continue
            val link = result.string("artistLinkUrl").orEmpty()
            if (!link.startsWith("https://music.apple.com/")) continue
            val id = result.string("artistId")?.toLongOrNull() ?: continue
            if (!AppleCatalogue.sharesTitles(id, titles)) {
                Log.d(TAG, "$artistName: Apple ${result.string("artistName")} ($id) shares no titles, skipped")
                continue
            }
            tried++
            ogImage(link.substringBefore('?'))?.let { return it }
        }
        return null
    }

    private const val MATCHES_TRIED = 2

    private fun String.matchKey(): String = AppleCatalogue.matchKey(this)

    /** The page's `og:image`, read off its head and the download dropped as soon as it's found. */
    private suspend fun ogImage(pageUrl: String): String? {
        val response = client.get(pageUrl) { header("User-Agent", USER_AGENT) }
        if (!response.status.isSuccess()) return null
        val head = response.bodyAsText().let { it.substringBefore("</head>").take(HEAD_LIMIT) }
        return OG_IMAGE.find(head)?.groupValues?.get(1)?.takeIf(::isArtistPhoto)
    }

    /**
     * A portrait rather than a stand-in. Apple's artist photos live under
     * `Features…` or `AMCArtistImages…`; an artist it has no photo of gets the
     * cover of one of their records instead (`Music…`), which on a card billed
     * "Featuring" reads as a film poster, not a person — so that counts as no
     * photo.
     */
    private fun isArtistPhoto(url: String): Boolean {
        val bucket = url.substringAfter("mzstatic.com/image/thumb/", "").substringBefore('/')
        return bucket.startsWith("Features") || bucket.startsWith("AMCArtistImages")
    }

    private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.content
}
