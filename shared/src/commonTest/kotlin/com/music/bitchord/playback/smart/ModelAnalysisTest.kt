package com.music.bitchord.playback.smart

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelAnalysisTest {

    /** Logits with a peak every [period] frames from [first], positive only at the peaks. */
    private fun train(frames: Int, period: Int, first: Int = 0): FloatArray =
        FloatArray(frames) { i -> if (i >= first && (i - first) % period == 0) 3f else -2f }

    @Test
    fun gridFromALogitTrainHasTheTrainsTempoAndDownbeats() {
        // 120 bpm at 50 fps: a beat every 25 frames, a bar every 100.
        val beat = train(1000, 25, first = 10)
        val downbeat = train(1000, 100, first = 10)
        val grid = assertNotNull(BeatModel.grid(beat, downbeat, offsetSeconds = 30.0))
        assertTrue(abs(grid.bpm - 120.0) < 0.5, "bpm ${grid.bpm}")
        assertEquals(30.2, grid.firstBeat, 1e-6)
        assertEquals(10, grid.downbeats.size)
        assertTrue(grid.downbeats.all { d -> grid.beats.any { abs(it - d) < 1e-9 } }, "downbeats are beats")
        assertTrue(grid.beatConfidence in 0.8..0.95, "confidence ${grid.beatConfidence}")
    }

    @Test
    fun tooFewBeatsIsNoGrid() {
        val beat = train(150, 25)
        assertNull(BeatModel.grid(beat, beat, 0.0))
    }

    @Test
    fun peaksAreRefinedBetweenFrames() {
        val logits = floatArrayOf(-1f, 1f, 2f, 1.5f, -1f)
        val peak = BeatModel.pickPeaks(logits).single()
        assertTrue(peak > 2.0 && peak < 2.5, "peak $peak")
    }

    @Test
    fun chunksCoverEveryFrameOnce() {
        for (frames in listOf(10, 1500, 1501, 3100, 4600)) {
            val kept = BooleanArray(frames)
            for (chunk in BeatModel.chunks(frames)) {
                assertTrue(chunk.length <= BeatModel.CHUNK_FRAMES)
                for (i in chunk.keepFrom until chunk.keepTo) {
                    val at = chunk.start + i
                    if (at < frames) kept[at] = true
                }
            }
            assertTrue(kept.all { it }, "every one of $frames frames kept")
        }
    }

    @Test
    fun windowsCoverTheHeadAndTailWithOverlap() {
        val span = VocalModel.WINDOW_SECONDS
        val spans = ModelRegions.spans(240.0, span)
        assertEquals(ModelRegions.HEAD_WINDOWS + ModelRegions.TAIL_WINDOWS, spans.windows.size)
        assertEquals(0.0, spans.windows.first().first)
        assertEquals(240.0, spans.windows.last().second, 1e-9)
        // Neighbours within the head, and within the tail, overlap by at least a second.
        val head = spans.windows.filter { it.first < spans.headEnd }
        val tail = spans.windows.filter { it.first >= spans.tailStart }
        for (side in listOf(head, tail)) {
            side.zipWithNext().forEach { (a, b) -> assertTrue(a.second - b.first >= ModelRegions.WINDOW_OVERLAP_SECONDS - 1e-6) }
        }
        assertTrue(spans.windows.all { it.second - it.first <= span + 1e-9 })
    }

    @Test
    fun aShortTrackIsCoveredOnce() {
        val spans = ModelRegions.spans(60.0, VocalModel.WINDOW_SECONDS)
        assertEquals(0.0, spans.windows.first().first)
        assertEquals(60.0, spans.windows.last().second, 1e-9)
        assertEquals(3, spans.windows.size)
    }

    private fun region(start: Double, seconds: Double, bpm: Double): ModelRegions.Region {
        val interval = 60 / bpm
        val beats = generateSequence(start) { it + interval }.takeWhile { it < start + seconds }.toList()
        return ModelRegions.Region(
            start = start,
            seconds = seconds,
            grid = BeatModel.Grid(beats, beats.filterIndexed { i, _ -> i % 4 == 0 }, bpm, interval, beats.first(), 0.9),
            vocalValues = FloatArray((seconds * VocalModel.FRAME_RATE).toInt()) { 0.7f },
            levelWindow = null,
        )
    }

    @Test
    fun seamedBeatsHaveNoDuplicatesAtOverlaps() {
        val a = region(0.0, 22.0, 120.0)
        val b = region(21.0, 22.0, 120.0)
        val seamed = ModelRegions.seamBeats(listOf(b, a))
        seamed.beats.zipWithNext().forEach { (x, y) -> assertTrue(y - x > 0.25, "beats $x and $y") }
        assertEquals(0.9, seamed.confidence, 1e-9)
    }

    @Test
    fun assembledAnalysisCarriesBothSidesAndTheVocalWindows() {
        val dsp = TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = "t",
            duration = 200.0,
            bpm = 60.0,
            beatConfidence = 0.3,
            energyCurve = (0 until 240).map { EnergySample(it * 200.0 / 240, 0.5) },
            vocalActivityMask = List(240) { 0.1 },
        )
        val span = VocalModel.WINDOW_SECONDS
        val spans = ModelRegions.spans(dsp.duration, span)
        val regions = spans.windows.map { (from, to) -> region(from, to - from, 124.0) }
        val out = ModelRegions.assemble(dsp, spans, regions)
        assertTrue(abs(out.bpm - 124.0) < 0.5, "bpm ${out.bpm}")
        assertEquals(0.9, out.beatConfidence, 1e-9)
        assertNotNull(out.headBeats)
        assertNotNull(out.tailBeats)
        assertEquals(regions.size, out.vocalWindows.size)
        // Measured over the windows, neutral between them, never the DSP's 0.1.
        assertEquals(0.7, out.vocalActivityMask.first(), 1e-6)
        assertEquals(ModelRegions.NEUTRAL_VOCAL, out.vocalActivityMask[120], 1e-9)
    }

    @Test
    fun withoutModelsTheDspAnalysisStands() {
        val dsp = TrackAnalysis(status = TrackAnalysis.STATUS_READY, trackId = "t", duration = 200.0, bpm = 98.0, vocalActivityMask = listOf(0.4))
        val spans = ModelRegions.spans(dsp.duration, VocalModel.WINDOW_SECONDS)
        val out = ModelRegions.assemble(dsp, spans, emptyList())
        assertEquals(98.0, out.bpm)
        assertEquals(listOf(0.4), out.vocalActivityMask)
        assertNull(out.headBeats)
    }

    @Test
    fun bandCurveIsTheTargetShareOfTheMix() {
        val bins = VocalModel.BINS
        val mix = FloatArray(VocalModel.CHANNELS * bins * VocalModel.FIXED_FRAMES) { 1f }
        val target = FloatArray(mix.size) { 0.5f }
        val curve = VocalModel.bandCurve(mix, target, usableFrames = 10)
        assertEquals(10, curve.size)
        assertTrue(curve.all { abs(it - 0.5f) < 1e-6f })
    }

    @Test
    fun fixedFramesPadsEachBinsTail() {
        val bins = 3
        val frames = 2
        val values = FloatArray(VocalModel.CHANNELS * bins * frames) { (it + 1).toFloat() }
        val fixed = VocalModel.fixedFrames(values, frames, bins)
        assertEquals(VocalModel.CHANNELS * bins * VocalModel.FIXED_FRAMES, fixed.size)
        assertEquals(3f, fixed[VocalModel.FIXED_FRAMES])
        assertEquals(0f, fixed[2])
    }

    @Test
    fun levelOfFullScaleIsOne() {
        val n = 44_100
        val left = FloatArray(n) { 1f }
        val level = assertNotNull(ModelRegions.levelCurve(left, left, n, 44_100.0, 5.0))
        assertEquals(10, level.values.size)
        assertEquals(5.0, level.startTime)
        assertTrue(level.values.all { abs(it - 1f) < 1e-6f })
    }
}
