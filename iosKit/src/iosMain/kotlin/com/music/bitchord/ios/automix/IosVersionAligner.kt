@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.music.bitchord.ios.automix

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import com.music.bitchord.ios.ffmpeg.FfmpegMonoDecoder
import com.music.bitchord.data.MonotonicClock
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import platform.Foundation.NSFileManager
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Smart audio alignment, the phone's `VersionAudioAligner`: how far one cut of a song (a music
 * video with its intro skit, say) runs ahead of another (the catalogue audio release), so a switch
 * between them lands on the same moment of the music rather than the same second of the clock.
 *
 * Both openings — the first 45 s, from the copy on the phone or fetched at the lowest rung — are
 * decoded to mono, reduced to 10 ms energy envelopes and cross-correlated over ±40 s; the phone's
 * numbers throughout, so the two apps agree on a pair. Main thread, the decodes on the analysis
 * thread.
 */
internal object IosVersionAligner {
    private const val TAG = "IosAlign"
    private const val HOP_MS = 10.0
    private const val WIN_MS = 40.0
    private const val MAX_LAG_SECONDS = 40.0
    private const val MIN_CONFIDENCE = 0.40
    private const val HEAD_SECONDS = 45
    private const val RATE = 11_025

    /** About 50 s of the lowest rung (Opus near 64 kbps), headers included. */
    private const val HEAD_BYTES = 640L * 1024

    /** How long a switch waits on the measurement before going ahead with no shift. */
    private const val MEASURE_TIMEOUT_MS = 20_000L

    private val scope = MainScope()
    private val files = NSFileManager.defaultManager

    /** (from, to) → how far to's music runs ahead of from's, in ms. */
    private val offsets = HashMap<Pair<String, String>, Long>()

    /** Measurements under way, shared by everyone asking for the same pair. */
    private val running = HashMap<Pair<String, String>, Deferred<Long?>>()

    fun cachedOffsetMs(fromId: String, toId: String): Long? =
        if (fromId == toId) 0L else offsets[fromId to toId]

    /**
     * Where [to] should start to match [from]: to's position = from's + the result. Null when it
     * couldn't be measured — never folded into 0, which is a real answer (they open together).
     */
    suspend fun offsetMs(from: Song, to: Song): Long? {
        cachedOffsetMs(from.videoId, to.videoId)?.let { return it }
        val key = from.videoId to to.videoId
        val deferred = running[key] ?: scope.async {
            try {
                withTimeoutOrNull(MEASURE_TIMEOUT_MS) { measure(from, to) }
            } finally {
                running.remove(key)
            }
        }.also { running[key] = it }
        return deferred.await()
    }

    private suspend fun measure(from: Song, to: Song): Long? {
        val started = MonotonicClock.nowMs()
        val heads = try {
            coroutineScope {
                val a = async { envelopeOf(from) }
                val b = async { envelopeOf(to) }
                a.await() to b.await()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            TrackLog.w(TAG, "alignment of '${from.title}': ${e.message}", about = from.videoId)
            return null
        }
        val (source, target) = heads
        if (source.isEmpty() || target.isEmpty()) return null
        val correlation = crossCorrelate(source, target)
        val tookMs = MonotonicClock.nowMs() - started
        if (correlation.confidence < MIN_CONFIDENCE) {
            TrackLog.w(
                TAG,
                "alignment of '${from.title}': confidence ${correlation.confidence.twoDecimals()} below $MIN_CONFIDENCE, no shift ($tookMs ms)",
                about = from.videoId,
            )
            return null
        }
        // A peak on the edge of the search means the true lag lies past what the openings reach.
        if (correlation.lagFrames == correlation.floor || correlation.lagFrames == correlation.ceiling) {
            TrackLog.w(TAG, "alignment of '${from.title}': peak at the edge of the search, not trusted", about = from.videoId)
            return null
        }
        val offset = (correlation.lagFrames * HOP_MS).toLong()
        offsets[from.videoId to to.videoId] = offset
        offsets[to.videoId to from.videoId] = -offset
        TrackLog.i(
            TAG,
            "alignment of '${from.title}' → ${to.videoId}: ${offset} ms (confidence ${correlation.confidence.twoDecimals()}, $tookMs ms)",
            about = from.videoId,
        )
        return offset
    }

    /** [song]'s opening as an energy envelope: decoded from the phone's copy, else a fetched head. */
    private suspend fun envelopeOf(song: Song): FloatArray {
        val local = IosAutomixAnalysis.localCopy(song)?.first
        val path = local ?: IosAutomixAnalysis.fetch(song, HEAD_BYTES)
        try {
            val decoder = FfmpegMonoDecoder(path, RATE, HEAD_SECONDS * RATE, partial = local == null)
            return IosAutomixAnalysis.onAnalysisThread(onCancel = decoder::cancel) {
                val decoded = decoder.decode(HEAD_SECONDS.toDouble())
                envelope(decoded.samples, decoded.count)
            }
        } finally {
            if (local == null) files.removeItemAtPath(path, null)
        }
    }

    private fun envelope(samples: FloatArray, count: Int): FloatArray {
        val hop = max(1, (RATE * HOP_MS / 1000.0).toInt())
        val window = max(hop, (RATE * WIN_MS / 1000.0).toInt())
        val frames = max(0, (count - window) / hop)
        val env = FloatArray(frames)
        for (i in 0 until frames) {
            val start = i * hop
            val end = min(count, start + window)
            var sumSq = 0.0
            for (j in start until end) {
                val s = samples[j]
                sumSq += s * s
            }
            env[i] = sqrt(sumSq / (end - start)).toFloat()
        }
        return env
    }

    private class Correlation(val lagFrames: Int, val confidence: Double, val floor: Int, val ceiling: Int)

    /**
     * Normalised cross-correlation of the two envelopes over every lag with three seconds' overlap;
     * a positive lag means [target]'s music comes later. Per-lag sums from prefix sums, cross terms
     * from one FFT pair, as the phone has it.
     */
    private fun crossCorrelate(source: FloatArray, target: FloatArray): Correlation {
        val maxLagFrames = (MAX_LAG_SECONDS * 1000.0 / HOP_MS).toInt()
        val minOverlap = (3.0 * 1000.0 / HOP_MS).toInt()
        val minLag = max(-maxLagFrames, -(source.size - minOverlap))
        val maxLag = min(maxLagFrames, target.size - minOverlap)
        if (minLag > maxLag) return Correlation(0, 0.0, 0, 0)
        val cross = crossTerms(source, target)
        val srcSum = prefix(source, squared = false)
        val srcSq = prefix(source, squared = true)
        val tgtSum = prefix(target, squared = false)
        val tgtSq = prefix(target, squared = true)
        var bestLag = 0
        var best = -1.0
        for (lag in minLag..maxLag) {
            val s0 = max(0, -lag)
            val t0 = max(0, lag)
            val n = min(source.size - s0, target.size - t0)
            if (n < minOverlap) continue
            val sumS = srcSum[s0 + n] - srcSum[s0]
            val sumT = tgtSum[t0 + n] - tgtSum[t0]
            val num = cross[lag + source.size - 1] - sumS * sumT / n
            val denS = srcSq[s0 + n] - srcSq[s0] - sumS * sumS / n
            val denT = tgtSq[t0 + n] - tgtSq[t0] - sumT * sumT / n
            val denom = sqrt(denS * denT)
            if (denom > 1e-6) {
                val r = num / denom
                if (r > best) {
                    best = r
                    bestLag = lag
                }
            }
        }
        return Correlation(bestLag, best, minLag, maxLag)
    }

    private fun prefix(values: FloatArray, squared: Boolean): DoubleArray {
        val out = DoubleArray(values.size + 1)
        for (i in values.indices) {
            val v = values[i].toDouble()
            out[i + 1] = out[i] + if (squared) v * v else v
        }
        return out
    }

    /** Σ source[i]·target[i+lag] for every lag, at `[lag + source.size - 1]`. */
    private fun crossTerms(source: FloatArray, target: FloatArray): DoubleArray {
        val n = source.size
        val m = target.size
        var size = 1
        while (size < n + m) size = size shl 1
        val aRe = DoubleArray(size)
        val aIm = DoubleArray(size)
        for (i in 0 until n) aRe[i] = source[i].toDouble()
        val bRe = DoubleArray(size)
        val bIm = DoubleArray(size)
        for (i in 0 until m) bRe[i] = target[i].toDouble()
        fft(aRe, aIm)
        fft(bRe, bIm)
        // conj(A)·B: the transform of the correlation, which keeps the lag's sign as written.
        for (i in 0 until size) {
            val a = aRe[i]
            val b = -aIm[i]
            val c = bRe[i]
            val d = bIm[i]
            aRe[i] = a * c - b * d
            aIm[i] = a * d + b * c
        }
        fft(aRe, aIm, inverse = true)
        val out = DoubleArray(n + m - 1)
        for (lag in -(n - 1) until m) out[lag + n - 1] = aRe[((lag % size) + size) % size]
        return out
    }

    /** In-place radix-2 FFT; the length must be a power of two. */
    private fun fft(re: DoubleArray, im: DoubleArray, inverse: Boolean = false) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val angle = 2.0 * PI / len * (if (inverse) 1.0 else -1.0)
            val stepRe = cos(angle)
            val stepIm = sin(angle)
            val half = len / 2
            for (start in 0 until n step len) {
                var wRe = 1.0
                var wIm = 0.0
                for (k in 0 until half) {
                    val oRe = re[start + k + half] * wRe - im[start + k + half] * wIm
                    val oIm = re[start + k + half] * wIm + im[start + k + half] * wRe
                    val eRe = re[start + k]
                    val eIm = im[start + k]
                    re[start + k] = eRe + oRe
                    im[start + k] = eIm + oIm
                    re[start + k + half] = eRe - oRe
                    im[start + k + half] = eIm - oIm
                    val next = wRe * stepRe - wIm * stepIm
                    wIm = wRe * stepIm + wIm * stepRe
                    wRe = next
                }
            }
            len = len shl 1
        }
        if (inverse) {
            for (i in 0 until n) {
                re[i] /= n
                im[i] /= n
            }
        }
    }

    private fun Double.twoDecimals(): String = ((this * 100).toLong() / 100.0).toString()
}
