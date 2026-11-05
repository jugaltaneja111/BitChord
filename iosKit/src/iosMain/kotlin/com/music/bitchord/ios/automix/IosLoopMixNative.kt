@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.music.bitchord.ios.automix

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.DoubleVar
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.invoke
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.Foundation.NSBundle
import platform.posix.RTLD_DEFAULT
import platform.posix.RTLD_NOW
import platform.posix.dlerror
import platform.posix.dlopen
import platform.posix.dlsym
import kotlin.concurrent.AtomicReference

/**
 * The Automix loop mix's renderer (`native/loopmix/`), as the iOS app carries it:
 * BitChordLoopMix.framework, the C++ Android builds as libbitchord_loopmix, with the C bridge in
 * `native/loopmix/ios/bitchord_loopmix_ios.cpp` in place of its JNI one.
 *
 * Bound at run time, as [IosAnalyzerNative] is; the signatures below restate the bridge's and are
 * checked against its ABI version. A build without the framework still runs: [loadError] says
 * why, and every pair gets the blend instead of a loop mix.
 */
internal object IosLoopMixNative {
    private const val FRAMEWORK = "BitChordLoopMix"

    /** The bridge's kAbiVersion this side was written against. */
    private const val ABI_VERSION = 1

    internal class Bound(
        val create: CPointer<CFunction<(Int) -> COpaquePointer?>>,
        val destroy: CPointer<CFunction<(COpaquePointer?) -> Unit>>,
        val setDeck: CPointer<
            CFunction<
                (
                    COpaquePointer?, Int, CPointer<FloatVar>?, CPointer<FloatVar>?, Long, Double, Long, Long, Double,
                    CPointer<DoubleVar>?, CPointer<DoubleVar>?, Int, Int, Double, Double, Float,
                ) -> Unit,
                >,
            >,
        val setEnvelope: CPointer<CFunction<(COpaquePointer?, Int, Int, CPointer<DoubleVar>?, CPointer<FloatVar>?, Int, Int) -> Unit>>,
        val prepare: CPointer<CFunction<(COpaquePointer?) -> Unit>>,
        val firstFrame: CPointer<CFunction<(COpaquePointer?) -> Long>>,
        val renderPlanar: CPointer<CFunction<(COpaquePointer?, Long, CPointer<FloatVar>?, CPointer<FloatVar>?, Int) -> Unit>>,
        val fadeOut: CPointer<CFunction<(COpaquePointer?, Int, Double) -> Unit>>,
        val heard: CPointer<CFunction<(COpaquePointer?, Int) -> Double>>,
    )

    private val bound: Result<Bound> by lazy {
        runCatching {
            val handle = dlopen("@rpath/$FRAMEWORK.framework/$FRAMEWORK", RTLD_NOW)
                ?: NSBundle.mainBundle.privateFrameworksPath?.let { dlopen("$it/$FRAMEWORK.framework/$FRAMEWORK", RTLD_NOW) }
            val loaderError = if (handle == null) dlerror()?.toKString() else null
            fun sym(name: String): COpaquePointer =
                handle?.let { dlsym(it, name) } ?: dlsym(RTLD_DEFAULT, name)
                    ?: error("$FRAMEWORK isn't in this build (${loaderError ?: "$name missing"})")
            val abi = sym("blm_abi").reinterpret<CFunction<() -> Int>>()()
            check(abi == ABI_VERSION) { "$FRAMEWORK's bridge is version $abi, not the $ABI_VERSION this app was built for" }
            Bound(
                create = sym("blm_create").reinterpret(),
                destroy = sym("blm_destroy").reinterpret(),
                setDeck = sym("blm_set_deck").reinterpret(),
                setEnvelope = sym("blm_set_envelope").reinterpret(),
                prepare = sym("blm_prepare").reinterpret(),
                firstFrame = sym("blm_first_frame").reinterpret(),
                renderPlanar = sym("blm_render_planar").reinterpret(),
                fadeOut = sym("blm_fade_out").reinterpret(),
                heard = sym("blm_heard").reinterpret(),
            )
        }
    }

    /** Null when the renderer is loaded and is the version this side expects. */
    val loadError: String? get() = bound.exceptionOrNull()?.let { it.message ?: "the loop mix renderer failed to load" }

    val available: Boolean get() = bound.isSuccess

    internal fun bound(): Bound? = bound.getOrNull()
}

/**
 * One native renderer: two Signalsmith Stretch decks, each a decoded region of one song, looped on
 * whole bars and following the mix's tempo ramp beat by beat, mixed to one stereo stream. Upstream's
 * `LoopMixRenderer`, on [IosLoopMixNative].
 *
 * Built and filled on a worker thread; after [prepare] only [renderPlanar] (the feeder thread) and
 * the small setters touch it. [close] must not race a render: the feeder is stopped first.
 */
internal class IosLoopMixRenderer(val sampleRate: Int) {
    private val native = IosLoopMixNative.bound()
    private val handle = AtomicReference<COpaquePointer?>(native?.create?.invoke(sampleRate))

    val isValid: Boolean get() = handle.value != null

    /** One deck: the region (planar, at [sampleRate]), its loop, and its beat knots. */
    class DeckSetup(
        val left: FloatArray,
        val right: FloatArray,
        /** Track seconds of sample 0. */
        val regionStart: Double,
        /** Loop bounds in region samples. */
        val loopStart: Long,
        val loopEnd: Long,
        /** Unwrapped region-sample position at which the loop lets go; huge for never. */
        val releaseAt: Double,
        /** Mix frames (0 = t0) and the unwrapped region-sample position heard at each. */
        val knotFrames: DoubleArray,
        val knotPositions: DoubleArray,
        /** After the last knot: carry on at rate 1 (true) or stop. */
        val runOn: Boolean,
        /** Mix seconds after which the deck renders nothing. */
        val stopAt: Double,
        val preroll: Double,
        val level: Float,
    )

    fun setDeck(index: Int, deck: DeckSetup) {
        val native = native ?: return
        val h = handle.value ?: return
        val frames = minOf(deck.left.size, deck.right.size)
        val knots = minOf(deck.knotFrames.size, deck.knotPositions.size)
        if (frames == 0) return
        deck.left.usePinned { l ->
            deck.right.usePinned { r ->
                deck.knotFrames.usePinned { kf ->
                    deck.knotPositions.usePinned { kp ->
                        native.setDeck(
                            h, index, l.addressOf(0), r.addressOf(0), frames.toLong(), deck.regionStart,
                            deck.loopStart, deck.loopEnd, deck.releaseAt,
                            if (knots > 0) kf.addressOf(0) else null, if (knots > 0) kp.addressOf(0) else null, knots,
                            if (deck.runOn) 1 else 0, deck.stopAt, deck.preroll, deck.level,
                        )
                    }
                }
            }
        }
    }

    /** Automation over mix seconds: [KIND_GAIN] (linear), [KIND_LOW_DB] (250 Hz shelf), [KIND_HIGH_PASS_HZ]. */
    fun setEnvelope(index: Int, kind: Int, times: DoubleArray, values: FloatArray, exponential: Boolean = false) {
        val native = native ?: return
        val h = handle.value ?: return
        val points = minOf(times.size, values.size)
        if (points == 0) return
        times.usePinned { t ->
            values.usePinned { v ->
                native.setEnvelope(h, index, kind, t.addressOf(0), v.addressOf(0), points, if (exponential) 1 else 0)
            }
        }
    }

    fun prepare() {
        val h = handle.value ?: return
        native?.prepare?.invoke(h)
    }

    /** The earliest mix frame anything renders at; negative, before t0. */
    fun firstFrame(): Long = handle.value?.let { native?.firstFrame?.invoke(it) } ?: 0L

    /** Renders [frames] stereo frames from mix frame [frame] into two planes. Frames in order only. */
    fun renderPlanar(frame: Long, left: CPointer<FloatVar>, right: CPointer<FloatVar>, frames: Int) {
        val h = handle.value ?: return
        native?.renderPlanar?.invoke(h, frame, left, right, frames)
    }

    /** As [renderPlanar], into Kotlin arrays (tests and the on-device check). */
    fun render(frame: Long, left: FloatArray, right: FloatArray, frames: Int) {
        left.usePinned { l -> right.usePinned { r -> renderPlanar(frame, l.addressOf(0), r.addressOf(0), frames) } }
    }

    /** Fade [index] out over 25 ms once it plays track second [trackSeconds] after its release. */
    fun setFadeOut(index: Int, trackSeconds: Double) {
        val h = handle.value ?: return
        native?.fadeOut?.invoke(h, index, trackSeconds)
    }

    /** Track seconds [index] played at the end of the last rendered block. */
    fun heard(index: Int): Double = handle.value?.let { native?.heard?.invoke(it, index) } ?: 0.0

    fun close() {
        val h = handle.getAndSet(null) ?: return
        native?.destroy?.invoke(h)
    }

    companion object {
        const val OUT = 0
        const val IN = 1
        const val KIND_GAIN = 0
        const val KIND_LOW_DB = 1
        const val KIND_HIGH_PASS_HZ = 2
    }
}
