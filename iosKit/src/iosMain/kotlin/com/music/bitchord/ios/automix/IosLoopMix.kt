@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.music.bitchord.ios.automix

import com.music.bitchord.data.TrackLog
import com.music.bitchord.ios.TapDsp
import com.music.bitchord.ios.ffmpeg.FfmpegAudioEngine
import com.music.bitchord.playback.smart.LoopMix
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.AVFAudio.AVAudioFormat
import platform.AVFAudio.AVAudioPCMBuffer
import platform.AVFAudio.AVAudioPlayerNode
import platform.AVFAudio.AVAudioPlayerNodeCompletionDataConsumed
import platform.AVFAudio.AVAudioTime
import platform.Foundation.NSCondition
import platform.Foundation.dateByAddingTimeInterval
import platform.Foundation.NSQualityOfServiceUserInteractive
import platform.Foundation.NSThread
import kotlin.concurrent.Volatile
import kotlin.math.max
import kotlin.math.roundToLong

/** A stretch of a song decoded for a deck: planar, at the renderer's rate, from track second [start]. */
internal class LoopRegion(val left: FloatArray, val right: FloatArray, val start: Double)

/** The host clock, mach ticks, which AVAudioTime and AVPlayer's host-time calls both speak. */
internal object HostClock {
    private val ticksPerSecond: Double = AVAudioTime.hostTimeForSeconds(1000.0).toDouble() / 1000.0

    fun now(): ULong = platform.CoreMedia.CMClockConvertHostTimeToSystemUnits(
        platform.CoreMedia.CMClockGetTime(platform.CoreMedia.CMClockGetHostTimeClock()),
    )

    fun ticks(seconds: Double): Long = (seconds * ticksPerSecond).roundToLong()

    fun seconds(ticks: Long): Double = ticks / ticksPerSecond

    fun plus(host: ULong, seconds: Double): ULong = (host.toLong() + ticks(seconds)).toULong()

    /** [a] - [b] in seconds. */
    fun between(a: ULong, b: ULong): Double = seconds(a.toLong() - b.toLong())
}

/**
 * One loop mix's sound: the plan and its clock, the renderer with both decks loaded, and the
 * player node it plays through on [FfmpegAudioEngine]'s graph. Upstream's `LoopMixSession` (the
 * decks, their knots and envelopes, unchanged), with the sink it ran inside replaced by a node of
 * its own, fed [BLOCK]-frame buffers by a feeder thread a few blocks ahead.
 *
 * Every time here is mix seconds, 0 being the moment the outgoing song reaches its loop start,
 * unless it says track seconds. On the engine's clock, mix second m is rendered at host time
 * [h0] + m: the node is started at that host time for its first frame, so the two stay in step
 * sample for sample, and a player on the same clock can be put beside it.
 */
internal class IosLoopMix private constructor(
    val plan: LoopMix.Plan,
    val clock: LoopMix.Clock,
    val outId: String,
    val inId: String,
    private val renderer: IosLoopMixRenderer,
    val sampleRate: Int,
    /** The outgoing region, kept for the splice check. */
    private val outRegion: LoopRegion,
) {
    /** Track second the incoming player becomes audible at. */
    val handoverTrack: Double = clock.elementTrackTime

    /**
     * The mix second the incoming player starts at (silent), and where in its track: half a second
     * in, on the deck's own timeline; or, when that would be before its song's start (a loop in
     * the intro, a long mix), the moment that timeline reaches the song's first second.
     */
    val inStartAt: Double = max(STARTUP_LEAD, clock.elementAt - handoverTrack)
    val inStartTrack: Double = max(0.0, handoverTrack - (clock.elementAt - inStartAt))

    /** Mix frame 0's render host time; 0 until [start]. */
    @Volatile
    var h0: ULong = 0uL
        private set

    private val firstFrame: Long = renderer.firstFrame()

    /** Mix seconds of the renderer's first frame (negative: the decks' pre-roll before t0). */
    val firstSeconds: Double get() = firstFrame.toDouble() / sampleRate

    private val node = AVAudioPlayerNode()
    private var attachedTo = -1
    private val lock = NSCondition()
    private var running = false
    /** The feeder thread is between its first and last block; [close] waits it out. */
    private var feeding = false
    private var inFlight = 0
    private var nextFrame = 0L
    private val pool = ArrayList<AVAudioPCMBuffer>()
    private var poolIndex = 0
    private var dsp: TapDsp? = null
    private val onEngineReset: () -> Unit = { engineLost = true }

    /** The engine dropped the node (a route change, a reset): the mix can't go on. */
    @Volatile
    var engineLost = false
        private set

    /** Mix seconds at render host time [host]. */
    fun mixSecondsAt(host: ULong): Double = HostClock.between(host, h0)

    /** Render host time of mix second [m]. */
    fun hostAt(m: Double): ULong = HostClock.plus(h0, m)

    /**
     * Starts the node so mix second 0 is rendered at host time [h0]; its first frames are
     * scheduled now, through [dsp] (the outgoing song's loudness and the equaliser), and the
     * feeder keeps it ahead from then on. The error when the engine won't run.
     */
    fun start(h0: ULong, dsp: TapDsp): String? {
        this.h0 = h0
        this.dsp = dsp
        FfmpegAudioEngine.ensureRunning()?.let { return it }
        val format = AVAudioFormat(standardFormatWithSampleRate = sampleRate.toDouble(), channels = 2u)
        format.streamDescription?.let { dsp.prepare(it.pointed) }
        attachedTo = FfmpegAudioEngine.connect(node, format, attachedTo)
        FfmpegAudioEngine.register(onEngineReset)
        FfmpegAudioEngine.want(this, true)
        repeat(POOL) {
            val buffer = AVAudioPCMBuffer(pCMFormat = format, frameCapacity = BLOCK.toUInt())
                ?: return "out of memory"
            pool += buffer
        }
        nextFrame = firstFrame
        running = true
        // The first blocks before the start, so the node has them when its moment comes.
        repeat(POOL) { feedOne() }
        node.playAtTime(AVAudioTime.timeWithHostTime(hostAt(firstSeconds)))
        feeding = true
        val thread = NSThread(block = { feed() })
        thread.setName("BitChord loop mix")
        thread.setQualityOfService(NSQualityOfServiceUserInteractive)
        thread.start()
        return null
    }

    /** Renders and schedules one block, if a buffer is free; the feeder thread's, or [start]'s. */
    private fun feedOne(): Boolean {
        lock.lock()
        val free = running && inFlight < POOL
        if (free) inFlight++
        lock.unlock()
        if (!free) return false
        val buffer = pool[poolIndex]
        poolIndex = (poolIndex + 1) % POOL
        val channels = buffer.floatChannelData ?: return false
        val left = channels[0] ?: return false
        val right = channels[1] ?: return false
        renderer.renderPlanar(nextFrame, left, right, BLOCK)
        buffer.frameLength = BLOCK.toUInt()
        buffer.mutableAudioBufferList?.let { dsp?.process(it.pointed, BLOCK) }
        nextFrame += BLOCK
        node.scheduleBuffer(buffer, completionCallbackType = AVAudioPlayerNodeCompletionDataConsumed) { _ ->
            lock.lock()
            inFlight--
            lock.signal()
            lock.unlock()
        }
        return true
    }

    private fun feed() {
        try {
            while (true) {
                lock.lock()
                while (running && inFlight >= POOL) lock.wait()
                val go = running
                lock.unlock()
                if (!go) return
                feedOne()
            }
        } finally {
            lock.lock()
            feeding = false
            lock.broadcast()
            lock.unlock()
        }
    }

    /**
     * How far the node is from where it should be, in seconds (positive: ahead): the mix second it
     * says it is rendering at its last render host time, against [mixSecondsAt] that host time.
     * Null while it hasn't started.
     */
    fun renderError(): Double? {
        val render = node.lastRenderTime ?: return null
        if (!render.hostTimeValid) return null
        val player = node.playerTimeForNodeTime(render) ?: return null
        if (!player.sampleTimeValid || player.sampleTime < 0) return null
        return (firstSeconds + player.sampleTime.toDouble() / sampleRate) - mixSecondsAt(render.hostTime)
    }

    /** Track seconds [index] (OUT or IN) played at the end of the last rendered block. */
    fun heard(index: Int): Double = renderer.heard(index)

    /**
     * Where the incoming deck will be at mix second [m], in its track's seconds: after the release
     * it plays its song at rate 1 from the loop end, so this is the player's timeline throughout.
     */
    fun incomingTrackAt(m: Double): Double = handoverTrack - (clock.elementAt - m)

    /**
     * How far apart the outgoing song as its player decoded it ([song], the tap's capture before
     * the loop start, at [songRate]) and as the region decoder did are, in frames (positive: the
     * region late), and the correlation there; null when it can't be said.
     */
    fun spliceLag(song: FloatArray, filled: Int, songRate: Int, maxLag: Int = 960): Pair<Int, Double>? {
        if (filled < song.size || songRate <= 0) return null
        val n = song.size
        val sr = sampleRate.toDouble()
        // The region read at the song's own rate (the tap's), linearly: good to well under a ms.
        val firstTrack = plan.out.loopStart - (n + maxLag).toDouble() / songRate
        val region = FloatArray(n + 2 * maxLag)
        for (i in region.indices) {
            val at = (firstTrack + i.toDouble() / songRate - outRegion.start) * sr
            val k = kotlin.math.floor(at).toInt()
            if (k < 0 || k + 1 >= outRegion.left.size) return null
            val f = (at - k).toFloat()
            val a0 = (outRegion.left[k] + outRegion.right[k]) * 0.5f
            val a1 = (outRegion.left[k + 1] + outRegion.right[k + 1]) * 0.5f
            region[i] = a0 + (a1 - a0) * f
        }
        var best = 0
        var bestScore = Double.NEGATIVE_INFINITY
        for (lag in -maxLag..maxLag) {
            var dot = 0.0
            var a2 = 0.0
            var b2 = 0.0
            for (i in 0 until n) {
                val a = song[i].toDouble()
                val b = region[i + maxLag + lag].toDouble()
                dot += a * b
                a2 += a * a
                b2 += b * b
            }
            val norm = kotlin.math.sqrt(a2 * b2)
            if (norm > 0 && dot / norm > bestScore) {
                bestScore = dot / norm
                best = lag
            }
        }
        return if (bestScore.isFinite()) best to bestScore else null
    }

    /** Stops the feeder, then the node; the renderer is freed once nothing can call it. Any thread. */
    fun close() {
        lock.lock()
        val was = running
        running = false
        lock.broadcast()
        // A block in flight takes milliseconds; the node isn't stopped under it.
        val deadline = platform.Foundation.NSDate().dateByAddingTimeInterval(0.5)
        while (feeding) {
            if (!lock.waitUntilDate(deadline)) break
        }
        lock.unlock()
        FfmpegAudioEngine.unregister(onEngineReset)
        if (was || attachedTo >= 0) {
            node.stop()
            FfmpegAudioEngine.detach(node)
            FfmpegAudioEngine.want(this, false)
        }
        // Freed a moment later all the same, should the wait above have run out.
        val renderer = renderer
        platform.darwin.dispatch_after(
            platform.darwin.dispatch_time(platform.darwin.DISPATCH_TIME_NOW, 500_000_000L),
            platform.darwin.dispatch_get_global_queue(platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u),
        ) {
            lock.lock()
            lock.unlock()
            renderer.close()
        }
    }

    companion object {
        private const val TAG = "IosLoopMix"

        /** Upstream's: mix seconds into the mix at which the incoming player starts, silent. */
        const val STARTUP_LEAD = 0.5

        /** Seconds of each song decoded either side of what the decks play. */
        private const val PREROLL_SECONDS = 1.0
        private const val TAIL_SECONDS = 3.0

        /** Frames per scheduled buffer, and how many are in flight: ~0.5 s ahead at 48 kHz. */
        const val BLOCK = 4096
        private const val POOL = 6

        /**
         * Builds the renderer for [plan] at [sampleRate], decoding the two regions through [decode]
         * (track seconds from..to, at [sampleRate]); null when either won't decode. Blocking: off
         * the main thread. [inLevel] scales the incoming deck (loudness matching). Upstream's
         * `LoopMixSession.build`, unchanged in what it gives the decks.
         */
        fun build(
            plan: LoopMix.Plan,
            outId: String,
            inId: String,
            sampleRate: Int,
            inLevel: Float,
            decode: (side: LoopMix.Side, from: Double, to: Double) -> LoopRegion?,
        ): IosLoopMix? {
            if (!IosLoopMixNative.available || sampleRate <= 0) return null
            val clock = LoopMix.Clock(plan)
            val outRegion = decode(LoopMix.Side.OUT, plan.out.loopStart - PREROLL_SECONDS, plan.out.loopEnd + 0.5)
                ?: return null
            val inRegion = decode(LoopMix.Side.IN, plan.inn.loopStart - PREROLL_SECONDS, clock.elementTrackTime + TAIL_SECONDS)
                ?: return null
            if (outRegion.start > plan.out.loopStart || inRegion.start > plan.inn.loopStart) {
                TrackLog.w(TAG, "a region starts after its loop: out ${outRegion.start}, in ${inRegion.start}")
                return null
            }
            val renderer = IosLoopMixRenderer(sampleRate)
            if (!renderer.isValid) return null
            val sr = sampleRate.toDouble()
            val beats = clock.beats

            fun samplesOf(region: LoopRegion, trackSeconds: Double) = (trackSeconds - region.start) * sr

            // Outgoing: its loop, knot by knot, looping until it stops at the release.
            renderer.setDeck(
                IosLoopMixRenderer.OUT,
                IosLoopMixRenderer.DeckSetup(
                    left = outRegion.left,
                    right = outRegion.right,
                    regionStart = outRegion.start,
                    loopStart = samplesOf(outRegion, plan.out.loopStart).roundToLong(),
                    loopEnd = samplesOf(outRegion, plan.out.loopEnd).roundToLong(),
                    releaseAt = 1e18,
                    knotFrames = DoubleArray(beats + 1) { clock.beatTimes[it] * sr },
                    knotPositions = DoubleArray(beats + 1) { samplesOf(outRegion, clock.outPositionAt(it)) },
                    runOn = false,
                    stopAt = clock.releaseAt + 0.05,
                    preroll = 0.4,
                    level = 1f,
                ),
            )
            // Incoming: its loop until the release, then on from the loop end at rate 1.
            renderer.setDeck(
                IosLoopMixRenderer.IN,
                IosLoopMixRenderer.DeckSetup(
                    left = inRegion.left,
                    right = inRegion.right,
                    regionStart = inRegion.start,
                    loopStart = samplesOf(inRegion, plan.inn.loopStart).roundToLong(),
                    loopEnd = samplesOf(inRegion, plan.inn.loopEnd).roundToLong(),
                    releaseAt = samplesOf(inRegion, clock.inReleasePosition),
                    knotFrames = DoubleArray(beats + 1) { clock.beatTimes[it] * sr },
                    knotPositions = DoubleArray(beats + 1) { samplesOf(inRegion, clock.inPositionAt(it)) },
                    runOn = true,
                    stopAt = clock.elementAt + 2.0,
                    preroll = 0.4,
                    level = inLevel,
                ),
            )
            // Gains: the outgoing deck takes over from its song, the incoming loop rises over the
            // entry and the ramp, the outgoing one falls away.
            val h = LoopMix.HANDOVER_SEC
            renderer.setEnvelope(
                IosLoopMixRenderer.OUT, IosLoopMixRenderer.KIND_GAIN,
                doubleArrayOf(-h, 0.0, clock.enterEnd, clock.rampEnd, clock.releaseAt),
                floatArrayOf(0f, 1f, 1f, 0.6f, 0f),
            )
            renderer.setEnvelope(
                IosLoopMixRenderer.IN, IosLoopMixRenderer.KIND_GAIN,
                doubleArrayOf(0.0, clock.enterEnd, clock.rampEnd),
                floatArrayOf(0f, 0.55f, 1f),
            )
            // Bass: the incoming loop comes in without it; they swap over one bar mid-ramp.
            val swapFrom = clock.handoffAt - clock.swapBar / 2
            val swapTo = clock.handoffAt + clock.swapBar / 2
            val cut = LoopMix.BASS_CUT_DB.toFloat()
            renderer.setEnvelope(IosLoopMixRenderer.OUT, IosLoopMixRenderer.KIND_LOW_DB, doubleArrayOf(swapFrom, swapTo), floatArrayOf(0f, cut))
            renderer.setEnvelope(IosLoopMixRenderer.IN, IosLoopMixRenderer.KIND_LOW_DB, doubleArrayOf(swapFrom, swapTo), floatArrayOf(cut, 0f))
            // The outgoing loop drains away through a rising high-pass once the tempo has arrived.
            renderer.setEnvelope(
                IosLoopMixRenderer.OUT, IosLoopMixRenderer.KIND_HIGH_PASS_HZ,
                doubleArrayOf(clock.rampEnd, clock.releaseAt),
                floatArrayOf(LoopMix.HIGH_PASS_FROM_HZ.toFloat(), LoopMix.HIGH_PASS_TO_HZ.toFloat()),
                exponential = true,
            )
            renderer.prepare()
            // The incoming deck hands over to its own player at the same track position.
            renderer.setFadeOut(IosLoopMixRenderer.IN, clock.elementTrackTime)
            return IosLoopMix(plan, clock, outId, inId, renderer, sampleRate, outRegion)
        }
    }
}
