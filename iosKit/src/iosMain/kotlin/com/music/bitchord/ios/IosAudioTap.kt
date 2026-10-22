package com.music.bitchord.ios

import com.music.bitchord.playback.EqCurve
import com.music.bitchord.playback.EqLayout
import com.music.bitchord.playback.FilterKind
import com.music.bitchord.playback.PeakLimiter
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.value
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.AVMutableAudioMix
import platform.AVFoundation.AVMutableAudioMixInputParameters
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.asset
import platform.AVFoundation.setAudioMix
import platform.AVFoundation.tracksWithMediaType
import platform.CoreAudioTypes.AudioBufferList
import platform.CoreAudioTypes.AudioStreamBasicDescription
import platform.CoreAudioTypes.kAudioFormatFlagIsFloat
import platform.CoreAudioTypes.kAudioFormatFlagIsNonInterleaved
import platform.CoreAudioTypes.kAudioFormatLinearPCM
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.kCFAllocatorDefault
import platform.MediaToolbox.MTAudioProcessingTapCallbacks
import platform.MediaToolbox.MTAudioProcessingTapCreate
import platform.MediaToolbox.MTAudioProcessingTapGetSourceAudio
import platform.MediaToolbox.MTAudioProcessingTapGetStorage
import platform.MediaToolbox.MTAudioProcessingTapRefVar
import platform.MediaToolbox.kMTAudioProcessingTapCallbacksVersion_0
import platform.MediaToolbox.kMTAudioProcessingTapCreationFlag_PostEffects
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.concurrent.Volatile
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * What the equaliser is aiming at, for every playing item at once: the phone's
 * `EqualizerProcessor.setTuning`. Swapped whole from the main thread, read by the audio thread.
 */
internal class EqTarget(val curve: EqCurve, val balance: Float) {
    companion object {
        val OFF = EqTarget(EqCurve.FLAT, 0f)
    }
}

internal object IosEqTarget {
    @Volatile
    var target: EqTarget = EqTarget.OFF
}

/**
 * One player item's sound, shaped on the way out: the equaliser's ten sections, its make-up and
 * balance, the track's loudness gain, and the peak limiter that keeps the sum under full scale.
 * The phone's EqualizerProcessor and LoudnessProcessor, on AVFoundation's float buffers.
 *
 * Everything the audio thread touches is allocated in [prepare], never in [process]; settings
 * reach it only through [IosEqTarget] and [targetGain], read once per sub-block and glided
 * towards, so a preset change or a new loudness figure never clicks.
 */
@OptIn(ExperimentalForeignApi::class)
internal class TapDsp {
    /** The track's loudness gain, linear; set from the main thread. */
    @Volatile
    var targetGain: Float = 1f

    /** Whether the tap is running on this item: until then the engine levels with the volume. */
    @Volatile
    var attached: Boolean = false

    /**
     * Skip silence's reading: set when a callback carries anything above the threshold, cleared by
     * whoever reads it. Nothing heard between two reads means nothing but silence went by.
     */
    @Volatile
    var heardSound: Boolean = false

    /** Skip silence: output nothing while a gap is fast-forwarded; fades back in when cleared. */
    @Volatile
    var muted: Boolean = false

    private var unmuteRamp = 1f

    private var sampleRate = 0
    private var channels = 0
    private var usable = false

    private val currentGainDb = FloatArray(EqLayout.SLOTS)
    private val currentQ = FloatArray(EqLayout.SLOTS) { 0.707f }
    private var currentPreampDb = 0f
    private var currentBalance = 0f
    private var currentGain = 1f
    private var bypassed = true

    private val coeffA1 = FloatArray(EqLayout.SLOTS)
    private val coeffA2 = FloatArray(EqLayout.SLOTS)
    private val coeffA3 = FloatArray(EqLayout.SLOTS)
    private val mixInput = FloatArray(EqLayout.SLOTS)
    private val mixBand = FloatArray(EqLayout.SLOTS)
    private val mixLow = FloatArray(EqLayout.SLOTS)
    private val activeSlots = IntArray(EqLayout.SLOTS)
    private val running = BooleanArray(EqLayout.SLOTS)

    private var state = FloatArray(0)
    private var channelGain = FloatArray(0)
    private var frame = FloatArray(0)

    /** Where each channel's samples are this callback: buffer, stride and offset. */
    private var channelData = arrayOfNulls<CPointer<FloatVar>>(0)
    private var channelStride = IntArray(0)
    private var channelOffset = IntArray(0)

    private val limiter = PeakLimiter()

    fun prepare(format: AudioStreamBasicDescription) {
        sampleRate = format.mSampleRate.toInt()
        channels = format.mChannelsPerFrame.toInt()
        usable = format.mFormatID == kAudioFormatLinearPCM &&
            (format.mFormatFlags and kAudioFormatFlagIsFloat) != 0u &&
            format.mBitsPerChannel == 32u &&
            sampleRate > 0 && channels in 1..8
        state = FloatArray(channels * EqLayout.SLOTS * 2)
        channelGain = FloatArray(channels) { 1f }
        frame = FloatArray(channels)
        channelData = arrayOfNulls(channels)
        channelStride = IntArray(channels)
        channelOffset = IntArray(channels)
        limiter.configure(sampleRate)
        running.fill(false)
        snapToTarget()
    }

    fun process(buffers: AudioBufferList, frames: Int) {
        if (!usable || frames <= 0) return
        if (!mapChannels(buffers)) return
        if (!isQuiet(frames)) heardSound = true
        if (muted) {
            for (c in 0 until channels) {
                val data = channelData[c] ?: return
                for (f in 0 until frames) data[f * channelStride[c] + channelOffset[c]] = 0f
            }
            unmuteRamp = 0f
            return
        }
        equalise(frames)
        if (unmuteRamp < 1f) {
            // Back from a skipped gap: ~5 ms up from nothing, so it doesn't click in.
            val step = 1f / (sampleRate * 0.005f).coerceAtLeast(1f)
            for (f in 0 until frames) {
                unmuteRamp = (unmuteRamp + step).coerceAtMost(1f)
                for (c in 0 until channels) {
                    val data = channelData[c] ?: return
                    data[f * channelStride[c] + channelOffset[c]] *= unmuteRamp
                }
            }
        }
    }

    private fun isQuiet(frames: Int): Boolean {
        for (c in 0 until channels) {
            val data = channelData[c] ?: return false
            for (f in 0 until frames) {
                if (abs(data[f * channelStride[c] + channelOffset[c]]) > SILENCE_LEVEL) return false
            }
        }
        return true
    }

    private fun equalise(frames: Int) {
        val tuning = IosEqTarget.target
        val gain = targetGain
        if (isFlat(tuning, gain) && isSettled(tuning, gain)) {
            // Nothing to do, and nothing in flight: leave the samples as they came.
            if (!bypassed) {
                bypassed = true
                state.fill(0f)
                running.fill(false)
                limiter.reset()
            }
            snapToTarget()
            return
        }
        bypassed = false
        var start = 0
        while (start < frames) {
            val block = min(GLIDE_FRAMES, frames - start)
            glideTowards(tuning, gain)
            val active = prepareSections()
            prepareChannelGains()
            for (f in start until start + block) {
                var peak = 0f
                for (c in 0 until channels) {
                    val data = channelData[c] ?: return
                    var x = data[f * channelStride[c] + channelOffset[c]]
                    for (i in 0 until active) x = section(activeSlots[i], c, x)
                    x *= channelGain[c]
                    frame[c] = x
                    val magnitude = abs(x)
                    if (magnitude > peak) peak = magnitude
                }
                val reduction = limiter.gainFor(peak)
                for (c in 0 until channels) {
                    channelData[c]!![f * channelStride[c] + channelOffset[c]] = frame[c] * reduction
                }
            }
            flushDenormals(active)
            start += block
        }
    }

    /** Non-interleaved (one buffer per channel, AVFoundation's usual) or interleaved alike. */
    private fun mapChannels(list: AudioBufferList): Boolean {
        val count = list.mNumberBuffers.toInt()
        var channel = 0
        for (b in 0 until count) {
            val buffer = list.mBuffers[b]
            val data = buffer.mData?.reinterpret<FloatVar>() ?: return false
            val inBuffer = buffer.mNumberChannels.toInt().coerceAtLeast(1)
            for (offset in 0 until inBuffer) {
                if (channel >= channels) return true
                channelData[channel] = data
                channelStride[channel] = inBuffer
                channelOffset[channel] = offset
                channel++
            }
        }
        return channel == channels
    }

    private fun snapToTarget() {
        val tuning = IosEqTarget.target
        tuning.curve.gainsDb.copyInto(currentGainDb)
        tuning.curve.qs.copyInto(currentQ)
        currentPreampDb = tuning.curve.preampDb
        currentBalance = tuning.balance
        currentGain = targetGain
    }

    private fun glideTowards(tuning: EqTarget, gain: Float) {
        for (slot in 0 until EqLayout.SLOTS) {
            currentGainDb[slot] = linearGlide(currentGainDb[slot], tuning.curve.gainsDb[slot])
            currentQ[slot] = geometricGlide(currentQ[slot], tuning.curve.qs[slot])
        }
        currentPreampDb = linearGlide(currentPreampDb, tuning.curve.preampDb)
        currentBalance = linearGlide(currentBalance, tuning.balance)
        currentGain = linearGlide(currentGain, gain)
    }

    private fun linearGlide(current: Float, target: Float): Float = current + (target - current) * GLIDE_RATE

    private fun geometricGlide(current: Float, target: Float): Float {
        val from = ln(current.coerceAtLeast(MIN_Q))
        val to = ln(target.coerceAtLeast(MIN_Q))
        return exp(from + (to - from) * GLIDE_RATE)
    }

    private fun isFlat(tuning: EqTarget, gain: Float): Boolean =
        abs(gain - 1f) < SETTLED_GAIN &&
            abs(tuning.balance) < SETTLED_BALANCE &&
            abs(tuning.curve.preampDb) < SETTLED_DB &&
            tuning.curve.gainsDb.all { abs(it) < SETTLED_DB }

    private fun isSettled(tuning: EqTarget, gain: Float): Boolean {
        if (abs(currentGain - gain) >= SETTLED_GAIN) return false
        if (abs(currentBalance - tuning.balance) >= SETTLED_BALANCE) return false
        if (abs(currentPreampDb - tuning.curve.preampDb) >= SETTLED_DB) return false
        for (slot in 0 until EqLayout.SLOTS) {
            if (abs(currentGainDb[slot] - tuning.curve.gainsDb[slot]) >= SETTLED_DB) return false
        }
        return true
    }

    private fun prepareSections(): Int {
        var active = 0
        for (slot in 0 until EqLayout.SLOTS) {
            if (abs(currentGainDb[slot]) >= SETTLED_DB) {
                updateCoefficients(slot)
                activeSlots[active++] = slot
                running[slot] = true
            } else if (running[slot]) {
                clearState(slot)
                running[slot] = false
            }
        }
        return active
    }

    /** The phone's trapezoidal state-variable sections, coefficient for coefficient. */
    private fun updateCoefficients(slot: Int) {
        val spec = EqLayout.slots[slot]
        val a = 10f.pow(currentGainDb[slot] / 40f)
        val q = currentQ[slot].coerceAtLeast(MIN_Q)
        val hz = spec.frequencyHz.coerceIn(MIN_HZ, sampleRate * MAX_FREQUENCY_FRACTION)
        val base = tan(PI * hz / sampleRate).toFloat()
        val g: Float
        val k: Float
        when (spec.kind) {
            FilterKind.BELL -> {
                g = base
                k = 1f / (q * a)
                mixInput[slot] = 1f
                mixBand[slot] = k * (a * a - 1f)
                mixLow[slot] = 0f
            }
            FilterKind.LOW_SHELF -> {
                g = base / sqrt(a)
                k = 1f / q
                mixInput[slot] = 1f
                mixBand[slot] = k * (a - 1f)
                mixLow[slot] = a * a - 1f
            }
            FilterKind.HIGH_SHELF -> {
                g = base * sqrt(a)
                k = 1f / q
                mixInput[slot] = a * a
                mixBand[slot] = k * (1f - a) * a
                mixLow[slot] = 1f - a * a
            }
        }
        val d = 1f / (1f + g * (g + k))
        coeffA1[slot] = d
        coeffA2[slot] = g * d
        coeffA3[slot] = g * (g * d)
    }

    private fun prepareChannelGains() {
        val trim = 10f.pow(currentPreampDb / 20f) * currentGain
        if (channels == 2) {
            channelGain[0] = trim * min(1f, 1f - currentBalance)
            channelGain[1] = trim * min(1f, 1f + currentBalance)
        } else {
            channelGain.fill(trim)
        }
    }

    private fun section(slot: Int, channel: Int, input: Float): Float {
        val i = (channel * EqLayout.SLOTS + slot) * 2
        val ic1 = state[i]
        val ic2 = state[i + 1]
        val v3 = input - ic2
        val v1 = coeffA1[slot] * ic1 + coeffA2[slot] * v3
        val v2 = ic2 + coeffA2[slot] * ic1 + coeffA3[slot] * v3
        state[i] = 2f * v1 - ic1
        state[i + 1] = 2f * v2 - ic2
        return mixInput[slot] * input + mixBand[slot] * v1 + mixLow[slot] * v2
    }

    private fun clearState(slot: Int) {
        for (channel in 0 until channels) {
            val i = (channel * EqLayout.SLOTS + slot) * 2
            state[i] = 0f
            state[i + 1] = 0f
        }
    }

    private fun flushDenormals(active: Int) {
        for (index in 0 until active) {
            val slot = activeSlots[index]
            for (channel in 0 until channels) {
                val i = (channel * EqLayout.SLOTS + slot) * 2
                if (abs(state[i]) < DENORMAL_FLOOR) state[i] = 0f
                if (abs(state[i + 1]) < DENORMAL_FLOOR) state[i + 1] = 0f
            }
        }
    }

    private companion object {
        /** Skip silence's threshold, as on the phone: 1024 of 16-bit full scale. */
        const val SILENCE_LEVEL = 1024f / 32768f
        const val GLIDE_FRAMES = 64
        const val GLIDE_RATE = 0.08f
        const val SETTLED_DB = 0.01f
        const val SETTLED_BALANCE = 0.0005f
        const val SETTLED_GAIN = 0.001f
        const val MIN_Q = 0.05f
        const val MIN_HZ = 10f
        const val MAX_FREQUENCY_FRACTION = 0.45f
        const val DENORMAL_FLOOR = 1e-12f
    }
}

/**
 * Hangs a [TapDsp] on an [AVPlayerItem] through an MTAudioProcessingTap: the one place AVPlayer
 * lets an app see its decoded samples. Works for files and progressive streams, which is all this
 * app plays; AirPlay bypasses taps, so there the engine's volume levelling is what remains.
 */
@OptIn(ExperimentalForeignApi::class)
internal object IosAudioTap {

    /** Taps [item] once its audio track is known; [dsp] is told when it is running. */
    fun attach(item: AVPlayerItem, dsp: TapDsp) {
        val asset = item.asset as? AVURLAsset ?: return
        asset.loadValuesAsynchronouslyForKeys(listOf("tracks")) {
            dispatch_async(dispatch_get_main_queue()) {
                val track = asset.tracksWithMediaType(AVMediaTypeAudio).firstOrNull() as? AVAssetTrack
                    ?: return@dispatch_async
                install(item, track, dsp)
            }
        }
    }

    private fun install(item: AVPlayerItem, track: AVAssetTrack, dsp: TapDsp) {
        val ref = StableRef.create(dsp)
        val tap = memScoped {
            val callbacks = alloc<MTAudioProcessingTapCallbacks>()
            callbacks.version = kMTAudioProcessingTapCallbacksVersion_0
            callbacks.clientInfo = ref.asCPointer()
            callbacks.init = staticCFunction(::tapInit)
            callbacks.finalize = staticCFunction(::tapFinalize)
            callbacks.prepare = staticCFunction(::tapPrepare)
            callbacks.unprepare = null
            callbacks.process = staticCFunction(::tapProcess)
            val out = alloc<MTAudioProcessingTapRefVar>()
            val status = MTAudioProcessingTapCreate(
                kCFAllocatorDefault, callbacks.ptr, kMTAudioProcessingTapCreationFlag_PostEffects, out.ptr,
            )
            if (status != 0) null else out.value
        }
        if (tap == null) {
            ref.dispose()
            return
        }
        val parameters = AVMutableAudioMixInputParameters.audioMixInputParametersWithTrack(track)
        parameters.setAudioTapProcessor(tap)
        val mix = AVMutableAudioMix.audioMix()
        mix.setInputParameters(listOf(parameters))
        item.setAudioMix(mix)
        // The parameters hold the tap now; finalize disposes the reference when they let go.
        CFRelease(tap)
        dsp.attached = true
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun tapInit(tap: platform.MediaToolbox.MTAudioProcessingTapRef?, clientInfo: COpaquePointer?, storageOut: CPointer<COpaquePointerVar>?) {
    storageOut?.set(0, clientInfo)
}

@OptIn(ExperimentalForeignApi::class)
private fun tapFinalize(tap: platform.MediaToolbox.MTAudioProcessingTapRef?) {
    val storage = tap?.let(::MTAudioProcessingTapGetStorage) ?: return
    val ref = storage.asStableRef<TapDsp>()
    ref.get().attached = false
    ref.dispose()
}

@OptIn(ExperimentalForeignApi::class)
private fun tapPrepare(
    tap: platform.MediaToolbox.MTAudioProcessingTapRef?,
    maxFrames: platform.CoreMedia.CMItemCount,
    format: CPointer<AudioStreamBasicDescription>?,
) {
    val storage = tap?.let(::MTAudioProcessingTapGetStorage) ?: return
    val description = format?.pointed ?: return
    storage.asStableRef<TapDsp>().get().prepare(description)
}

@OptIn(ExperimentalForeignApi::class)
private fun tapProcess(
    tap: platform.MediaToolbox.MTAudioProcessingTapRef?,
    numberFrames: platform.CoreMedia.CMItemCount,
    flags: platform.MediaToolbox.MTAudioProcessingTapFlags,
    bufferList: CPointer<AudioBufferList>?,
    numberFramesOut: CPointer<platform.CoreMedia.CMItemCountVar>?,
    flagsOut: CPointer<platform.MediaToolbox.MTAudioProcessingTapFlagsVar>?,
) {
    if (tap == null || bufferList == null) return
    val status = MTAudioProcessingTapGetSourceAudio(tap, numberFrames, bufferList, flagsOut, null, numberFramesOut)
    if (status != 0) return
    val frames = numberFramesOut?.pointed?.value?.toInt() ?: return
    val storage = MTAudioProcessingTapGetStorage(tap) ?: return
    storage.asStableRef<TapDsp>().get().process(bufferList.pointed, frames)
}
