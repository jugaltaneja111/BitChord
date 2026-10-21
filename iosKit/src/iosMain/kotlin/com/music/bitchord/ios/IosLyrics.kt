package com.music.bitchord.ios

import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsRepository
import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.durationMillis
import com.music.bitchord.ui.LyricsProviderState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The playing track's lyrics, as the phone's MainViewModel finds them: every enabled source raced
 * at once and taken in the listener's order ([LyricsRepository]), each source's outcome kept so
 * the player's provider drawer can switch between them without asking again.
 */
internal object IosLyrics {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _lyrics = MutableStateFlow<List<LyricLine>?>(null)
    val lyrics: StateFlow<List<LyricLine>?> = _lyrics.asStateFlow()

    private val _source = MutableStateFlow<LyricsSource?>(null)
    val source: StateFlow<LyricsSource?> = _source.asStateFlow()

    /** True once the lookup for the playing track has finished, found or not. */
    private val _checked = MutableStateFlow(false)
    val checked: StateFlow<Boolean> = _checked.asStateFlow()

    private val _providerStates = MutableStateFlow<Map<LyricsSource, LyricsProviderState>>(emptyMap())
    val providerStates: StateFlow<Map<LyricsSource, LyricsProviderState>> = _providerStates.asStateFlow()

    private data class Request(
        val videoId: String,
        val title: String,
        val artist: String,
        val durationMs: Long,
        val album: String?,
    )

    private var request: Request? = null
    private var generation = 0L
    private var selected: LyricsSource? = null
    private val results = HashMap<LyricsSource, LyricsRepository.Result>()
    private var job: Job? = null
    private val manualJobs = HashMap<LyricsSource, Job>()

    /** What is loaded: the track and the settings that chose it, so a settings change looks again. */
    private var loadedFor: Pair<String, List<LyricsSource>>? = null

    /** Called as the playing track, its length or the lyric settings change; a no-op when loaded. */
    fun load(song: Song, playerDurationMs: Long) {
        val order = IosSettings.lyricsSourceOrder.flow.value
        val enabled = IosSettings.lyricsSources.flow.value.toSet()
        val sources = if (IosSettings.syncedLyrics.flow.value) order.filter { it in enabled } else emptyList()
        val key = song.videoId to sources
        if (loadedFor == key) return
        // A database match needs the length, which lands a beat after the track; the catalogue's
        // stands in until the player knows.
        val durationMs = playerDurationMs.takeIf { it > 0L } ?: song.durationMillis()
        if (durationMs <= 0L) return
        loadedFor = key
        generation++
        val asked = generation
        val current = Request(song.videoId, song.title, song.artist, durationMs, song.albumName)
        request = current
        selected = null
        manualJobs.values.forEach(Job::cancel)
        manualJobs.clear()
        results.clear()
        _providerStates.value = LyricsSource.entries.associateWith { LyricsProviderState.NOT_FETCHED }
        _lyrics.value = null
        _source.value = null
        job?.cancel()
        if (sources.isEmpty()) {
            // Switched off: the player drops the lyric strip rather than reporting none.
            _checked.value = true
            return
        }
        _checked.value = false
        job = scope.launch {
            val found = LyricsRepository.lyrics(
                current.videoId, current.title, current.artist, current.durationMs, current.album,
                sources.toSet(), order, IosSettings.prioritizeSyllableSync.flow.value,
                onSourceStarted = { scope.launch { started(asked, it) } },
                onSourceResult = { source, result -> scope.launch { finished(asked, source, result) } },
                onSourceCancelled = { scope.launch { cancelled(asked, it) } },
            )
            if (asked != generation) return@launch
            val shown = selected?.let(results::get) ?: found
            _lyrics.value = shown?.lines
            _source.value = shown?.source
            _checked.value = true
        }
    }

    /** A provider picked in the player's drawer: a hit shows at once, an untried one is asked. */
    fun select(source: LyricsSource) {
        val current = request ?: return
        when (_providerStates.value[source]) {
            LyricsProviderState.FOUND -> {
                selected = source
                results[source]?.let(::show)
            }
            LyricsProviderState.FETCHING -> selected = source
            LyricsProviderState.NOT_FETCHED, null -> {
                selected = source
                fetchOne(current, generation, source)
            }
            LyricsProviderState.NOT_FOUND -> Unit
        }
    }

    private fun fetchOne(current: Request, asked: Long, source: LyricsSource) {
        if (manualJobs[source]?.isActive == true) return
        manualJobs[source] = scope.launch {
            LyricsRepository.lyrics(
                videoId = current.videoId,
                title = current.title,
                artist = current.artist,
                durationMs = current.durationMs,
                album = current.album,
                sources = setOf(source),
                order = listOf(source),
                prioritizeSyllableSync = false,
                onSourceStarted = { scope.launch { started(asked, it) } },
                onSourceResult = { provider, result -> scope.launch { finished(asked, provider, result) } },
                onSourceCancelled = { scope.launch { cancelled(asked, it) } },
            )
        }
    }

    private fun started(asked: Long, source: LyricsSource) {
        if (asked != generation) return
        _providerStates.update { it + (source to LyricsProviderState.FETCHING) }
    }

    private fun finished(asked: Long, source: LyricsSource, result: LyricsRepository.Result?) {
        if (asked != generation) return
        if (result == null) {
            _providerStates.update { it + (source to LyricsProviderState.NOT_FOUND) }
            return
        }
        results[source] = result
        _providerStates.update { it + (source to LyricsProviderState.FOUND) }
        if (selected == source) show(result)
    }

    private fun cancelled(asked: Long, source: LyricsSource) {
        if (asked != generation) return
        _providerStates.update { states ->
            if (states[source] == LyricsProviderState.FETCHING) states + (source to LyricsProviderState.NOT_FETCHED) else states
        }
        // A tap may have picked a provider the race then dropped: ask it on its own.
        if (selected == source) request?.let { fetchOne(it, asked, source) }
    }

    private fun show(result: LyricsRepository.Result) {
        _lyrics.value = result.lines
        _source.value = result.source
        _checked.value = true
    }
}
