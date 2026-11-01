package com.music.bitchord.playback.smart

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MixWaveformBandsTest {
    private val rate = 11_025

    private fun tone(hz: Double, seconds: Double) = FloatArray((rate * seconds).toInt()) { sin(2 * PI * hz * it / rate).toFloat() * 0.5f }

    private fun bandsOf(hz: Double): MixWaveformBands {
        val bands = MixWaveformBands(0.5, 1.5, 40)
        val pcm = tone(hz, 2.0)
        bands.feed(pcm, 0, pcm.size, 0.0, rate)
        // Before normalisation: the raw envelopes say which band carries the tone.
        return bands
    }

    @Test
    fun aLowToneLandsInTheLows() {
        val b = bandsOf(60.0)
        assertTrue(b.low.average() > b.mid.average(), "low ${b.low.average()} mid ${b.mid.average()}")
        assertTrue(b.low.average() > b.high.average())
    }

    @Test
    fun aMidToneLandsInTheMids() {
        val b = bandsOf(800.0)
        assertTrue(b.mid.average() > b.low.average(), "mid ${b.mid.average()} low ${b.low.average()}")
        assertTrue(b.mid.average() > b.high.average())
    }

    @Test
    fun aHighToneLandsInTheHighs() {
        val b = bandsOf(5_000.0)
        assertTrue(b.high.average() > b.low.average(), "high ${b.high.average()} low ${b.low.average()}")
        assertTrue(b.high.average() > b.mid.average())
    }

    @Test
    fun normalisationReachesTheCeiling() {
        val b = bandsOf(60.0).finish()
        assertEquals(1f, b.low.sorted()[(b.low.size * 0.97).toInt()], 1e-3f)
        assertTrue(b.low.all { it <= 1.08f })
    }

    @Test
    fun audioBeforeTheWarmUpIsIgnored() {
        val bands = MixWaveformBands(10.0, 11.0, 40)
        val pcm = tone(60.0, 1.0)
        bands.feed(pcm, 0, pcm.size, 0.0, rate)
        assertTrue(bands.low.all { it == 0f })
    }
}
