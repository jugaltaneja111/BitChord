@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.music.bitchord.ios.automix

import com.music.bitchord.playback.smart.EnergySample
import com.music.bitchord.playback.smart.MixCandidate
import com.music.bitchord.playback.smart.TrackAnalysis
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.invoke
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
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

    /** The bridge's kAbiVersion this side was written against. */
    private const val ABI_VERSION = 1

    private class Bound(
        val run: CPointer<CFunction<(CPointer<FloatVar>?, Long, Double, Double) -> CPointer<ByteVar>?>>,
        val free: CPointer<CFunction<(CPointer<ByteVar>?) -> Unit>>,
        val sampleRate: Double,
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
            Bound(
                run = sym("bitchord_analysis_run").reinterpret(),
                free = sym("bitchord_analysis_free").reinterpret(),
                sampleRate = sym("bitchord_analysis_sample_rate").reinterpret<CFunction<() -> Double>>()(),
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
}

/**
 * The bridge's JSON as a [TrackAnalysis]: TrackFeatures.parse (jvmSharedMain) on the commonMain
 * types, field for field, then assembled as the desktop's analyser assembles it — minus the Beat This!
 * grid, which needs ONNX Runtime and so has no iOS build. The beat grid, its confidence and the vocal
 * mask are therefore always the DSP's own (autocorrelation and spectral heuristics), as on a phone
 * whose model failed to load; the policy already weighs them that way.
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
