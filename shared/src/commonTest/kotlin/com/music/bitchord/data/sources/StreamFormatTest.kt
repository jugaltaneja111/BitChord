package com.music.bitchord.data.sources

import com.music.bitchord.data.NerdStats
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// Pins the labels the JVM produced with String.format / BigDecimal, so every platform's formatting
// gives the same text.
class StreamFormatTest {

    @Test
    fun `sample rates read in kHz to one decimal`() {
        assertEquals("44.1 kHz", StreamFormat(sampleRateHz = 44_100).summary)
        assertEquals("48 kHz", StreamFormat(sampleRateHz = 48_000).summary)
        assertEquals("88.2 kHz", StreamFormat(sampleRateHz = 88_200).summary)
        assertEquals("192 kHz", StreamFormat(sampleRateHz = 192_000).summary)
        assertEquals("352.8 kHz", StreamFormat(sampleRateHz = 352_800).summary)
    }

    // These round the float's binary value, not the decimal one: 22.05f is 22.0499…, so "22".
    // Taken from what String.format printed on the JVM.
    @Test
    fun `sample rates round the float they are divided into`() {
        assertEquals("22 kHz", StreamFormat(sampleRateHz = 22_050).summary)
        assertEquals("11 kHz", StreamFormat(sampleRateHz = 11_025).summary)
        assertEquals("7.9 kHz", StreamFormat(sampleRateHz = 7_950).summary)
        assertEquals("176.4 kHz", StreamFormat(sampleRateHz = 176_400).summary)
        assertEquals("705.6 kHz", StreamFormat(sampleRateHz = 705_600).summary)
    }

    @Test
    fun `the parts that are known are joined`() {
        assertEquals(
            "FLAC · 24-bit · 96 kHz",
            StreamFormat(codec = "flac", sampleRateHz = 96_000, bitDepth = 24, kbps = 4_000).summary,
        )
        assertEquals("Unknown format", StreamFormat().summary)
    }

    @Test
    fun `megahertz labels keep every digit`() {
        assertEquals("2.8224 MHz", NerdStats.megahertzLabel(2_822_400))
        assertEquals("45.1584 MHz", NerdStats.megahertzLabel(45_158_400))
        assertEquals("1 MHz", NerdStats.megahertzLabel(1_000_000))
        assertEquals("11.2896 MHz", NerdStats.megahertzLabel(11_289_600))
        assertNull(NerdStats.megahertzLabel(192_000))
    }
}
