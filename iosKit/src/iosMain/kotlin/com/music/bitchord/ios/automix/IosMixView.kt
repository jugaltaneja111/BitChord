@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.music.bitchord.ios.automix

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.MixDeck
import com.music.bitchord.data.settings.MixTransitionView
import com.music.bitchord.ios.ffmpeg.FfmpegMonoDecoder
import com.music.bitchord.data.MonotonicClock
import com.music.bitchord.playback.smart.MixWaveformBands
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.TransitionPlan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileManager
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * The Automix mix popup's data on iOS: the blend in flight laid out as a [MixTransitionView], and
 * how far through it the listener is. The phone's `CrossfadeController.publishMixView` and
 * `prefetchMixWaves`, for [com.music.bitchord.ios.IosPlayer]'s blends.
 *
 * The waveforms are decoded ahead, once a blend is [PREFETCH_SECONDS] away, from the copy of each
 * song on the phone (a download or the song cache), else a low-bitrate copy fetched as Automix's
 * analysis fetches one. The whole file is decoded at the analyzer's 11 kHz mono — about a second
 * for a song — and only the window around the blend is kept, in three bands. Below 5.5 kHz the
 * "highs" are 2.5–5.5 kHz, which is all a picture of them needs.
 */
internal object IosMixView {
    private const val TAG = "IosMixView"

    /** How far ahead of a blend its waveforms start decoding. */
    private const val PREFETCH_SECONDS = 30.0

    /** How much of each track either side of the overlap the popup shows, as a fraction of it. */
    private const val CONTEXT = 0.24

    /** Extra decoded either side of the popup's own window, for a plan that shifts before it starts. */
    private const val PAD_SECONDS = 3.0

    /** Points the popup's fader lines are sampled at. */
    private const val FADER_POINTS = 64

    private const val DECODE_RATE = 11_025
    private const val MAX_SAMPLES = 20 * 60 * DECODE_RATE

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _view = MutableStateFlow<MixTransitionView?>(null)
    val view: StateFlow<MixTransitionView?> = _view.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    /** A decoded waveform and the stretch of its track, in seconds, it covers. */
    private class DeckWave(val id: String, val from: Double, val to: Double, val bands: MixWaveformBands) {
        fun covers(id: String, start: Double, end: Double) = id == this.id && from <= start + 0.05 && to >= end - 0.05
    }

    private var outgoingWave: DeckWave? = null
    private var incomingWave: DeckWave? = null
    private var wavePair: String? = null
    private var lastAttempt: String? = null
    private var waveJob: Job? = null

    /** The ids of the blend [view] is showing. */
    private var showing: Pair<String, String>? = null

    /**
     * Called on every Automix tick while a blend is planned but not started: decodes both windows
     * once it is [PREFETCH_SECONDS] away, so the popup has them the moment the blend starts.
     */
    fun prefetch(out: Song, inn: Song, plan: TransitionPlan, positionSeconds: Double, outgoingRate: Double) {
        if (plan.transitionStart - positionSeconds > PREFETCH_SECONDS) return
        val g = Geometry.of(plan, plan.transitionStart, outgoingRate)
        val pad = g.outSpan * CONTEXT + PAD_SECONDS
        val outFrom = (g.outStart - pad).coerceAtLeast(0.0)
        val outTo = g.outEnd + pad
        val inFrom = (g.inStart - pad * g.ratio).coerceAtLeast(0.0)
        val inTo = g.inEnd + pad * g.ratio
        val pair = "${out.videoId}->${inn.videoId}"
        val outReady = outgoingWave?.covers(out.videoId, outFrom + PAD_SECONDS, outTo - PAD_SECONDS) == true
        val inReady = incomingWave?.covers(inn.videoId, inFrom + PAD_SECONDS, inTo - PAD_SECONDS) == true
        if (outReady && inReady) return
        if (waveJob?.isActive == true && wavePair == pair) return
        // Tried already for about these windows: a copy that couldn't be had isn't asked for every tick.
        val attempt = "$pair|${(outFrom / 10).toInt()}|${(inFrom / 10).toInt()}"
        if (attempt == lastAttempt) return
        lastAttempt = attempt
        waveJob?.cancel()
        wavePair = pair
        waveJob = scope.launch {
            if (!outReady) {
                outgoingWave = wave(out, outFrom, outTo)
                republish()
            }
            if (!inReady) {
                incomingWave = wave(inn, inFrom, inTo)
                republish()
            }
        }
    }

    /**
     * Lays the blend just started out for the popup: both tracks' cards, where the overlap sits on
     * each timeline, its beats and the faders the blend will move, with whichever waveforms are in.
     */
    fun begin(
        out: Song,
        inn: Song,
        plan: TransitionPlan,
        outAnalysis: TrackAnalysis,
        inAnalysis: TrackAnalysis,
        positionSeconds: Double,
        outgoingRate: Double,
        outDurationMs: Long,
        inDurationMs: Long,
    ) {
        val g = Geometry.of(plan, positionSeconds, outgoingRate)
        val beatSeconds = plan.beatSeconds
        val beats = if (beatSeconds > 0) (g.outSpan / beatSeconds).roundToInt() else 0
        val marks = if (beatSeconds > 0) {
            val anchor = outAnalysis.downbeats.lastOrNull { it <= g.outStart } ?: g.outStart
            val first = anchor + ceil((g.outStart - anchor) / beatSeconds) * beatSeconds
            generateSequence(first) { it + beatSeconds }
                .takeWhile { it <= g.outEnd + 1e-6 }
                .map { ((it - g.outStart) / g.outSpan).toFloat() }
                .toList().toFloatArray()
        } else {
            FloatArray(0)
        }
        // A ride of its own: [IosAutomixRide.at] carries its filters from call to call, so sampling
        // the engine's would move it to the end of the blend before it began.
        val ride = IosAutomixRide(plan)
        val moves = List(FADER_POINTS) { ride.at(it / (FADER_POINTS - 1f)) }
        fun deck(song: Song, analysis: TrackAnalysis, durationMs: Long, from: Double, to: Double) = MixDeck(
            title = song.title,
            artist = song.artist,
            artworkUrl = song.thumbnailUrl,
            bpm = analysis.bpm,
            key = analysis.key,
            durationMs = durationMs.takeIf { it > 0L } ?: (analysis.duration * 1000).toLong(),
            waveStart = from,
            waveEnd = to,
        )
        _progress.value = 0f
        showing = out.videoId to inn.videoId
        _view.value = MixTransitionView(
            outgoing = deck(out, outAnalysis, outDurationMs, g.outStart - g.context, g.outEnd + g.context),
            incoming = deck(inn, inAnalysis, inDurationMs, g.inStart - g.context * g.ratio, g.inEnd + g.context * g.ratio),
            outStart = g.outStart,
            outEnd = g.outEnd,
            inStart = g.inStart,
            inEnd = g.inEnd,
            beats = beats,
            beatMarks = marks,
            fadeOut = FloatArray(FADER_POINTS) { moves[it].outgoingGain.coerceIn(0f, 1f) },
            fadeIn = FloatArray(FADER_POINTS) { moves[it].incomingGain.coerceIn(0f, 1f) },
            bassSwapAt = ride.bassSwapMark,
        )
        republish()
        // A blend that started before its prefetch (a skip, a short song) decodes now.
        prefetch(out, inn, plan.copy(transitionStart = positionSeconds), positionSeconds, outgoingRate)
    }

    /** The blend's progress, 0..1, each fade step. */
    fun progress(value: Float) {
        if (_view.value != null) _progress.value = value.coerceIn(0f, 1f)
    }

    /** The blend is over, or cut short. */
    fun end() {
        showing = null
        _view.value = null
        _progress.value = 0f
    }

    /** A new queue, or Automix turned off: nothing kept for a blend that won't happen. */
    fun reset() {
        waveJob?.cancel()
        waveJob = null
        wavePair = null
        lastAttempt = null
        outgoingWave = null
        incomingWave = null
        end()
    }

    private fun republish() {
        val view = _view.value ?: return
        val ids = showing ?: return
        fun MixDeck.with(wave: DeckWave?, id: String) =
            if (wave != null && wave.id == id) withWave(wave.bands.low, wave.bands.mid, wave.bands.high, wave.from, wave.to) else this
        _view.value = view.withDecks(view.outgoing.with(outgoingWave, ids.first), view.incoming.with(incomingWave, ids.second))
    }

    /** [song]'s three bands over [from]..[to] seconds; null when no copy could be had or decoded. */
    private suspend fun wave(song: Song, from: Double, to: Double): DeckWave? {
        val started = MonotonicClock.nowMs()
        var fetched: String? = null
        return try {
            val path = IosAutomixAnalysis.localCopy(song)?.first
                ?: IosAutomixAnalysis.fetch(song).also { fetched = it }
            val decoder = FfmpegMonoDecoder(path, DECODE_RATE, MAX_SAMPLES)
            val bands = withContext(Dispatchers.Default) {
                val decoded = decoder.decode(to)
                val bands = MixWaveformBands(from, to, MixWaveformBands.binsFor(to - from))
                val first = (bands.warmFrom.coerceAtLeast(0.0) * decoded.rate).toInt().coerceIn(0, decoded.count)
                val last = (to * decoded.rate).toInt().coerceIn(first, decoded.count)
                bands.feed(decoded.samples, first, last - first, first.toDouble() / decoded.rate, decoded.rate)
                bands.finish()
            }
            TrackLog.d(TAG, "mix waveform ${from.toInt()}–${to.toInt()}s in ${MonotonicClock.nowMs() - started} ms", about = song.videoId)
            DeckWave(song.videoId, from, to, bands)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TrackLog.w(TAG, "mix waveform failed: ${e.message}", about = song.videoId)
            null
        } finally {
            fetched?.let { NSFileManager.defaultManager.removeItemAtPath(it, null) }
        }
    }

    /**
     * The overlap on each track's own timeline. The blend runs [TransitionPlan.fadeSeconds] of
     * wall time; the outgoing track covers that at the speed it was ramped to, the incoming one at
     * its blend speed.
     */
    private class Geometry(val outStart: Double, val outEnd: Double, val inStart: Double, val inEnd: Double, val ratio: Double) {
        val outSpan get() = (outEnd - outStart).coerceAtLeast(0.5)
        val context get() = outSpan * CONTEXT

        companion object {
            fun of(plan: TransitionPlan, startSeconds: Double, outgoingRate: Double): Geometry {
                val wall = plan.fadeSeconds.coerceAtLeast(0.5)
                val outRate = outgoingRate.takeIf { it > 0 } ?: 1.0
                val inRate = plan.incomingPlaybackRate.takeIf { it > 0 } ?: 1.0
                val inStart = plan.incomingCueTime.coerceAtLeast(0.0)
                return Geometry(startSeconds, startSeconds + wall * outRate, inStart, inStart + wall * inRate, inRate / outRate)
            }
        }
    }
}
