package com.music.bitchord.ios.automix

import com.music.bitchord.data.MonotonicClock
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.ios.IosNetwork
import com.music.bitchord.ios.IosPlayerHost
import com.music.bitchord.ios.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.UIKit.UIPasteboard

/**
 * Phase 2 of docs/designs/automix-loop-mix.md, on the phone: do Beat This! and open-unmix run on
 * iOS, what do they find, and what do they cost? It measures ten songs found by search exactly as
 * Automix measures a queued one (a low-bitrate copy, the DSP pass, then the models), without
 * storing anything, and reports each song's model results and both passes' times. The report goes
 * to the clipboard and the playback log.
 */
internal object IosAutomixModelCheck {
    private const val TAG = "ModelCheck"
    private const val SONGS = 10

    /** One song each, spread over tempo, genre and how much singing there is. */
    private val QUERIES = listOf(
        "Daft Punk One More Time",
        "Coldplay Viva la Vida",
        "Adele Rolling in the Deep",
        "Avicii Levels",
        "Arijit Singh Kesariya",
        "Billie Eilish bad guy",
        "Hans Zimmer Time",
        "The Weeknd Blinding Lights",
        "Eminem Lose Yourself",
        "Norah Jones Don't Know Why",
    )

    private val _progress = MutableStateFlow<String?>(null)

    /** What the settings row shows: "3/10", "Done: 9/10 with model beats". */
    val progress: StateFlow<String?> = _progress

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    fun run() {
        if (job?.isActive == true) return
        job = scope.launch {
            val report = StringBuilder()
            try {
                report.appendLine("BitChord Automix model check (B6 Phase 2)")
                report.appendLine("metered: ${IosNetwork.metered.value}")
                report.appendLine("analyzer: ${IosAnalyzerNative.loadError ?: "loaded"}")
                report.appendLine("models: ${IosModels.loadError ?: "loaded"}")
                _progress.value = tr("Finding songs…")
                val songs = pickSongs()
                report.appendLine("songs: ${songs.size}")
                var modelled = 0
                var withBeats = 0
                var withVocals = 0
                val ratios = ArrayList<Double>()
                songs.forEachIndexed { index, song ->
                    _progress.value = "${index + 1}/${songs.size}"
                    val started = MonotonicClock.nowMs()
                    val line = try {
                        val measured = IosAutomixAnalysis.measure(song)
                        val total = MonotonicClock.nowMs() - started
                        val analysis = measured.analysis
                        if (measured.models) modelled++
                        if (analysis.headBeats != null || analysis.tailBeats != null) withBeats++
                        if (analysis.vocalWindows.isNotEmpty()) withVocals++
                        val before = (total - measured.modelMs).coerceAtLeast(1)
                        if (measured.models) ratios += total.toDouble() / before
                        "total $total ms (fetch and DSP $before, models ${measured.modelMs}); ${measured.summary}"
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        "failed: ${e.message}"
                    }
                    report.appendLine("  ${song.videoId}  ${song.title.take(32)} — $line")
                }
                val median = ratios.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] }
                report.appendLine(
                    "modelled $modelled/${songs.size}, model beats $withBeats, vocal curves $withVocals; " +
                        "time with models vs without, median ${(median * 100).toInt() / 100.0}x",
                )
                val go = songs.isNotEmpty() && withBeats >= songs.size - 1 && withVocals >= songs.size - 1 && median in 0.01..2.0
                report.appendLine(if (go) "verdict: GO" else "verdict: CHECK")
                _progress.value = tr("Done: {0}/{1} with model beats", withBeats, songs.size)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                report.appendLine("check failed: ${e::class.simpleName}: ${e.message}")
                _progress.value = tr("Failed")
            }
            withContext(Dispatchers.Default) { IosModels.release() }
            val text = report.toString()
            TrackLog.i(TAG, text)
            UIPasteboard.generalPasteboard.string = text
            IosPlayerHost.showMessage(tr("Automix model check copied to the clipboard"))
        }
    }

    private suspend fun pickSongs(): List<Song> {
        val seen = LinkedHashMap<String, Song>()
        for (query in QUERIES) {
            YtMusicRepository.search(query, SearchFilter.SONGS).getOrNull()
                ?.filterIsInstance<SearchResult.Track>()?.map { it.song }
                ?.firstOrNull { it.videoId !in seen }
                ?.let { seen[it.videoId] = it }
            if (seen.size >= SONGS) break
        }
        return seen.values.toList()
    }
}
