/*
 * Ported from Orchard (https://github.com/SFG5453/Orchard).
 *
 * Copyright (C) 2026 SFG545 (original Orchard implementation)
 * Copyright (C) 2026 Kushagra Singh (BitChord adaptation)
 *
 * Orchard's original source is licensed under the GNU Affero General Public
 * License, version 3 or later. Per AGPLv3 section 13, this file is combined
 * here into BitChord -- a work licensed under the GNU General Public
 * License, version 3 or later -- and remains itself governed by the AGPLv3
 * as part of that combination.
 */

package com.music.bitchord.playback.smart

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The Beat This! and open-unmix arithmetic that doesn't touch a model runtime, for the platforms
 * that run the models their own way: the JVM's BeatTracker and VocalTracker (jvmSharedMain) on
 * ai.onnxruntime, and iOS on ONNX Runtime's C API. Upstream's, unchanged: peak picking, tempo from
 * beats, grid confidence, the chunking of the beat model's input, and the vocal band curve.
 */
object BeatModel {
    /** The model's input width in frames, and the margin discarded from each chunk's edges. */
    const val CHUNK_FRAMES = 1500
    const val BORDER_FRAMES = 6

    /** The front end's frames per second: 441 samples at 22,050 Hz. */
    const val FRAME_RATE = 50.0

    /** Window length that costs exactly one inference. */
    const val WINDOW_SECONDS = (CHUNK_FRAMES - 2 * BORDER_FRAMES) / FRAME_RATE

    /** A frame is a beat when it is the maximum of a seven-frame window and its logit positive. */
    private const val PEAK_WINDOW = 7
    const val MIN_BEATS = 8

    /** Plausible musical tempo, used only to reject a grid the model clearly did not find. */
    private const val MIN_TEMPO = 40.0
    private const val MAX_TEMPO = 220.0

    /** A tracked grid on the analysed audio's own timeline, in seconds. */
    data class Grid(
        val beats: List<Double>,
        val downbeats: List<Double>,
        val bpm: Double,
        val beatInterval: Double,
        val firstBeat: Double,
        val beatConfidence: Double,
    )

    /** One chunk of the input: frames [start] until [start] + [length]; logits kept over [keepFrom] until [keepTo]. */
    data class Chunk(val start: Int, val length: Int, val keepFrom: Int, val keepTo: Int)

    /**
     * How a spectrogram of [frames] is cut into model calls: 1500-frame chunks, [BORDER_FRAMES]
     * discarded from each inner edge, the first and last keeping their outer border.
     */
    fun chunks(frames: Int): List<Chunk> {
        val out = ArrayList<Chunk>()
        val stride = CHUNK_FRAMES - 2 * BORDER_FRAMES
        var start = 0
        while (start < frames) {
            val length = min(CHUNK_FRAMES, frames - start)
            // A chunk shorter than the border padding carries no usable centre.
            if (length <= 2 * BORDER_FRAMES && start > 0) break
            val keepFrom = if (start == 0) 0 else BORDER_FRAMES
            val keepTo = if (start + length >= frames) length else length - BORDER_FRAMES
            out += Chunk(start, length, keepFrom, keepTo)
            if (start + length >= frames) break
            start += stride
        }
        return out
    }

    /**
     * The grid in the two heads' logits, with [offsetSeconds] added to every time; null when the
     * model found too few beats or no plausible tempo.
     */
    fun grid(beatLogits: FloatArray, downbeatLogits: FloatArray, offsetSeconds: Double, fps: Double = FRAME_RATE): Grid? {
        val beatFrames = pickPeaks(beatLogits)
        val beats = beatFrames.map { it / fps + offsetSeconds }
        if (beats.size < MIN_BEATS) return null
        val bpm = tempoFromBeats(beats)
        if (bpm <= 0) return null
        // Every downbeat is a beat: the two heads' peaks can land a frame apart, so each downbeat
        // is snapped onto the nearest beat, keeping the bar grid a strict subset of the beat grid.
        val downbeats = pickPeaks(downbeatLogits)
            .map { it / fps + offsetSeconds }
            .map { time -> beats.minByOrNull { abs(it - time) } ?: beats.first() }
            .distinct()
            .sorted()
        return Grid(
            beats = beats,
            downbeats = downbeats,
            bpm = bpm,
            beatInterval = 60 / bpm,
            firstBeat = beats.first(),
            beatConfidence = gridConfidence(
                beats,
                beatFrames.map { frame -> beatLogits.getOrElse(frame.roundToInt()) { 0f }.toDouble() },
            ),
        )
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    /**
     * Frame indices that are local maxima over [PEAK_WINDOW] and positive, with runs of adjacent
     * peaks collapsed to their mean, then refined to sub-frame resolution with a parabola through
     * the peak and its neighbours.
     */
    fun pickPeaks(logits: FloatArray): List<Double> {
        val half = PEAK_WINDOW / 2
        val peaks = ArrayList<Int>()
        for (index in logits.indices) {
            if (logits[index] <= 0f) continue
            var isMaximum = true
            for (offset in -half..half) {
                val neighbour = index + offset
                if (neighbour < 0 || neighbour >= logits.size) continue
                if (logits[neighbour] > logits[index]) {
                    isMaximum = false
                    break
                }
            }
            if (isMaximum) peaks += index
        }
        val deduped = ArrayList<Int>()
        var index = 0
        while (index < peaks.size) {
            var mean = peaks[index].toDouble()
            var count = 1
            while (index + 1 < peaks.size && peaks[index + 1] - mean <= 1) {
                index += 1
                count += 1
                mean += (peaks[index] - mean) / count
            }
            deduped += mean.roundToInt()
            index += 1
        }
        return deduped.map { frame ->
            if (frame <= 0 || frame + 1 >= logits.size) return@map frame.toDouble()
            val left = logits[frame - 1].toDouble()
            val centre = logits[frame].toDouble()
            val right = logits[frame + 1].toDouble()
            val denominator = left - 2 * centre + right
            if (abs(denominator) <= 1e-9) return@map frame.toDouble()
            frame + (0.5 * (left - right) / denominator).coerceIn(-0.5, 0.5)
        }
    }

    /** Median inter-beat interval as a tempo, or 0 when it is not a plausible one. */
    fun tempoFromBeats(beats: List<Double>): Double {
        if (beats.size < MIN_BEATS) return 0.0
        val gaps = beats.zipWithNext { left, right -> right - left }
        val rough = median(gaps)
        if (rough <= 0) return 0.0
        val kept = gaps.filter { abs(it - rough) <= rough * 0.2 }
        val interval = median(if (kept.size >= 4) kept else gaps)
        if (interval <= 0) return 0.0
        val bpm = 60 / interval
        return if (bpm in MIN_TEMPO..MAX_TEMPO) bpm else 0.0
    }

    /** How far the grid can be trusted, 0..0.95: regularity of the spacing and decisiveness of the peaks. */
    fun gridConfidence(beats: List<Double>, peakLogits: List<Double>): Double {
        if (beats.size < MIN_BEATS) return 0.0
        val gaps = beats.zipWithNext { left, right -> right - left }
        val interval = median(gaps)
        if (interval <= 0) return 0.0
        val regular = gaps.count { abs(it - interval) <= interval * 0.1 }.toDouble() / gaps.size
        val strength = 1 / (1 + exp(-(median(peakLogits) - 0.5)))
        return max(0.0, min(0.95, 0.35 + 0.4 * regular + 0.25 * strength))
    }
}

/** open-unmix's input shape and the reduction of its output to one vocal value per frame. */
object VocalModel {
    const val CHANNELS = 2
    const val BINS = 2049
    const val FFT_SIZE = 4096
    const val HOP = 1024
    const val SAMPLE_RATE = 44_100.0
    const val FRAME_RATE = SAMPLE_RATE / HOP

    /** The model's fixed input width, ~22.8 s. */
    const val FIXED_FRAMES = 960

    /** Seconds one call covers, less two frames for the ±1 sample a rate conversion can land on. */
    const val WINDOW_SECONDS = (FIXED_FRAMES - 2) * HOP / SAMPLE_RATE

    /** The band a vocal actually occupies. */
    private const val LOW_HZ = 200.0
    private const val HIGH_HZ = 4000.0

    /**
     * The front end's `[2, bins, frames]` spectrogram re-strided to the model's `[2, bins,
     * FIXED_FRAMES]`, each bin's tail zero.
     */
    fun fixedFrames(values: FloatArray, frames: Int, bins: Int = BINS): FloatArray {
        if (frames == FIXED_FRAMES) return values
        val out = FloatArray(CHANNELS * bins * FIXED_FRAMES)
        for (row in 0 until CHANNELS * bins) {
            values.copyInto(out, row * FIXED_FRAMES, row * frames, row * frames + frames)
        }
        return out
    }

    /**
     * `target / mix`, clamped to 0..1, averaged over 200 Hz–4 kHz and both channels, for each of
     * the [usableFrames] carrying real audio. Both arrays are in the model's fixed layout.
     */
    fun bandCurve(mix: FloatArray, target: FloatArray, usableFrames: Int, bins: Int = BINS): FloatArray {
        val lowBin = floor(LOW_HZ * FFT_SIZE / SAMPLE_RATE).toInt().coerceAtLeast(0)
        val highBin = ceil(HIGH_HZ * FFT_SIZE / SAMPLE_RATE).toInt().coerceAtMost(bins - 1)
        if (highBin <= lowBin || usableFrames <= 0) return FloatArray(0)
        val curve = FloatArray(usableFrames)
        for (frame in 0 until usableFrames) {
            var sum = 0.0
            var count = 0
            for (channel in 0 until CHANNELS) {
                for (bin in lowBin..highBin) {
                    val index = (channel * bins + bin) * FIXED_FRAMES + frame
                    if (index >= mix.size || index >= target.size) continue
                    val mixValue = mix[index]
                    if (mixValue <= 1e-6f) continue
                    sum += (target[index] / mixValue).coerceIn(0f, 1f)
                    count += 1
                }
            }
            curve[frame] = if (count > 0) (sum / count).toFloat() else 0f
        }
        return curve
    }
}

/**
 * Pass 2 of Android's TrackAnalyzer.analyze, minus the decoding: where the models run (two windows
 * over the opening, three up to the end), how the windows' beats are stitched, and how their
 * results go into a [TrackAnalysis] the DSP pass made. A platform decodes each window, runs the
 * models over it, and hands the [Region]s here.
 */
object ModelRegions {
    const val HEAD_WINDOWS = 2
    const val TAIL_WINDOWS = 3
    const val WINDOW_OVERLAP_SECONDS = 1.0

    /** Where nothing was measured; below the policy's vocal threshold, so it trips nothing. */
    const val NEUTRAL_VOCAL = 0.2

    /** What one decoded window gave; [start] is where the decode really began. */
    class Region(
        val start: Double,
        val seconds: Double,
        val grid: BeatModel.Grid?,
        val vocalValues: FloatArray?,
        val levelWindow: CurveWindow?,
    ) {
        val vocalWindow: CurveWindow? get() = vocalValues?.let { CurveWindow(start, VocalModel.FRAME_RATE, it) }
    }

    class Spans(val headEnd: Double, val tailStart: Double, val windows: List<Pair<Double, Double>>)

    /** The windows for a track of [duration] seconds, each [span] long (one call of each model). */
    fun spans(duration: Double, span: Double): Spans {
        val headEnd = min(duration, HEAD_WINDOWS * span - (HEAD_WINDOWS - 1) * WINDOW_OVERLAP_SECONDS)
        val tailStart = max(0.0, duration - (TAIL_WINDOWS * span - (TAIL_WINDOWS - 1) * WINDOW_OVERLAP_SECONDS))
        return Spans(headEnd, tailStart, windows(headEnd, tailStart, duration, span))
    }

    /**
     * As few windows as cover the head and the tail with at least the overlap between neighbours,
     * spread evenly; a short track, where the two spans meet, is covered once.
     */
    fun windows(headEnd: Double, tailStart: Double, duration: Double, span: Double): List<Pair<Double, Double>> {
        val spans = if (tailStart <= headEnd) listOf(0.0 to duration) else listOf(0.0 to headEnd, tailStart to duration)
        val windows = ArrayList<Pair<Double, Double>>()
        for ((from, to) in spans) {
            val length = to - from
            if (length < 1.0) continue
            if (length <= span) {
                windows += from to to
                continue
            }
            val count = ceil((length - WINDOW_OVERLAP_SECONDS) / (span - WINDOW_OVERLAP_SECONDS) - 1e-6).toInt()
            val stride = (length - span) / (count - 1)
            for (index in 0 until count) {
                val start = from + index * stride
                windows += start to min(to, start + span)
            }
        }
        return windows
    }

    class Beats(val beats: List<Double>, val downbeats: List<Double>, val confidence: Double)

    /** One beat list across the windows: at each overlap, the earlier window's beats before its midpoint, the later's after. */
    fun seamBeats(regions: List<Region>): Beats {
        val graded = regions.filter { it.grid != null }.sortedBy { it.start }
        if (graded.isEmpty()) return Beats(emptyList(), emptyList(), 0.0)
        val beats = ArrayList<Double>()
        val downbeats = ArrayList<Double>()
        var weighted = 0.0
        var count = 0
        for ((index, region) in graded.withIndex()) {
            val grid = region.grid!!
            val previous = graded.getOrNull(index - 1)
            val next = graded.getOrNull(index + 1)
            val from = previous?.let { (it.start + it.seconds + region.start) / 2 }
                ?.takeIf { it > region.start } ?: Double.NEGATIVE_INFINITY
            val until = next?.let { (region.start + region.seconds + it.start) / 2 }
                ?.takeIf { it < region.start + region.seconds } ?: Double.POSITIVE_INFINITY
            val kept = grid.beats.filter { it >= from && it < until }
            beats += kept
            downbeats += grid.downbeats.filter { it >= from && it < until }
            weighted += grid.beatConfidence * kept.size
            count += kept.size
        }
        return Beats(beats.sorted(), downbeats.sorted(), if (count > 0) weighted / count else 0.0)
    }

    /** The model's grid over [from]..[to], or null where it found too few beats there. */
    fun beatSide(all: Beats, from: Double, to: Double): BeatSide? {
        val beats = all.beats.filter { it >= from && it <= to }
        val bpm = BeatModel.tempoFromBeats(beats)
        if (bpm <= 0) return null
        return BeatSide(beats, all.downbeats.filter { it >= from && it <= to }, bpm, all.confidence)
    }

    /**
     * RMS of the stereo mix in frames of 1 / [LEVEL_FRAME_RATE] s on the [levelFromRms] scale,
     * reading every fourth sample.
     */
    fun levelCurve(left: FloatArray, right: FloatArray, count: Int, rate: Double, start: Double): CurveWindow? {
        val frame = (rate / LEVEL_FRAME_RATE).toInt()
        if (frame <= 0) return null
        val frames = min(count, min(left.size, right.size)) / frame
        if (frames <= 0) return null
        val values = FloatArray(frames)
        for (index in 0 until frames) {
            var sum = 0.0
            var n = 0
            var sample = index * frame
            val end = sample + frame
            while (sample < end) {
                val mixed = (left[sample] + right[sample]) * 0.5f
                sum += mixed * mixed
                n++
                sample += 4
            }
            values[index] = levelFromRms(sqrt(sum / n)).toFloat()
        }
        return CurveWindow(start, LEVEL_FRAME_RATE, values)
    }

    /** A value for every point on [energyCurve], filled only over [region]'s vocal window. */
    private fun vocalMask(energyCurve: List<EnergySample>, region: Region): DoubleArray? {
        val values = region.vocalValues ?: return null
        if (energyCurve.isEmpty()) return null
        val mask = DoubleArray(energyCurve.size) { NEUTRAL_VOCAL }
        for (index in energyCurve.indices) {
            val frame = ((energyCurve[index].time - region.start) * VocalModel.FRAME_RATE).toInt()
            if (frame in values.indices) mask[index] = values[frame].toDouble()
        }
        return mask
    }

    /** The masks overlaid onto one full-length curve, or null when none ran. */
    private fun mergeMasks(size: Int, masks: List<DoubleArray>): List<Double>? {
        if (size <= 0 || masks.isEmpty()) return null
        val merged = DoubleArray(size) { NEUTRAL_VOCAL }
        for (source in masks) {
            for (index in merged.indices) {
                if (index < source.size && source[index] != NEUTRAL_VOCAL) merged[index] = source[index]
            }
        }
        return merged.toList()
    }

    /**
     * [dsp] with the models' evidence in it, as Android's analyze assembles it: the model grid in
     * place of the DSP one where the model found one (the tail's first, as the side a track is
     * mixed out on), the model's vocal mask where it ran and the DSP's elsewhere, and the loop
     * mix's per-side beats and per-window curves.
     */
    fun assemble(dsp: TrackAnalysis, spans: Spans, regions: List<Region>): TrackAnalysis {
        val beats = seamBeats(regions)
        val headSide = beatSide(beats, 0.0, spans.headEnd)
        val tailSide = beatSide(beats, spans.tailStart, dsp.duration)
        val leading = tailSide ?: headSide
        val masks = regions.mapNotNull { vocalMask(dsp.energyCurve, it) }
        return dsp.copy(
            bpm = leading?.bpm ?: dsp.bpm,
            beatInterval = leading?.let { 60 / it.bpm } ?: dsp.beatInterval,
            beatConfidence = leading?.confidence ?: dsp.beatConfidence,
            downbeats = (headSide?.downbeats.orEmpty() + tailSide?.downbeats.orEmpty()).ifEmpty { dsp.downbeats }.sorted(),
            firstBeat = headSide?.beats?.firstOrNull() ?: dsp.firstBeat,
            vocalActivityMask = mergeMasks(dsp.energyCurve.size, masks) ?: dsp.vocalActivityMask,
            headBeats = headSide,
            tailBeats = tailSide,
            vocalWindows = regions.mapNotNull { it.vocalWindow },
            levelWindows = regions.mapNotNull { it.levelWindow },
        )
    }
}
