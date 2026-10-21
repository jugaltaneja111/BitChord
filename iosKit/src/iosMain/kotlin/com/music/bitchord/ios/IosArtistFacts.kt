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
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile

/**
 * What Replay knows about an artist beyond their name — their picture and page, found by asking
 * YouTube Music once — as the Android app's ArtistFacts keeps it. Genres come from Last.fm there,
 * and arrive here with the Last.fm sign-in; until then no artist votes for one and the genre chart
 * stays empty.
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
        val documents = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).first() as String
        "$documents/artist_facts.json"
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

    /** Genres need Last.fm, which the iOS app doesn't sign in to yet. */
    val genresAvailable: Boolean get() = false

    fun genresFor(@Suppress("UNUSED_PARAMETER") artist: String): List<String> = emptyList()

    fun imageFor(artist: String): String? = known[key(artist)]?.image

    fun browseIdFor(artist: String): String? = known[key(artist)]?.browseId

    /** Asks about [artist] if nothing is known yet, or the last answer was nothing long enough ago. */
    fun noticed(artist: String) {
        if (!started) return
        val key = key(artist)
        if (key.isEmpty() || key.length > MAX_NAME_LENGTH) return
        val entry = known[key]
        if (entry != null && (entry.browseId != null || wallClockMs() - entry.cardAt < RETRY_DAYS * DAY_MS)) return
        if (!queued.add(key)) return
        requests.trySend(artist.trim())
    }

    private suspend fun worker() {
        for (name in requests) {
            runCatching { fetchCard(name) }
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

    private fun save() {
        if (!dirty) return
        dirty = false
        val stored = Stored(artists = known.values.sortedByDescending { it.cardAt }.take(MAX_ARTISTS))
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

    private const val MAX_NAME_LENGTH = 120
    private const val MAX_ARTISTS = 4_000
    private const val REQUEST_SPACING_MS = 1_500L
    private const val SAVE_INTERVAL_MS = 5_000L
    private const val RETRY_DAYS = 14L
    private const val DAY_MS = 86_400_000L
}
