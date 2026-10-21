package com.music.bitchord.data.lyrics

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.test.Test
import kotlin.test.assertEquals

class MusixmatchDateTest {

    @Test
    fun `utc date matches the JDK calendar`() {
        val format = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC)
        listOf(0L, 951_782_400_000L, 1_709_251_199_999L, 1_709_251_200_000L, 4_102_444_800_000L, -86_400_000L)
            .forEach { ms -> assertEquals(format.format(Instant.ofEpochMilli(ms)), Musixmatch.utcDate(ms), "at $ms") }
    }
}
