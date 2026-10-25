package com.music.bitchord.ios

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.wallClockMs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import com.music.bitchord.data.appHttpClient
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile

/**
 * What Replay knows about an artist beyond their name — their picture and page, found by asking
 * YouTube Music once — and their genres, from Last.fm's top tags filtered through a fixed list of
 * real genres, as the Android app's ArtistFacts keeps them. Genres need a Last.fm API key (the
 * build's or the listener's); without one the genre chart isn't drawn.
 *
 * Lookups go one at a time, spaced out, and are remembered on disk, so a name is asked about once
 * and again only after [RETRY_DAYS] if nothing was found.
 */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
internal object IosArtistFacts {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val known = HashMap<String, StoredArtist>()
    private val queued = HashSet<String>()
    private val requests = Channel<String>(Channel.UNLIMITED)
    private var dirty = false
    private var started = false

    private val _revision = MutableStateFlow(0)
    /** Raised whenever something new is known, so a Replay merged before it is merged again. */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private val path: String by lazy {
        appDataPath("artist_facts.json")
    }

    /** Reads what is known and starts the lookups. Called once, at launch. */
    fun start() {
        if (started) return
        started = true
        NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null)?.let { text ->
            runCatching { json.decodeFromString(Stored.serializer(), text) }.getOrNull()
                ?.artists?.forEach { known[it.key] = it }
        }
        scope.launch { worker() }
        scope.launch {
            while (true) {
                delay(SAVE_INTERVAL_MS)
                save()
            }
        }
    }

    /** Whether a genre chart can be drawn: Last.fm answers only with a key. */
    val genresAvailable: Boolean get() = IosSettings.replayGenres.flow.value && IosLastFm.apiKey.isNotEmpty()

    fun genresFor(artist: String): List<String> =
        if (!genresAvailable) emptyList() else known[key(artist)]?.genres.orEmpty()

    fun imageFor(artist: String): String? = known[key(artist)]?.image

    fun browseIdFor(artist: String): String? = known[key(artist)]?.browseId

    /** Asks about [artist] if nothing is known yet, or the last answer was nothing long enough ago. */
    fun noticed(artist: String) {
        if (!started) return
        val key = key(artist)
        if (key.isEmpty() || key.length > MAX_NAME_LENGTH) return
        val entry = known[key]
        val now = wallClockMs()
        val wantsCard = entry == null || (entry.browseId == null && now - entry.cardAt >= RETRY_DAYS * DAY_MS)
        val wantsGenres = genresAvailable && entry?.genres.isNullOrEmpty() &&
            now - (entry?.genresAt ?: 0L) >= RETRY_DAYS * DAY_MS
        if (!wantsCard && !wantsGenres) return
        if (!queued.add(key)) return
        requests.trySend(artist.trim())
    }

    private suspend fun worker() {
        for (name in requests) {
            val entry = known[key(name)]
            val now = wallClockMs()
            if (entry == null || (entry.browseId == null && now - entry.cardAt >= RETRY_DAYS * DAY_MS)) {
                runCatching { fetchCard(name) }
            }
            if (genresAvailable && known[key(name)]?.genres.isNullOrEmpty()) runCatching { fetchGenres(name) }
            _revision.value++
            delay(REQUEST_SPACING_MS)
        }
    }

    private suspend fun fetchCard(name: String) {
        val hit = YtMusicRepository.search(name, SearchFilter.ARTISTS).getOrNull()
            ?.filterIsInstance<SearchResult.Browse>()
            ?.firstOrNull()
            ?.item
            ?.takeIf { it.title.equals(name, ignoreCase = true) }
        val entry = known[key(name)] ?: StoredArtist(key = key(name))
        known[key(name)] = entry.copy(
            image = hit?.thumbnailUrl ?: entry.image,
            browseId = hit?.browseId ?: entry.browseId,
            cardAt = wallClockMs(),
        )
        dirty = true
    }

    private suspend fun fetchGenres(name: String) {
        val url = "https://ws.audioscrobbler.com/2.0/?method=artist.gettoptags" +
            "&artist=${name.encodeURLParameter()}" +
            "&api_key=${IosLastFm.apiKey.encodeURLParameter()}" +
            "&autocorrect=1&format=json"
        val response = http.get(url) { header("User-Agent", "BitChord") }
        if (!response.status.isSuccess()) return
        val tags = runCatching {
            Json.parseToJsonElement(response.bodyAsText()).jsonObject["toptags"]
                ?.jsonObject?.get("tag")?.jsonArray
                ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.content }
        }.getOrNull().orEmpty()
        val genres = tags.asSequence().mapNotNull(::canonical).distinct().take(MAX_GENRES_PER_ARTIST).toList()
        val entry = known[key(name)] ?: StoredArtist(key = key(name))
        known[key(name)] = entry.copy(genres = genres, genresAt = wallClockMs())
        dirty = true
    }

    /** A tag as a genre, or null if it isn't one: "seen live" and "awesome" are not genres. */
    private fun canonical(tag: String): String? {
        val cleaned = tag.trim().lowercase()
            .replace('-', ' ')
            .replace("&", "and")
            .replace(Regex("[^a-z0-9 ]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (cleaned.isEmpty()) return null
        return VOCABULARY[cleaned] ?: VOCABULARY[ALIASES[cleaned] ?: return null]
    }

    private val http by lazy { appHttpClient {} }

    private fun save() {
        if (!dirty) return
        dirty = false
        val stored = Stored(artists = known.values.sortedByDescending { maxOf(it.cardAt, it.genresAt) }.take(MAX_ARTISTS))
        NSString.create(string = json.encodeToString(Stored.serializer(), stored))
            .writeToFile(path, atomically = true, encoding = NSUTF8StringEncoding, error = null)
    }

    private fun key(artist: String): String = artist.trim().lowercase()

    @Serializable
    private data class Stored(val version: Int = 2, val artists: List<StoredArtist> = emptyList())

    @Serializable
    private data class StoredArtist(
        val key: String,
        val genres: List<String> = emptyList(),
        val genresAt: Long = 0L,
        val image: String? = null,
        val browseId: String? = null,
        val cardAt: Long = 0L,
    )

    private const val MAX_GENRES_PER_ARTIST = 2
    private const val MAX_NAME_LENGTH = 120
    private const val MAX_ARTISTS = 4_000
    private const val REQUEST_SPACING_MS = 1_500L
    private const val SAVE_INTERVAL_MS = 5_000L
    private const val RETRY_DAYS = 14L
    private const val DAY_MS = 86_400_000L

    /**
     * Genres whose display name isn't just their words capitalised.
     *
     * Declared before [VOCABULARY] because that is where it is read: an object's
     * properties initialise in source order, and a lookup table consulted by an
     * earlier initialiser is still null when it runs.
     */
    private val SPELLINGS = mapOf(
        "randb" to "R&B",
        "edm" to "EDM",
        "lo fi" to "Lo-Fi",
        "hip hop" to "Hip-Hop",
        "k pop" to "K-Pop",
        "j pop" to "J-Pop",
        "j rock" to "J-Rock",
        "c pop" to "C-Pop",
        "drum and bass" to "Drum & Bass",
        "singer songwriter" to "Singer-Songwriter",
        "post punk" to "Post-Punk",
        "post rock" to "Post-Rock",
        "bossa nova" to "Bossa Nova",
    )

    /**
     * The genres a chart may name, mapped from their normalised form to how they
     * are spelt on screen.
     *
     * A fixed list rather than a heuristic because the failure mode of a
     * heuristic here is silent and permanent: a tag that slips through becomes a
     * row on someone's Replay, and there is no signal anywhere that it was
     * wrong. Adding to this list is cheap; letting it grow itself is not.
     */
    private val VOCABULARY: Map<String, String> = listOf(
        "pop", "rock", "hip hop", "rap", "randb", "soul", "funk", "jazz", "blues",
        "country", "folk", "indie", "indie pop", "indie rock", "alternative",
        "alternative rock", "metal", "heavy metal", "punk", "punk rock", "hardcore",
        "electronic", "house", "deep house", "techno", "trance", "dubstep",
        "drum and bass", "edm", "ambient", "lo fi", "synthpop", "disco",
        "classical", "opera", "soundtrack", "instrumental", "acoustic",
        "reggae", "reggaeton", "dancehall", "ska", "latin", "salsa", "bossa nova",
        "afrobeats", "afrobeat", "k pop", "j pop", "j rock", "c pop",
        "bollywood", "punjabi", "desi", "bhangra", "hindi", "sufi", "ghazal",
        "singer songwriter", "emo", "grunge", "shoegaze", "psychedelic",
        "progressive rock", "hard rock", "garage rock", "post punk", "new wave",
        "gospel", "christian", "world", "experimental", "trap", "drill", "grime",
        "phonk", "hyperpop", "chillout", "downtempo", "jungle", "garage",
        "bluegrass", "americana", "swing", "big band", "motown", "britpop",
        "dream pop", "art pop", "noise", "industrial", "gothic", "doom metal",
        "black metal", "death metal", "thrash metal", "metalcore", "post rock",
        "math rock", "jam band", "surf rock", "rockabilly", "boom bap",
        "cloud rap", "conscious hip hop", "west coast rap", "east coast rap",
    ).associateWith { normalised ->
        SPELLINGS[normalised] ?: normalised.split(" ").joinToString(" ") { word ->
            word.replaceFirstChar { it.uppercase() }
        }
    }

    /** Tags that are a genre in [VOCABULARY] under another name. */
    private val ALIASES = mapOf(
        "rnb" to "randb",
        "r and b" to "randb",
        "rhythm and blues" to "randb",
        "contemporary randb" to "randb",
        "hiphop" to "hip hop",
        "hip hop rap" to "hip hop",
        "lofi" to "lo fi",
        "lo fi hip hop" to "lo fi",
        "chillhop" to "lo fi",
        "kpop" to "k pop",
        "jpop" to "j pop",
        "jrock" to "j rock",
        "cpop" to "c pop",
        "korean" to "k pop",
        "dnb" to "drum and bass",
        "drum n bass" to "drum and bass",
        "drumandbass" to "drum and bass",
        "electronica" to "electronic",
        "electro" to "electronic",
        "dance" to "electronic",
        "electropop" to "synthpop",
        "synth pop" to "synthpop",
        "indierock" to "indie rock",
        "indiepop" to "indie pop",
        "alt rock" to "alternative rock",
        "altrock" to "alternative rock",
        "singersongwriter" to "singer songwriter",
        "female vocalists" to "pop",
        "hindi pop" to "bollywood",
        "indian" to "desi",
        "filmi" to "bollywood",
        "afro beats" to "afrobeats",
        "afropop" to "afrobeats",
        "amapiano" to "afrobeats",
        "regueton" to "reggaeton",
        "latin pop" to "latin",
        "trip hop" to "downtempo",
        "nu metal" to "metal",
        "classic rock" to "rock",
        "soft rock" to "rock",
        "pop rock" to "rock",
        "pop punk" to "punk",
        "hardcore punk" to "hardcore",
        "orchestral" to "classical",
        "film score" to "soundtrack",
        "score" to "soundtrack",
        "ost" to "soundtrack",
        "chill" to "chillout",
        "chillwave" to "chillout",
        "worship" to "christian",
        "rap rock" to "rap",
        "gangsta rap" to "rap",
        "underground hip hop" to "hip hop",
    )
}
