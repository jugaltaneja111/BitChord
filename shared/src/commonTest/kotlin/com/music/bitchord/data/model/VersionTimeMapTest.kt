package com.music.bitchord.data.model

import kotlin.test.Test
import kotlin.test.assertEquals

class VersionTimeMapTest {

    // A video with a 10 s intro, then a 30 s skit after the first verse:
    // song 0..60 s is video 10..70 s; song 60..180 s is video 100..220 s.
    private val map = VersionTimeMap(
        listOf(
            VersionTimeMap.Segment(fromStartMs = 0, toStartMs = 10_000, durationMs = 60_000),
            VersionTimeMap.Segment(fromStartMs = 60_000, toStartMs = 100_000, durationMs = 120_000),
        ),
    )

    @Test
    fun insideASegmentMapsExactly() {
        assertEquals(40_000, map.map(30_000))
        assertEquals(130_000, map.map(90_000))
    }

    @Test
    fun theSkitInTheMiddleIsSkippedInBothDirections() {
        // Song 59.9 s is just before the skit; 60 s is just after it in the video.
        assertEquals(69_900, map.map(59_900))
        assertEquals(100_000, map.map(60_000))
        // In the video's skit (70..100 s), the song picks up where the music resumes.
        assertEquals(60_000, map.inverted().map(85_000))
    }

    @Test
    fun pastTheEndLandsAtTheEnd() {
        assertEquals(220_000, map.map(400_000))
    }

    @Test
    fun invertingTwiceIsTheSameMap() {
        val twice = map.inverted().inverted()
        listOf(0L, 30_000L, 59_900L, 60_000L, 90_000L, 400_000L).forEach {
            assertEquals(map.map(it), twice.map(it))
        }
    }
}
