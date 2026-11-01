/*
 * Copyright (C) 2026 Kushagra Singh
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 */

package com.music.bitchord.playback.smart

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp

/**
 * The DJ-style three-band waveform the Automix mix popup draws: lows, mids and highs as
 * separate peak envelopes over [fromSeconds]..[toSeconds] of one track, in [bins] columns.
 *
 * The band split and normalisation of the phone's `MixWaveform`, apart from its decoder: [feed]
 * takes mono PCM as it is decoded, so a platform decodes however it likes and the picture comes
 * out the same. Two one-pole stages per split, a gentle 12 dB/octave, which is all a picture needs.
 */
class MixWaveformBands(
    private val fromSeconds: Double,
    private val toSeconds: Double,
    private val bins: Int,
) {
    val low = FloatArray(bins.coerceAtLeast(0))
    val mid = FloatArray(bins.coerceAtLeast(0))
    val high = FloatArray(bins.coerceAtLeast(0))

    private var lowA = 0f
    private var highA = 0f
    private var configuredRate = 0
    private var l1 = 0f
    private var l2 = 0f
    private var h1 = 0f
    private var h2 = 0f
    private val binsPerSecond = if (toSeconds > fromSeconds) bins / (toSeconds - fromSeconds) else 0.0

    /** Where the filters start settling: audio further ahead of the window is skipped whole. */
    val warmFrom: Double get() = fromSeconds - WARM_UP_SECONDS

    /** [count] mono samples of [samples] from [offset], at [rate], the first at track second [at]. */
    fun feed(samples: FloatArray, offset: Int, count: Int, at: Double, rate: Int) {
        if (rate <= 0 || bins <= 0 || binsPerSecond <= 0.0) return
        if (at + count.toDouble() / rate < warmFrom) return
        if (rate != configuredRate) {
            configuredRate = rate
            lowA = (1.0 - exp(-2.0 * PI * LOW_HZ / rate)).toFloat()
            highA = (1.0 - exp(-2.0 * PI * HIGH_HZ / rate)).toFloat()
        }
        val firstBin = (at - fromSeconds) * binsPerSecond
        val binStep = binsPerSecond / rate
        for (index in 0 until count) {
            val x = samples[offset + index]
            l1 += lowA * (x - l1)
            l2 += lowA * (l1 - l2)
            h1 += highA * (x - h1)
            h2 += highA * (h1 - h2)
            val position = firstBin + index * binStep
            if (position < 0.0) continue
            val bin = position.toInt()
            if (bin >= bins) break
            val lo = abs(l2)
            val mi = abs(h2 - l2)
            val hi = abs(x - h2)
            if (lo > low[bin]) low[bin] = lo
            if (mi > mid[bin]) mid[bin] = mi
            if (hi > high[bin]) high[bin] = hi
        }
    }

    /** Scales each band to the popup's ceilings; call once, after the last [feed]. */
    fun finish(): MixWaveformBands {
        normalize(low, 1f)
        normalize(mid, 0.82f)
        normalize(high, 0.5f)
        return this
    }

    companion object {
        /** Below this is the bass layer; above [HIGH_HZ] the highs; between them, mids. */
        const val LOW_HZ = 200.0
        const val HIGH_HZ = 2_500.0

        /** Audio decoded ahead of the window to settle the filters. */
        const val WARM_UP_SECONDS = 0.05

        /** Waveform columns per second of audio, and a ceiling for very long windows. */
        const val BINS_PER_SECOND = 40.0
        const val MAX_BINS = 3_000

        /** Columns for a window of [seconds], as the phone sizes them. */
        fun binsFor(seconds: Double): Int = (seconds * BINS_PER_SECOND).toInt().coerceIn(64, MAX_BINS)

        /**
         * Scales a band so its loud passages reach [ceiling], judged by a high percentile rather
         * than the single biggest peak, which one transient would otherwise set for the whole picture.
         */
        internal fun normalize(band: FloatArray, ceiling: Float) {
            val sorted = band.filter { it > 0f }.sorted()
            if (sorted.isEmpty()) return
            val reference = sorted[(sorted.size * 0.97).toInt().coerceAtMost(sorted.size - 1)]
            if (reference <= 1e-6f) return
            for (index in band.indices) band[index] = (band[index] / reference * ceiling).coerceAtMost(ceiling * 1.08f)
        }
    }
}
