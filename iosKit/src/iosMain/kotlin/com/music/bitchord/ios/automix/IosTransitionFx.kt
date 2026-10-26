package com.music.bitchord.ios.automix

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.get
import kotlinx.cinterop.set
import kotlin.concurrent.Volatile
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.tan

/**
 * The filter and echo a track rides through an Automix transition: a low-pass that can close over
 * the outgoing track, a high-pass that can lift the low end out of one side of a blend, and the
 * feedback delay advanced Automix echoes the outgoing track out through. The phone's
 * `TransitionFilterProcessor`, on the audio tap's float channels.
 *
 * Aimed from the main thread ([aim], [setEcho], [parkEcho]); everything the audio thread needs is
 * allocated before it does. Parked — both filters open, no echo — it touches nothing.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosTransitionFx {
    @Volatile
    private var targetLowPassHz = OPEN_HZ

    @Volatile
    private var targetHighPassHz = OFF_HZ

    private var channels = 0
    private var sampleRate = 0

    private var currentLowPassHz = OPEN_HZ
    private var currentHighPassHz = OFF_HZ

    /** Two integrator states per second-order section, per channel. */
    private var lowState = FloatArray(0)
    private var highState = FloatArray(0)
    private val lowA1 = FloatArray(STAGES)
    private val lowA2 = FloatArray(STAGES)
    private val lowA3 = FloatArray(STAGES)
    private val highA1 = FloatArray(STAGES)
    private val highA2 = FloatArray(STAGES)
    private val highA3 = FloatArray(STAGES)
    private val highK = FloatArray(STAGES)
    private var filterStateDirty = false

    @Volatile private var echoEngaged = false
    @Volatile private var echoTargetSend = 0f
    @Volatile private var echoTargetDry = 1f
    @Volatile private var echoSeconds = 0f
    @Volatile private var echoGeneration = 0
    @Volatile private var pendingEchoRing: FloatArray? = null
    @Volatile private var formatRate = 0
    @Volatile private var formatChannels = 0
    private var echoRing = FloatArray(0)
    private var echoSeenGeneration = 0
    private var echoDelayFrames = 0
    private var echoPosition = 0
    private var echoSend = 0f
    private var echoDry = 1f
    private var echoLow = FloatArray(0)
    private var echoHigh = FloatArray(0)
    private var echoLowCoefficient = 0f
    private var echoHighCoefficient = 0f

    /** On the audio thread, as the tap learns the format. */
    fun configure(sampleRate: Int, channels: Int) {
        this.sampleRate = sampleRate
        this.channels = channels
        lowState = FloatArray(channels * STAGES * 2)
        highState = FloatArray(channels * STAGES * 2)
        currentLowPassHz = targetLowPassHz
        currentHighPassHz = targetHighPassHz
        echoLow = FloatArray(channels)
        echoHigh = FloatArray(channels)
        echoLowCoefficient = onePole(ECHO_LOW_PASS_HZ, sampleRate)
        echoHighCoefficient = onePole(ECHO_HIGH_PASS_HZ, sampleRate)
        formatRate = sampleRate
        formatChannels = channels
        echoSeenGeneration = echoGeneration - 1
    }

    /** Aims the filters. A low-pass at [OPEN_HZ] and a high-pass at [OFF_HZ] are off. */
    fun aim(lowPassHz: Float, highPassHz: Float) {
        targetLowPassHz = lowPassHz.coerceIn(MIN_HZ, OPEN_HZ)
        targetHighPassHz = highPassHz.coerceIn(OFF_HZ, MAX_HIGH_PASS_HZ)
    }

    fun open() = aim(OPEN_HZ, OFF_HZ)

    /** Engages the echo out, or moves it: [delaySeconds] between repeats, [send] into it, [dry] out. */
    fun setEcho(delaySeconds: Float, send: Float, dry: Float) {
        if (delaySeconds != echoSeconds || !echoEngaged) {
            val needed = (delaySeconds.coerceIn(0f, MAX_ECHO_SECONDS) * formatRate).toInt() * formatChannels
            if (needed > echoRing.size) pendingEchoRing = FloatArray(needed)
            echoSeconds = delaySeconds
            echoGeneration++
        }
        echoTargetSend = send.coerceIn(0f, 1f)
        echoTargetDry = dry.coerceIn(0f, 1f)
        echoEngaged = true
    }

    fun parkEcho() {
        echoEngaged = false
        echoTargetSend = 0f
        echoTargetDry = 1f
    }

    /** Back to doing nothing: the end of a transition, however it ended. */
    fun park() {
        open()
        parkEcho()
    }

    /** Runs over the tap's channels in place: [data]`[c][f * stride[c] + offset[c]]`. */
    fun process(data: Array<CPointer<FloatVar>?>, stride: IntArray, offset: IntArray, frames: Int) {
        if (frames <= 0 || channels < 1 || sampleRate <= 0) return
        filter(data, stride, offset, frames)
        if (echoEngaged) echo(data, stride, offset, frames)
    }

    private fun filter(data: Array<CPointer<FloatVar>?>, stride: IntArray, offset: IntArray, frames: Int) {
        val targetLow = targetLowPassHz
        val targetHigh = targetHighPassHz
        val parked = targetLow >= OPEN_HZ && targetHigh <= OFF_HZ &&
            currentLowPassHz >= OPEN_HZ - SETTLED_HZ && currentHighPassHz <= OFF_HZ + SETTLED_HZ
        if (parked) {
            if (filterStateDirty) {
                lowState.fill(0f)
                highState.fill(0f)
                filterStateDirty = false
            }
            return
        }
        filterStateDirty = true
        var start = 0
        while (start < frames) {
            val block = min(GLIDE_FRAMES, frames - start)
            currentLowPassHz = glide(currentLowPassHz, targetLow)
            currentHighPassHz = glide(currentHighPassHz, targetHigh)
            val lowOn = currentLowPassHz < OPEN_HZ - SETTLED_HZ
            val highOn = currentHighPassHz > OFF_HZ + SETTLED_HZ
            if (lowOn) coefficients(currentLowPassHz, lowA1, lowA2, lowA3, null)
            if (highOn) coefficients(currentHighPassHz, highA1, highA2, highA3, highK)
            for (c in 0 until channels) {
                val samples = data[c] ?: continue
                val s = stride[c]
                val o = offset[c]
                for (f in start until start + block) {
                    val i = f * s + o
                    var x = samples[i]
                    if (lowOn) x = lowPass(c, x)
                    if (highOn) x = highPass(c, x)
                    samples[i] = x
                }
            }
            start += block
        }
    }

    private fun echo(data: Array<CPointer<FloatVar>?>, stride: IntArray, offset: IntArray, frames: Int) {
        if (echoSeenGeneration != echoGeneration) startEcho()
        val targetSend = echoTargetSend
        val targetDry = echoTargetDry
        val sendStep = (targetSend - echoSend) / frames
        val dryStep = (targetDry - echoDry) / frames
        val delay = echoDelayFrames
        if (delay <= 0) {
            // No ring to run: honour the dry gain alone, so the track still leaves.
            for (c in 0 until channels) {
                val samples = data[c] ?: continue
                var dry = echoDry
                for (f in 0 until frames) {
                    dry += dryStep
                    samples[f * stride[c] + offset[c]] *= dry
                }
            }
            echoSend = targetSend
            echoDry = targetDry
            return
        }
        val ring = echoRing
        var position = echoPosition
        var send = echoSend
        var dry = echoDry
        for (f in 0 until frames) {
            send += sendStep
            dry += dryStep
            val ringBase = position * channels
            for (c in 0 until channels) {
                val samples = data[c] ?: continue
                val i = f * stride[c] + offset[c]
                val input = samples[i]
                val delayed = ring[ringBase + c]
                val darker = echoLow[c] + echoLowCoefficient * (delayed - echoLow[c])
                echoLow[c] = darker
                val rumble = echoHigh[c] + echoHighCoefficient * (darker - echoHigh[c])
                echoHigh[c] = rumble
                ring[ringBase + c] = input * send + (darker - rumble) * ECHO_FEEDBACK
                samples[i] = input * dry + delayed * ECHO_WET
            }
            if (++position == delay) position = 0
        }
        echoPosition = position
        echoSend = targetSend
        echoDry = targetDry
    }

    private fun startEcho() {
        echoSeenGeneration = echoGeneration
        pendingEchoRing?.let {
            echoRing = it
            pendingEchoRing = null
        }
        val perFrame = channels.coerceAtLeast(1)
        echoDelayFrames = (echoSeconds * sampleRate).toInt().coerceIn(0, echoRing.size / perFrame)
        echoRing.fill(0f, 0, echoDelayFrames * perFrame)
        echoLow.fill(0f)
        echoHigh.fill(0f)
        echoPosition = 0
        echoSend = 0f
        echoDry = 1f
    }

    private fun glide(current: Float, target: Float): Float {
        val from = ln(current.coerceAtLeast(MIN_HZ))
        val to = ln(target.coerceAtLeast(MIN_HZ))
        return exp(from + (to - from) * GLIDE_RATE)
    }

    private fun coefficients(hz: Float, a1: FloatArray, a2: FloatArray, a3: FloatArray, k: FloatArray?) {
        val g = tan(PI * hz.coerceIn(MIN_HZ, sampleRate * MAX_CUTOFF_FRACTION) / sampleRate).toFloat()
        for (stage in 0 until STAGES) {
            val damping = 1f / BUTTERWORTH_Q[stage]
            val first = 1f / (1f + g * (g + damping))
            a1[stage] = first
            a2[stage] = g * first
            a3[stage] = g * (g * first)
            k?.set(stage, damping)
        }
    }

    private fun lowPass(channel: Int, input: Float): Float {
        var value = input
        for (stage in 0 until STAGES) {
            val i = (channel * STAGES + stage) * 2
            val ic1 = lowState[i]
            val ic2 = lowState[i + 1]
            val v3 = value - ic2
            val v1 = lowA1[stage] * ic1 + lowA2[stage] * v3
            val v2 = ic2 + lowA2[stage] * ic1 + lowA3[stage] * v3
            lowState[i] = 2f * v1 - ic1
            lowState[i + 1] = 2f * v2 - ic2
            value = v2
        }
        return value
    }

    private fun highPass(channel: Int, input: Float): Float {
        var value = input
        for (stage in 0 until STAGES) {
            val i = (channel * STAGES + stage) * 2
            val ic1 = highState[i]
            val ic2 = highState[i + 1]
            val v3 = value - ic2
            val v1 = highA1[stage] * ic1 + highA2[stage] * v3
            val v2 = ic2 + highA2[stage] * ic1 + highA3[stage] * v3
            highState[i] = 2f * v1 - ic1
            highState[i + 1] = 2f * v2 - ic2
            value -= highK[stage] * v1 + v2
        }
        return value
    }

    companion object {
        const val OPEN_HZ = 20_000f
        const val OFF_HZ = 20f
        private const val MAX_HIGH_PASS_HZ = 2_000f
        private const val MIN_HZ = 10f
        private const val STAGES = 2
        private val BUTTERWORTH_Q = floatArrayOf(0.54120f, 1.30656f)
        private const val GLIDE_FRAMES = 64
        private const val GLIDE_RATE = 0.05f
        private const val SETTLED_HZ = 1f
        private const val MAX_CUTOFF_FRACTION = 0.45f
        private const val ECHO_FEEDBACK = 0.4f
        private const val ECHO_WET = 0.5f
        private const val ECHO_LOW_PASS_HZ = 3_500f
        private const val ECHO_HIGH_PASS_HZ = 250f
        private const val MAX_ECHO_SECONDS = 1f

        private fun onePole(hz: Float, sampleRate: Int): Float =
            if (sampleRate <= 0) 0f else (1.0 - exp(-2.0 * PI * hz / sampleRate)).toFloat()
    }
}
