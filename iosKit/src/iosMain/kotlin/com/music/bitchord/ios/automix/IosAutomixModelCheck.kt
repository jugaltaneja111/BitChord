package com.music.bitchord.ios.automix

import com.music.bitchord.data.MonotonicClock
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.ios.IosNetwork
import com.music.bitchord.ios.IosPlayerHost
import com.music.bitchord.ios.IosSettings
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
 * Phases 2 and 4 of docs/designs/automix-loop-mix.md, on the phone: do Beat This! and open-unmix run on
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
                report.appendLine("BitChord Automix model check (B6 Phase 4)")
                report.appendLine("metered: ${IosNetwork.metered.value}")
                report.appendLine("analyzer: ${IosAnalyzerNative.loadError ?: "loaded"}")
                report.appendLine("models: ${IosModels.loadError ?: "loaded"}")
                report.appendLine("loop mix renderer: ${IosLoopMixNative.loadError ?: withContext(Dispatchers.Default) { rendererCheck() }}")
                report.appendLine("performance: ${IosSettings.automixPerformanceMode.name} (${IosSettings.automixPerformanceMode.inferenceThreads} threads)")
                _progress.value = tr("Finding songs…")
                val songs = pickSongs()
                report.appendLine("songs: ${songs.size}")
                var modelled = 0
                var withBeats = 0
                var withVocals = 0
                val ratios = ArrayList<Double>()
                val modelTimes = ArrayList<Long>()
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
                        if (measured.models) {
                            ratios += total.toDouble() / before
                            modelTimes += measured.modelMs
                        }
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
                        "time with models vs without, median ${(median * 100).toInt() / 100.0}x; " +
                        "model time per song, median ${modelTimes.sorted().let { if (it.isEmpty()) 0 else it[it.size / 2] }} ms",
                )
                // Analysis runs ahead of playback, so what matters is that the models finish well
                // inside a song: under a minute each, not a ratio to the DSP pass.
                val modelMedian = modelTimes.sorted().let { if (it.isEmpty()) Long.MAX_VALUE else it[it.size / 2] }
                val go = songs.isNotEmpty() && withBeats >= songs.size - 1 && withVocals >= songs.size - 1 && modelMedian <= 60_000
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

    /**
     * The renderer on its own: a synthetic two-deck mix with a tempo ramp and a loop, rendered
     * offline the way the feeder renders it, and how long that took against its length. The pass
     * mark for the real thing is under 15% of one core.
     */
    private fun rendererCheck(): String {
        val rate = 44_100
        val renderer = IosLoopMixRenderer(rate)
        if (!renderer.isValid) return "would not create"
        try {
            val n = rate * 40
            val left = FloatArray(n) { i ->
                val t = i.toDouble() / rate
                (0.3 * kotlin.math.sin(2 * kotlin.math.PI * 220 * t) * (if (i % (rate / 2) < 2000) 1.0 else 0.3)).toFloat()
            }
            val beats = 32
            val frames = DoubleArray(beats + 1)
            val positions = DoubleArray(beats + 1)
            var t = 0.0
            var position = rate * 1.0
            for (b in 0..beats) {
                frames[b] = t * rate
                positions[b] = position
                t += 60.0 / (120 + 5.0 * b / beats)
                position += 0.5 * rate
            }
            renderer.setDeck(
                IosLoopMixRenderer.OUT,
                IosLoopMixRenderer.DeckSetup(left, left, 0.0, rate * 1L, rate * 3L, 1e18, frames, positions, false, t + 0.05, 0.4, 1f),
            )
            renderer.setDeck(
                IosLoopMixRenderer.IN,
                IosLoopMixRenderer.DeckSetup(left, left, 0.0, rate * 1L, rate * 3L, positions[16], frames, positions, true, t + 2, 0.4, 1f),
            )
            renderer.setEnvelope(IosLoopMixRenderer.IN, IosLoopMixRenderer.KIND_GAIN, doubleArrayOf(0.0, 5.0, 10.0), floatArrayOf(0f, 0.5f, 1f))
            renderer.prepare()
            val first = renderer.firstFrame()
            val total = ((t + 1) * rate).toLong() - first
            val block = 4096
            val l = FloatArray(block)
            val r = FloatArray(block)
            var peak = 0f
            val started = MonotonicClock.nowMs()
            var frame = first
            while (frame < first + total) {
                renderer.render(frame, l, r, block)
                for (i in 0 until block) peak = maxOf(peak, kotlin.math.abs(l[i]))
                frame += block
            }
            val ms = MonotonicClock.nowMs() - started
            val seconds = total.toDouble() / rate
            val percent = ms / (seconds * 10)
            return "loaded; ${(seconds * 10).toInt() / 10.0} s test mix in $ms ms " +
                "(${(percent * 10).toInt() / 10.0}% of real time), peak ${(peak * 100).toInt() / 100.0}"
        } finally {
            renderer.close()
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
