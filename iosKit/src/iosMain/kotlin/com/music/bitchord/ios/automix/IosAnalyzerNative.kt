@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.music.bitchord.ios.automix

import com.music.bitchord.playback.smart.EnergySample
import com.music.bitchord.playback.smart.MixCandidate
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.VocalModel
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.DoubleVar
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import platform.Foundation.NSBundle
import platform.posix.RTLD_DEFAULT
import platform.posix.RTLD_NOW
import platform.posix.dlerror
import platform.posix.dlopen
import platform.posix.dlsym

/**
 * The native DSP analyzer (`native/analyzer/`), as the iOS app carries it: BitChordAnalysis.framework,
 * built from the same sources Android and the desktop compile, plus the C bridge in
 * `native/analyzer/ios/bitchord_analysis_ios.cpp` in place of their JNI one.
 *
 * Bound at run time, the way [com.music.bitchord.ios.ffmpeg.Av] binds FFmpeg, and for the same
 * reason: Kotlin/Native can't run cinterop for Apple targets on the Linux host that compiles iosKit
 * in CI. The signatures below restate the bridge's and are checked against its ABI version.
 *
 * A build without the framework still runs; [loadError] says why, and Automix degrades to plain
 * crossfades, which is what "no evidence" means to the policy.
 */
internal object IosAnalyzerNative {
    private const val FRAMEWORK = "BitChordAnalysis"

    /** The bridge's kAbiVersion this side was written against (2: the models' front ends). */
    private const val ABI_VERSION = 2

    /** The mel front end's shape, which Beat This! was trained on. */
    const val MEL_BANDS = 128
    const val MEL_RATE = 22_050.0

    private class Bound(
        val run: CPointer<CFunction<(CPointer<FloatVar>?, Long, Double, Double) -> CPointer<ByteVar>?>>,
        val free: CPointer<CFunction<(CPointer<ByteVar>?) -> Unit>>,
        val sampleRate: Double,
        val mel: CPointer<CFunction<(CPointer<FloatVar>?, Long, Double, CPointer<LongVar>?) -> CPointer<FloatVar>?>>,
        val vocal: CPointer<CFunction<(CPointer<FloatVar>?, CPointer<FloatVar>?, Long, Double, CPointer<LongVar>?) -> CPointer<FloatVar>?>>,
        val resample: CPointer<CFunction<(CPointer<FloatVar>?, Long, Double, Double, CPointer<LongVar>?) -> CPointer<FloatVar>?>>,
        val bufferFree: CPointer<CFunction<(CPointer<FloatVar>?) -> Unit>>,
    )

    private val bound: Result<Bound> by lazy {
        runCatching {
            val handle = dlopen("@rpath/$FRAMEWORK.framework/$FRAMEWORK", RTLD_NOW)
                ?: NSBundle.mainBundle.privateFrameworksPath?.let { dlopen("$it/$FRAMEWORK.framework/$FRAMEWORK", RTLD_NOW) }
            val loaderError = if (handle == null) dlerror()?.toKString() else null
            fun sym(name: String): COpaquePointer =
                handle?.let { dlsym(it, name) } ?: dlsym(RTLD_DEFAULT, name)
                    ?: error("$FRAMEWORK isn't in this build (${loaderError ?: "$name missing"})")
            val abi = sym("bitchord_analysis_abi_version").reinterpret<CFunction<() -> Int>>()()
            check(abi == ABI_VERSION) { "$FRAMEWORK's bridge is version $abi, not the $ABI_VERSION this app was built for" }
            // The front ends' shapes are the models' contract: checked, not assumed.
            val shape = memScoped {
                val out = allocArray<DoubleVar>(7)
                sym("bitchord_frontend_shape").reinterpret<CFunction<(CPointer<DoubleVar>?) -> Unit>>()(out)
                DoubleArray(7) { out[it] }
            }
            val expected = doubleArrayOf(
                MEL_BANDS.toDouble(), MEL_RATE, 441.0,
                VocalModel.BINS.toDouble(), VocalModel.SAMPLE_RATE, VocalModel.HOP.toDouble(), VocalModel.FFT_SIZE.toDouble(),
            )
            check(shape.contentEquals(expected)) { "$FRAMEWORK's front ends are ${shape.toList()}, not ${expected.toList()}" }
            Bound(
                run = sym("bitchord_analysis_run").reinterpret(),
                free = sym("bitchord_analysis_free").reinterpret(),
                sampleRate = sym("bitchord_analysis_sample_rate").reinterpret<CFunction<() -> Double>>()(),
                mel = sym("bitchord_mel_compute").reinterpret(),
                vocal = sym("bitchord_vocal_compute").reinterpret(),
                resample = sym("bitchord_resample").reinterpret(),
                bufferFree = sym("bitchord_buffer_free").reinterpret(),
            )
        }
    }

    /** Null when the analyzer is loaded and is the version this side expects. */
    val loadError: String? get() = bound.exceptionOrNull()?.let { it.message ?: "the analyzer failed to load" }

    /** The rate the analyzer's windows assume (TrackFeatures.sampleRate). */
    val sampleRate: Double get() = bound.getOrNull()?.sampleRate ?: 11_025.0

    /**
     * Analyses the first [count] of [samples], mono float at [sampleRate] covering [durationSeconds],
     * into the bridge's JSON; null when the analyzer is missing or declined the input. Blocking and
     * O(n): a worker thread's.
     */
    fun analyze(samples: FloatArray, count: Int, durationSeconds: Double): String? {
        val native = bound.getOrNull() ?: return null
        if (count <= 0 || count > samples.size) return null
        return samples.usePinned { pinned ->
            val json = native.run(pinned.addressOf(0), count.toLong(), native.sampleRate, durationSeconds) ?: return@usePinned null
            try {
                json.toKString()
            } finally {
                native.free(json)
            }
        }
    }

    /** A native float buffer of [length], copied out and released. */
    private fun Bound.take(buffer: CPointer<FloatVar>?, length: Long): FloatArray? {
        buffer ?: return null
        try {
            if (length <= 0 || length > Int.MAX_VALUE / 4) return null
            val out = FloatArray(length.toInt())
            out.usePinned { pinned ->
                platform.posix.memcpy(pinned.addressOf(0), buffer, (length * 4).toULong())
            }
            return out
        } finally {
            bufferFree(buffer)
        }
    }

    /**
     * Beat This!'s log-mel input for the first [count] of [samples], mono at [MEL_RATE]: frames ×
     * [MEL_BANDS], frame-major, with the frame count; null when the input is unusable.
     */
    fun melSpectrogram(samples: FloatArray, count: Int): Pair<FloatArray, Int>? {
        val native = bound.getOrNull() ?: return null
        if (count <= 0 || count > samples.size) return null
        return memScoped {
            val frames = alloc<LongVar>()
            val buffer = samples.usePinned { native.mel(it.addressOf(0), count.toLong(), MEL_RATE, frames.ptr) }
            native.take(buffer, frames.value * MEL_BANDS)?.let { it to frames.value.toInt() }
        }
    }

    /** open-unmix's `[2, bins, frames]` input for [count] samples of planar stereo at 44.1 kHz, and the frames. */
    fun vocalSpectrogram(left: FloatArray, right: FloatArray, count: Int): Pair<FloatArray, Int>? {
        val native = bound.getOrNull() ?: return null
        if (count <= 0 || count > left.size || count > right.size) return null
        return memScoped {
            val frames = alloc<LongVar>()
            val buffer = left.usePinned { l ->
                right.usePinned { r -> native.vocal(l.addressOf(0), r.addressOf(0), count.toLong(), VocalModel.SAMPLE_RATE, frames.ptr) }
            }
            native.take(buffer, frames.value * VocalModel.CHANNELS * VocalModel.BINS)?.let { it to frames.value.toInt() }
        }
    }

    /** The first [count] of [samples] at [outputRate], windowed-sinc; null when it can't be. */
    fun resample(samples: FloatArray, count: Int, inputRate: Double, outputRate: Double): FloatArray? {
        val native = bound.getOrNull() ?: return null
        if (count <= 0 || count > samples.size) return null
        return memScoped {
            val length = alloc<LongVar>()
            val buffer = samples.usePinned { native.resample(it.addressOf(0), count.toLong(), inputRate, outputRate, length.ptr) }
            native.take(buffer, length.value)
        }
    }
}

/**
 * The bridge's JSON as a [TrackAnalysis]: TrackFeatures.parse (jvmSharedMain) on the commonMain
 * types, field for field — the DSP pass's numbers, which the model pass
 * ([IosAutomixAnalysis], [IosModels]) then improves where the models find something.
 */
internal object IosTrackFeatures {

    fun parse(json: String, trackId: String, durationSeconds: Double): TrackAnalysis {
        val root = Json.parseToJsonElement(json).jsonObject
        return TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = trackId,
            duration = durationSeconds,
            bpm = root.number("bpm"),
            beatInterval = root.number("beatInterval"),
            beatConfidence = root.number("beatConfidence"),
            downbeats = root.doubles("downbeats"),
            phraseBoundaries = root.doubles("phraseBoundaries"),
            firstBeat = root.number("firstBeat"),
            key = root.text("key"),
            keyConfidence = root.number("keyConfidence"),
            audibleStartTime = root.number("audibleStartTime"),
            pickupTime = root.number("pickupTime"),
            introEndTime = root.number("introEndTime"),
            contentEndTime = root.number("contentEndTime"),
            outroStartTime = root.number("outroStartTime"),
            mixInTime = root.number("mixInTime"),
            mixOutTime = root.number("mixOutTime"),
            mixInCandidates = root.cuePoints("mixInCandidates"),
            mixOutCandidates = root.cuePoints("mixOutCandidates"),
            energyCurve = root.energyCurve("energyCurve"),
            lowEnergyCurve = root.energyCurve("lowEnergyCurve"),
            vocalActivityMask = root.doubles("vocalActivityMask"),
            vocalProbability = root.number("vocalProbability"),
        )
    }

    private fun JsonObject.number(name: String): Double =
        (this[name]?.jsonPrimitive?.doubleOrNull ?: 0.0).takeIf { it.isFinite() } ?: 0.0

    private fun JsonObject.text(name: String): String =
        this[name]?.jsonPrimitive?.contentOrNull ?: ""

    private fun JsonObject.array(name: String): JsonArray? =
        runCatching { this[name]?.jsonArray }.getOrNull()

    private fun JsonObject.doubles(name: String): List<Double> {
        val array = array(name) ?: return emptyList()
        return array.mapNotNull { it.jsonPrimitive.doubleOrNull?.takeIf(Double::isFinite) }
    }

    private fun JsonObject.energyCurve(name: String): List<EnergySample> {
        val array = array(name) ?: return emptyList()
        return array.mapNotNull { element ->
            val point = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
            val time = point["t"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val energy = point["e"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            if (time.isFinite() && energy.isFinite()) EnergySample(time, energy) else null
        }
    }

    private fun JsonObject.cuePoints(name: String): List<MixCandidate> {
        val array = array(name) ?: return emptyList()
        return array.mapNotNull { element ->
            val point = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
            val time = point["t"]?.jsonPrimitive?.doubleOrNull?.takeIf(Double::isFinite) ?: return@mapNotNull null
            MixCandidate(time = time, score = point.number("s"), type = point.text("y"))
        }
    }
}
