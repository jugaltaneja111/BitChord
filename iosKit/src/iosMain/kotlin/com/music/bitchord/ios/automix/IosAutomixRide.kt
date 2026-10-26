package com.music.bitchord.ios.automix

import com.music.bitchord.playback.smart.FILTER_ECHO_AT
import com.music.bitchord.playback.smart.FilterTransitionVariant
import com.music.bitchord.playback.smart.TransitionPlan
import com.music.bitchord.playback.smart.TransitionStyle
import com.music.bitchord.playback.smart.echoTailSeconds
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where each fader and filter stands at one moment of a blend, and the echo out if there is one.
 * Cutoffs at [IosTransitionFx.OPEN_HZ] / [IosTransitionFx.OFF_HZ] are off.
 */
internal class RideMoves(
    val incomingGain: Float,
    val outgoingGain: Float,
    val incomingLowPassHz: Float,
    val incomingHighPassHz: Float,
    val outgoingLowPassHz: Float,
    val outgoingHighPassHz: Float,
    /** The outgoing echo's send and dry level, or null with no echo engaged yet. */
    val echoSend: Float?,
    val echoDry: Float,
)

/**
 * Advanced Automix's moves for one planned transition, fixed when it begins: the phone's
 * `CrossfadeController.Render` and its rides — the DJ fader curves, the filter sweep, the bass
 * swap on the beat, the vocal separation and the echo out — as pure functions of the blend's
 * progress, for the engine to apply to its two decks every tick.
 */
internal class IosAutomixRide(plan: TransitionPlan) {
    val style = plan.transitionStyle
    private val bassSwap = plan.bassSwap
    private val filterSweep = plan.filterSweep
    private val filterVariant = plan.filterVariant
    private val beats = if (plan.beatSeconds > 0) plan.fadeSeconds / plan.beatSeconds else 0.0
    private val grid = if (beats >= 2.0) beats else 0.0
    private val clash = plan.vocalOverlap.coerceIn(0.0, 1.0)

    /** Echo the outgoing track out rather than fading or filtering it away. Needs the grid it repeats on. */
    val echo = grid > 0 && plan.echoSeconds > 0.0
    val echoSeconds = plan.echoSeconds.toFloat()

    /** How long the repeats ring after the dry signal goes, in ms. */
    val echoTailMs = if (echo) (echoTailSeconds(plan.echoSeconds) * 1000).toLong() else 0L

    private val swapAt = onBeat(plan.bassSwapFraction.coerceIn(0.05, 0.95))
    private val swapWidth = if (grid > 0) SWAP_BEATS / grid else 2 * BASS_SWAP_WIDTH
    private val swapFrom = if (grid > 0) swapAt - swapWidth else swapAt - BASS_SWAP_WIDTH
    private val entryOpenBy = onBar(
        when (filterVariant) {
            FilterTransitionVariant.SWEEP -> ENTRY_OPEN_BY
            FilterTransitionVariant.BASS_HANDOFF -> 0.38
            FilterTransitionVariant.ECHO_RIDE -> 0.52
        },
    )
    private val vocalEntryOpenBy = onBar(entryOpenBy + (VOCAL_SAFE_ENTRY_OPEN_BY - entryOpenBy) * clash)
    private val blendEntryOpenBy = onBar(BLEND_ENTRY_OPEN_BY + (BLEND_ENTRY_CLASH_OPEN_BY - BLEND_ENTRY_OPEN_BY) * clash)
    private val blendExitFrom = onBar(BLEND_EXIT_FROM + (BLEND_EXIT_CLASH_FROM - BLEND_EXIT_FROM) * clash)
    private val filterSwapAt = onBar(
        when (filterVariant) {
            FilterTransitionVariant.SWEEP -> FILTER_BASS_SWAP_AT
            FilterTransitionVariant.BASS_HANDOFF -> 0.43
            FilterTransitionVariant.ECHO_RIDE -> 0.58
        },
    )
    private val filterSwapAtF = filterSwapAt.toFloat()
    private val echoAt = onBar(FILTER_ECHO_AT)
    private val echoFrom = if (grid > 0) echoAt - 1.0 / grid else echoAt
    private val echoKill = if (grid > 0) ECHO_KILL_BEATS / grid else 0.0
    private val holdFaders = (1.0 - clash).toFloat()
    private val swapAtF = swapAt.toFloat()

    /**
     * Whether this is doing something a plain crossfade could not, which is what lights the
     * scrubber up: a style that filters or swaps bass, or an echo out.
     */
    val isRealMix: Boolean =
        style == TransitionStyle.DJ_BLEND || style == TransitionStyle.DJ_FILTER || echo || clash > 0.0

    private var inLow = OPEN
    private var inHigh = OFF
    private var outLow = OPEN
    private var outHigh = OFF
    private var send: Float? = null
    private var dry = 1f

    /** The moves at [progress] (0..1) through the blend. */
    fun at(progress: Float): RideMoves {
        val p = progress.coerceIn(0f, 1f)
        inLow = OPEN; inHigh = OFF; outLow = OPEN; outHigh = OFF
        send = null; dry = 1f
        rideStyle(p)
        val rise = mixRise(p)
        val fall = mixFall(p)
        // Two faders at full sum past full scale: trimmed back together, as the phone's headroom does.
        // An echo out's fader stays up for the repeats; what is heard of it is its dry level.
        val sum = rise + if (echo) dry else fall
        val headroom = if (sum <= 1f) 1f else 1f / sqrt(sum)
        return RideMoves(rise * headroom, fall * headroom, inLow, inHigh, outLow, outHigh, send, dry)
    }

    private fun rideStyle(progress: Float) {
        when (style) {
            TransitionStyle.DJ_FILTER -> when (filterVariant) {
                FilterTransitionVariant.BASS_HANDOFF -> rideFallbackBassHandoff(progress)
                FilterTransitionVariant.SWEEP, FilterTransitionVariant.ECHO_RIDE -> rideFilterSweep(progress)
            }
            TransitionStyle.DJ_BLEND -> if (bassSwap) rideBassSwap(progress) else rideVocalSeparation(progress)
            // An album played through: any filtering would be an edit the record didn't ask for.
            TransitionStyle.GAPLESS -> Unit
            TransitionStyle.EQUAL_POWER -> rideVocalSeparation(progress)
        }
        if (echo) rideEchoOut(progress)
    }

    private fun rideEchoOut(progress: Float) {
        val p = progress.toDouble()
        val handover = ((p - (echoAt - swapWidth)) / swapWidth).coerceIn(0.0, 1.0)
        outLow = OPEN
        outHigh = bassCutoff(handover)
        if (p < echoFrom) return
        send = if (p >= echoAt) 0f else ((p - echoFrom) / (echoAt - echoFrom)).toFloat()
        dry = (1.0 - ((p - echoAt) / echoKill).coerceIn(0.0, 1.0)).toFloat()
    }

    private fun rideVocalSeparation(progress: Float) {
        val amount = clash
        if (amount <= 0.0) return
        val floor = glide(OPEN_D, VOCAL_SEPARATION_FLOOR_HZ, amount)
        outLow = glide(OPEN_D, floor, progress.toDouble().pow(FILTER_SWEEP_SHAPE)).toFloat()
        inHigh = entryHighPass(progress, amount, VOCAL_SEPARATION_HIGH_PASS_HZ, ENTRY_OPEN_BY)
    }

    private fun rideFilterSweep(progress: Float) {
        val sweep = filterSweep.coerceIn(0.0, 1.0)
        if (sweep <= 0.0) return
        val entry = glide(OPEN_D, FILTER_ENTRY_HZ, sweep)
        val floor = glide(OPEN_D, FILTER_FLOOR_HZ, sweep)
        val cutoff = glide(entry, floor, progress.toDouble().pow(FILTER_SWEEP_SHAPE))
        val entryCorner = entryHighPass(
            progress,
            sweep,
            glide(ENTRY_HIGH_PASS_HZ, VOCAL_SAFE_ENTRY_HIGH_PASS_HZ, clash),
            vocalEntryOpenBy,
        )
        // Two tempi that don't match are two kicks that flam: the low end changes hands once, on a beat.
        val handover = ((progress - (filterSwapAt - swapWidth)) / swapWidth).coerceIn(0.0, 1.0)
        outLow = cutoff.toFloat()
        outHigh = bassCutoff(handover)
        inHigh = maxOf(entryCorner, bassCutoff(1.0 - handover))
    }

    private fun rideFallbackBassHandoff(progress: Float) {
        val p = progress.toDouble()
        val handover = ((p - (filterSwapAt - swapWidth)) / swapWidth).coerceIn(0.0, 1.0)
        val exitAmount = ((p - filterSwapAt) / (1.0 - filterSwapAt)).coerceIn(0.0, 1.0)
        val handoffTone = glide(OPEN_D, FALLBACK_HANDOFF_FLOOR_HZ, exitAmount.pow(FALLBACK_HANDOFF_SHAPE))
        val vocalTone = glide(OPEN_D, VOCAL_SEPARATION_FLOOR_HZ, clash * p.pow(VOCAL_SAFE_EXIT_SHAPE))
        val incomingEntry = entryHighPass(
            progress,
            1.0,
            glide(FALLBACK_HANDOFF_ENTRY_HZ, VOCAL_SAFE_ENTRY_HIGH_PASS_HZ, clash),
            vocalEntryOpenBy,
        )
        outLow = minOf(handoffTone, vocalTone).toFloat()
        outHigh = bassCutoff(handover)
        inHigh = maxOf(incomingEntry, bassCutoff(1.0 - handover))
    }

    private fun rideBassSwap(progress: Float) {
        val handover = ((progress - swapFrom) / swapWidth).coerceIn(0.0, 1.0)
        inHigh = maxOf(
            bassCutoff(1.0 - handover),
            entryHighPass(progress, 1.0, glide(BLEND_ENTRY_HIGH_PASS_HZ, BLEND_ENTRY_CLASH_HIGH_PASS_HZ, clash), blendEntryOpenBy),
        )
        val amount = ((progress - blendExitFrom) / (1.0 - blendExitFrom)).coerceIn(0.0, 1.0)
        outLow = glide(OPEN_D, glide(BLEND_EXIT_LOW_PASS_HZ, BLEND_EXIT_CLASH_LOW_PASS_HZ, clash), amount).toFloat()
        outHigh = bassCutoff(handover)
    }

    private fun entryHighPass(progress: Float, amount: Double, topHz: Double, openBy: Double): Float {
        val remaining = (1.0 - progress / openBy).coerceIn(0.0, 1.0)
        return glide(OFF_D, topHz, amount * remaining.pow(ENTRY_SHAPE)).toFloat()
    }

    private fun glide(from: Double, to: Double, amount: Double): Double =
        from * (to / from).pow(amount.coerceIn(0.0, 1.0))

    private fun bassCutoff(amount: Double): Float = glide(OFF_D, BASS_SWAP_HZ, amount).toFloat()

    private fun riseGain(progress: Float): Float = sin(progress.coerceIn(0f, 1f) * PI.toFloat() / 2f)

    private fun fallGain(progress: Float): Float = cos(progress.coerceIn(0f, 1f) * PI.toFloat() / 2f)

    private fun mixRise(progress: Float): Float {
        val styled = when (style) {
            TransitionStyle.DJ_BLEND ->
                if (bassSwap) held(riseGain(progress), riseGain(progress / swapAtF)) else riseGain(progress)
            TransitionStyle.DJ_FILTER -> when (filterVariant) {
                FilterTransitionVariant.BASS_HANDOFF -> riseGain(progress / filterSwapAtF)
                FilterTransitionVariant.ECHO_RIDE -> riseGain(progress.pow(ECHO_RIDE_RISE_SHAPE))
                FilterTransitionVariant.SWEEP -> riseGain(progress.pow(FILTER_RISE_SHAPE))
            }
            else -> riseGain(progress)
        }
        val vocalSafe = riseGain(progress.pow(VOCAL_SAFE_RISE_SHAPE))
        return styled + (vocalSafe - styled) * clash.toFloat()
    }

    private fun mixFall(progress: Float): Float {
        // The fader carries the repeats too: an echo out leaves it up and fades the dry signal instead.
        if (echo) return 1f
        val styled = when (style) {
            TransitionStyle.DJ_BLEND -> if (!bassSwap) {
                fallGain(progress)
            } else {
                held(fallGain(progress), if (progress <= swapAtF) 1f else fallGain((progress - swapAtF) / (1f - swapAtF)))
            }
            TransitionStyle.DJ_FILTER -> when (filterVariant) {
                FilterTransitionVariant.BASS_HANDOFF ->
                    if (progress <= filterSwapAtF) 1f else fallGain((progress - filterSwapAtF) / (1f - filterSwapAtF))
                FilterTransitionVariant.ECHO_RIDE -> fallGain(progress.pow(ECHO_RIDE_FALL_SHAPE))
                FilterTransitionVariant.SWEEP -> fallGain(progress.pow(FILTER_FALL_SHAPE))
            }
            else -> fallGain(progress)
        }
        val vocalSafe = fallGain(progress.pow(VOCAL_SAFE_FALL_SHAPE))
        return styled + (vocalSafe - styled) * clash.toFloat()
    }

    private fun held(plain: Float, dj: Float): Float = plain + (dj - plain) * holdFaders

    private fun onBeat(fraction: Double): Double =
        if (grid > 0) (round(fraction * grid) / grid).coerceIn(1.0 / grid, 1.0 - 1.0 / grid) else fraction

    private fun onBar(fraction: Double): Double =
        if (grid >= 8.0) (round(fraction * grid / 4.0) * 4.0 / grid).coerceIn(4.0 / grid, 1.0 - 4.0 / grid) else onBeat(fraction)

    private companion object {
        const val OPEN = IosTransitionFx.OPEN_HZ
        const val OFF = IosTransitionFx.OFF_HZ
        const val OPEN_D = IosTransitionFx.OPEN_HZ.toDouble()
        const val OFF_D = IosTransitionFx.OFF_HZ.toDouble()
        const val FILTER_ENTRY_HZ = 7_000.0
        const val FILTER_FLOOR_HZ = 300.0
        const val BASS_SWAP_HZ = 200.0
        const val BASS_SWAP_WIDTH = 0.10
        const val SWAP_BEATS = 0.5
        const val FILTER_BASS_SWAP_AT = 0.5
        const val FILTER_FALL_SHAPE = 1.6f
        const val FILTER_RISE_SHAPE = 0.8f
        const val FALLBACK_HANDOFF_ENTRY_HZ = 900.0
        const val FALLBACK_HANDOFF_FLOOR_HZ = 1_600.0
        const val FALLBACK_HANDOFF_SHAPE = 0.8
        const val VOCAL_SAFE_ENTRY_HIGH_PASS_HZ = 2_400.0
        const val VOCAL_SAFE_ENTRY_OPEN_BY = 0.78
        const val VOCAL_SAFE_EXIT_SHAPE = 0.55
        const val VOCAL_SAFE_RISE_SHAPE = 1.65f
        const val VOCAL_SAFE_FALL_SHAPE = 0.65f
        const val ECHO_RIDE_RISE_SHAPE = 0.7f
        const val ECHO_RIDE_FALL_SHAPE = 1.35f
        const val ECHO_KILL_BEATS = 0.25
        const val FILTER_SWEEP_SHAPE = 0.75
        const val ENTRY_HIGH_PASS_HZ = 1_200.0
        const val ENTRY_OPEN_BY = 0.6
        const val ENTRY_SHAPE = 0.35
        const val VOCAL_SEPARATION_FLOOR_HZ = 1_600.0
        const val VOCAL_SEPARATION_HIGH_PASS_HZ = 700.0
        const val BLEND_ENTRY_HIGH_PASS_HZ = 520.0
        const val BLEND_ENTRY_OPEN_BY = 0.45
        const val BLEND_ENTRY_CLASH_HIGH_PASS_HZ = 950.0
        const val BLEND_ENTRY_CLASH_OPEN_BY = 0.7
        const val BLEND_EXIT_CLASH_FROM = 0.12
        const val BLEND_EXIT_CLASH_LOW_PASS_HZ = 1_100.0
        const val BLEND_EXIT_FROM = 0.3
        const val BLEND_EXIT_LOW_PASS_HZ = 2_200.0
    }
}
