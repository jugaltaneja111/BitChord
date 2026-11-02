@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.music.bitchord.ios.automix

import com.music.bitchord.data.TrackLog
import com.music.bitchord.ios.IosSettings
import com.music.bitchord.playback.smart.BeatModel
import com.music.bitchord.playback.smart.VocalModel
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.Foundation.NSBundle
import platform.posix.RTLD_DEFAULT
import platform.posix.RTLD_NOW
import platform.posix.dlerror
import platform.posix.dlopen
import platform.posix.dlsym
import platform.posix.memcpy

/**
 * Automix's two models on iOS — Beat This! (beats and downbeats) and open-unmix (vocals) — run by
 * ONNX Runtime in BitChordModels.framework (`native/models/ios/bitchord_models_ios.cpp`), bound at
 * run time as [IosAnalyzerNative] is. The weights are bundled with the app, under the names
 * Android unpacks them by.
 *
 * Each model's session is opened on first use, on the analysis thread, and kept until [release]
 * (the end of a run of analyses): opening parses the whole graph, which is too slow to repeat per
 * window. Intra-op threads follow the Automix performance setting, as on the phone (Balanced, 2,
 * by default); a session opened with another count is reopened once no run holds it.
 *
 * A build without the framework or a weights file still runs: [loadError] says why, the model
 * fields of an analysis stay empty, and Automix plans on the DSP's evidence alone, as before.
 */
internal object IosModels {
    private const val TAG = "IosModels"
    private const val FRAMEWORK = "BitChordModels"

    /** The bridge's kAbiVersion this side was written against. */
    private const val ABI_VERSION = 1

    /** Android's asset names (BeatTracker.MODEL_ASSET, VocalTracker.MODEL_ASSET). */
    const val BEAT_MODEL = "beat_this_final0_q8"
    const val VOCAL_MODEL = "vocals_umxhq_int8"

    private val threads: Int get() = IosSettings.automixPerformanceMode.inferenceThreads
    private const val MAX_OUTPUTS = 4

    private class Bound(
        val open: CPointer<CFunction<(CPointer<ByteVar>?, Int) -> COpaquePointer?>>,
        val run: CPointer<CFunction<(COpaquePointer?, CPointer<FloatVar>?, CPointer<LongVar>?, Int, CPointer<CPointerVar<FloatVar>>?, CPointer<LongVar>?, Int) -> Int>>,
        val free: CPointer<CFunction<(CPointer<FloatVar>?) -> Unit>>,
        val close: CPointer<CFunction<(COpaquePointer?) -> Unit>>,
        val lastError: CPointer<CFunction<() -> CPointer<ByteVar>?>>,
    )

    private val bound: Result<Bound> by lazy {
        runCatching {
            val handle = dlopen("@rpath/$FRAMEWORK.framework/$FRAMEWORK", RTLD_NOW)
                ?: NSBundle.mainBundle.privateFrameworksPath?.let { dlopen("$it/$FRAMEWORK.framework/$FRAMEWORK", RTLD_NOW) }
            val loaderError = if (handle == null) dlerror()?.toKString() else null
            fun sym(name: String): COpaquePointer =
                handle?.let { dlsym(it, name) } ?: dlsym(RTLD_DEFAULT, name)
                    ?: error("$FRAMEWORK isn't in this build (${loaderError ?: "$name missing"})")
            val abi = sym("bcm_abi").reinterpret<CFunction<() -> Int>>()()
            check(abi == ABI_VERSION) { "$FRAMEWORK's bridge is version $abi, not the $ABI_VERSION this app was built for" }
            Bound(
                open = sym("bcm_open").reinterpret(),
                run = sym("bcm_run").reinterpret(),
                free = sym("bcm_free").reinterpret(),
                close = sym("bcm_close").reinterpret(),
                lastError = sym("bcm_last_error").reinterpret(),
            )
        }
    }

    private fun path(name: String): String? = NSBundle.mainBundle.pathForResource(name, "onnx")

    /** Null when the runtime, both weights and the front ends are all here. */
    val loadError: String?
        get() = bound.exceptionOrNull()?.let { it.message ?: "the models failed to load" }
            ?: listOf(BEAT_MODEL, VOCAL_MODEL).firstOrNull { path(it) == null }?.let { "$it.onnx isn't in this build" }
            ?: IosAnalyzerNative.loadError

    /** A session of [model], or the reason there is none. Kept apart so one model's failure leaves the other. */
    private class Session(val model: String) {
        var handle: COpaquePointer? = null
        var threads = 0
        var failure: String? = null
    }

    private val sessions = HashMap<String, Session>()
    private val lock = platform.Foundation.NSLock()

    /**
     * Runs in flight, and whether [release] was asked for meanwhile. A measurement that was called
     * off finishes its run on its own thread, so the sessions are closed only once no run holds one.
     */
    private var running = 0
    private var releaseWanted = false

    /** Under [lock]. */
    private fun session(model: String): COpaquePointer? {
        val native = bound.getOrNull() ?: return null
        val session = sessions.getOrPut(model) { Session(model) }
        val wanted = threads
        session.handle?.let { open ->
            if (session.threads == wanted || running > 0) return open
            native.close(open)
            session.handle = null
        }
        if (session.failure != null) return null
        val file = path(model) ?: run {
            session.failure = "$model.onnx isn't in this build"
            return null
        }
        val handle = memScoped { native.open(file.cstr.ptr, wanted) }
        if (handle == null) {
            session.failure = native.lastError()?.toKString().orEmpty().ifBlank { "would not open" }
            TrackLog.w(TAG, "automix: $model unavailable: ${session.failure}")
            return null
        }
        session.handle = handle
        session.threads = wanted
        return handle
    }

    /** [model]'s outputs for [input] of [shape]; null when it can't run. Blocking: the analysis thread's. */
    private fun run(model: String, input: FloatArray, shape: LongArray): List<FloatArray>? {
        val native = bound.getOrNull() ?: return null
        lock.lock()
        val session = try {
            session(model)?.also {
                running++
                releaseWanted = false
            }
        } finally {
            lock.unlock()
        }
        session ?: return null
        try {
            return runOn(native, session, model, input, shape)
        } finally {
            lock.lock()
            try {
                running--
                if (running == 0 && releaseWanted) closeAll(native)
            } finally {
                lock.unlock()
            }
        }
    }

    private fun runOn(native: Bound, session: COpaquePointer, model: String, input: FloatArray, shape: LongArray): List<FloatArray>? {
        return memScoped {
            val outputs = allocArray<CPointerVar<FloatVar>>(MAX_OUTPUTS)
            val lengths = allocArray<LongVar>(MAX_OUTPUTS)
            for (i in 0 until MAX_OUTPUTS) outputs[i] = null
            val written = input.usePinned { data ->
                shape.usePinned { dims ->
                    native.run(session, data.addressOf(0), dims.addressOf(0), shape.size, outputs, lengths, MAX_OUTPUTS)
                }
            }
            if (written < 0) {
                TrackLog.w(TAG, "automix: $model failed: ${native.lastError()?.toKString().orEmpty()}")
                return@memScoped null
            }
            List(written) { index ->
                val buffer = outputs[index]
                val length = lengths[index].toInt()
                val out = FloatArray(length)
                if (length > 0 && buffer != null) {
                    out.usePinned { memcpy(it.addressOf(0), buffer, (length.toLong() * 4).toULong()) }
                }
                native.free(buffer)
                out
            }
        }
    }

    /**
     * Beat This! over [samples] (the first [count]), mono at the mel front end's 22,050 Hz, with
     * [offsetSeconds] added to every time; null when the model is missing or found no grid.
     */
    fun beats(samples: FloatArray, count: Int, offsetSeconds: Double): BeatModel.Grid? {
        val (mels, frames) = IosAnalyzerNative.melSpectrogram(samples, count) ?: return null
        val bands = IosAnalyzerNative.MEL_BANDS
        val beat = FloatArray(frames)
        val downbeat = FloatArray(frames)
        for (chunk in BeatModel.chunks(frames)) {
            val input = mels.copyOfRange(chunk.start * bands, (chunk.start + chunk.length) * bands)
            val outputs = run(BEAT_MODEL, input, longArrayOf(1, chunk.length.toLong(), bands.toLong())) ?: return null
            if (outputs.size < 2) return null
            for (index in chunk.keepFrom until chunk.keepTo) {
                val target = chunk.start + index
                if (target >= frames || index >= outputs[0].size || index >= outputs[1].size) break
                beat[target] = outputs[0][index]
                downbeat[target] = outputs[1][index]
            }
        }
        return BeatModel.grid(beat, downbeat, offsetSeconds)
    }

    /**
     * One vocal-presence value, 0..1, per open-unmix frame of [count] samples of planar stereo at
     * 44.1 kHz; null when the model is missing or the window is wider than its graph.
     */
    fun vocals(left: FloatArray, right: FloatArray, count: Int): FloatArray? {
        val usable = minOf(count, (VocalModel.WINDOW_SECONDS * VocalModel.SAMPLE_RATE).toInt())
        val (values, frames) = IosAnalyzerNative.vocalSpectrogram(left, right, usable) ?: return null
        if (frames > VocalModel.FIXED_FRAMES) return null
        val mix = VocalModel.fixedFrames(values, frames)
        val shape = longArrayOf(1, VocalModel.CHANNELS.toLong(), VocalModel.BINS.toLong(), VocalModel.FIXED_FRAMES.toLong())
        val target = run(VOCAL_MODEL, mix, shape)?.firstOrNull() ?: return null
        if (target.size != mix.size) return null
        return VocalModel.bandCurve(mix, target, frames)
    }

    /**
     * Closes both sessions — now, or when the last run in flight ends — and forgets a failure so
     * the next run of analyses tries again.
     */
    fun release() {
        val native = bound.getOrNull() ?: return
        lock.lock()
        try {
            if (running > 0) releaseWanted = true else closeAll(native)
        } finally {
            lock.unlock()
        }
    }

    /** Under [lock], with no run in flight. */
    private fun closeAll(native: Bound) {
        sessions.values.forEach { session -> session.handle?.let { native.close(it) } }
        sessions.clear()
        releaseWanted = false
    }
}
