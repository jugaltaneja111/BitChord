package com.music.bitchord.data

import com.music.bitchord.data.model.ArtistPage
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.Song
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess

/**
 * Proof that an Apple Music artist found by name is the YouTube artist meant.
 *
 * A name is not an identity: Apple has a "Krsna" who is not YouTube's KR$NA,
 * and the iTunes search ranks either first depending on the day. So anything
 * borrowed from Apple for a YouTube artist — header art, a Top Picks photo — is
 * taken only from an Apple artist whose catalogue shares a song or release
 * title with the YouTube page's own.
 */
object AppleCatalogue {

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0 Safari/537.36"

    private val json = Json { ignoreUnknownKeys = true }

    internal val http by lazy {
        appHttpClient {
            install(io.ktor.client.plugins.HttpTimeout) {
                requestTimeoutMillis = 8_000
                connectTimeoutMillis = 4_000
            }
        }
    }

    /**
     * Case, accents and punctuation are not what makes two names different. A
     * `$` standing in for an S is read as one: Apple's KR$NA is YouTube's Krsna.
     */
    fun matchKey(name: String): String =
        decomposed(name.lowercase().replace('$', 's'))
            .filter { it.isLetterOrDigit() }

    /**
     * A title reduced to what both services agree on: Apple appends " - Single"
     * or " - EP" and folds features into the title, YouTube does neither. Too
     * short a title ("War", "Intro") proves nothing and is dropped.
     */
    fun titleKey(title: String): String? =
        matchKey(
            title.substringBefore(" (").substringBefore(" [")
                .removeSuffix(" - Single").removeSuffix(" - EP"),
        ).takeIf { it.length >= 4 }

    /** [titles] as the keys [sharesTitles] compares; empty means nothing to prove with. */
    fun keys(titles: Collection<String>): Set<String> = titles.mapNotNullTo(HashSet(), ::titleKey)

    /**
     * Whether Apple artist [artistId] has a song or release whose [titleKey] is
     * in [keys]. One lookup covers both: each track row carries its album's
     * name. A failed request counts as no — not borrowing is the safe answer.
     */
    suspend fun sharesTitles(artistId: Long, keys: Set<String>): Boolean {
        if (keys.isEmpty()) return false
        val body = runCatching {
            val response = http.get("https://itunes.apple.com/lookup?id=$artistId&entity=song&limit=200") {
                header("User-Agent", USER_AGENT)
            }
            if (response.status.isSuccess()) response.bodyAsText() else null
        }.getOrNull() ?: return false
        val rows = json.parseToJsonElement(body).jsonObject["results"]?.jsonArray ?: return false
        return rows.any { row ->
            val o = row.jsonObject
            listOf("trackName", "collectionName").any { field ->
                o[field]?.jsonPrimitive?.content?.let(::titleKey) in keys
            }
        }
    }

    /**
     * The titles a YouTube artist page vouches for — its songs and its own
     * releases (`MPRE…` pages). Playlists and other artists on its shelves are
     * not its catalogue.
     */
    fun titlesOf(songs: List<Song>, sections: List<HomeShelf>): List<String> =
        songs.map { it.title } +
            sections.flatMap { it.items }.filter { it.browseId?.startsWith("MPRE") == true }.map { it.title }

    fun titlesOf(page: ArtistPage): List<String> = titlesOf(page.songs, page.sections)
}
