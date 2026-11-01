package com.music.bitchord.data.innertube

import com.music.bitchord.data.innertube.InnerTubeXResolver.VideoStream
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExtractVideoCodecTest {

    private fun stream(profile: String, codecs: String) =
        VideoStream("v", "https://example/$profile", 1920, 1080, "video/mp4", codecs, emptyMap(), listOf(1080), profile)

    /** A fake InnerTubeX: the first client not skipped answers, in this order. */
    private fun clients(vararg offered: Pair<String, String>): Pair<suspend (Set<String>) -> VideoStream?, MutableList<Set<String>>> {
        val asked = mutableListOf<Set<String>>()
        val ask: suspend (Set<String>) -> VideoStream? = { skip ->
            asked += skip
            offered.firstOrNull { it.first !in skip }?.let { stream(it.first, it.second) }
        }
        return ask to asked
    }

    @Test
    fun h264FirstTimeIsTaken() : Unit = runBlocking {
        val (ask, asked) = clients("IOS" to "avc1.640028")
        val got = InnerTubeXResolver.pickByCodec(emptySet(), "avc1", 3, ask)
        assertEquals("IOS", got?.profileId)
        assertEquals(1, asked.size)
        assertEquals(emptyList(), got?.codecRejected)
    }

    @Test
    fun vp9IsPassedOverForTheNextClient() : Unit = runBlocking {
        val (ask, asked) = clients("TV" to "vp09.00.40.08", "IOS" to "avc1.640028")
        val got = InnerTubeXResolver.pickByCodec(emptySet(), "avc1", 3, ask)
        assertEquals("IOS", got?.profileId)
        assertEquals(listOf(emptySet(), setOf("TV")), asked)
        assertEquals(listOf("TV:vp09.00.40.08"), got?.codecRejected)
    }

    @Test
    fun noH264WithinTheBudgetIsNull() : Unit = runBlocking {
        val (ask, asked) = clients("A" to "vp9", "B" to "av01", "C" to "vp9", "D" to "avc1")
        assertNull(InnerTubeXResolver.pickByCodec(emptySet(), "avc1", 3, ask))
        assertEquals(3, asked.size)
    }

    @Test
    fun callerSkipsAreKeptAndNoCodecMeansAnything() : Unit = runBlocking {
        val (ask, asked) = clients("A" to "vp9", "B" to "vp9")
        val got = InnerTubeXResolver.pickByCodec(setOf("A"), null, 3, ask)
        assertEquals("B", got?.profileId)
        assertEquals(listOf(setOf("A")), asked)
    }

    @Test
    fun hlsManifestIsTakenWhateverItsCodecField() : Unit = runBlocking {
        val hls = VideoStream("v", "https://example/m3u8", 0, 0, "application/x-mpegURL", "", emptyMap(), emptyList(), "IOS", isHls = true)
        val got = InnerTubeXResolver.pickByCodec(emptySet(), "avc1", 3) { hls }
        assertEquals(true, got?.isHls)
    }
}
