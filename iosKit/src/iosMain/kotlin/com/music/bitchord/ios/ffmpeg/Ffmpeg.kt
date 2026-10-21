@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.music.bitchord.ios.ffmpeg

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.plus
import kotlinx.cinterop.pointed
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.Foundation.NSBundle
import platform.posix.RTLD_DEFAULT
import platform.posix.RTLD_GLOBAL
import platform.posix.RTLD_NOW
import platform.posix.dlerror
import platform.posix.dlopen
import platform.posix.dlsym

private typealias P = COpaquePointer?
private typealias PP = CPointer<COpaquePointerVar>?

/** AVIOContext's read_packet: (opaque, buf, buf_size) -> bytes or AVERROR. */
internal typealias AvioRead = CPointer<CFunction<(COpaquePointer?, CPointer<UByteVar>?, Int) -> Int>>

/** AVIOContext's seek: (opaque, offset, whence) -> position or AVERROR. */
internal typealias AvioSeek = CPointer<CFunction<(COpaquePointer?, Long, Int) -> Long>>

/**
 * FFmpegBuild's libraries, bound at run time: dlopen'd from the app's embedded frameworks and
 * called through dlsym'd pointers. Kotlin/Native can't run cinterop for Apple targets on a Linux
 * host, and a target with a cinterop then isn't cross-compiled there at all, so this keeps iosKit
 * building on the Linux CI; structs are read at the offsets in [FfmpegLayout], generated from the
 * same headers by scripts/gen-ffmpeg-layout.sh.
 *
 * A build without the frameworks embedded still runs: [loadError] says why FFmpeg is unavailable
 * and the player reports it as a failure instead of crashing at launch.
 */
@OptIn(ExperimentalForeignApi::class)
internal object Av {
    private val LIBRARIES = listOf("AetherLibavutil", "AetherLibswresample", "AetherLibavcodec", "AetherLibavformat")

    private val handles: List<COpaquePointer> by lazy {
        val frameworks = NSBundle.mainBundle.privateFrameworksPath
        LIBRARIES.mapNotNull { name ->
            dlopen("@rpath/$name.framework/$name", RTLD_NOW or RTLD_GLOBAL)
                ?: frameworks?.let { dlopen("$it/$name.framework/$name", RTLD_NOW or RTLD_GLOBAL) }
        }
    }

    private fun sym(name: String): COpaquePointer {
        handles.forEach { handle -> dlsym(handle, name)?.let { return it } }
        return dlsym(RTLD_DEFAULT, name) ?: throw IllegalStateException("FFmpeg symbol $name missing")
    }

    /** Null when FFmpeg is loaded and is the build [FfmpegLayout] was generated from. */
    val loadError: String? by lazy {
        try {
            if (handles.size < LIBRARIES.size && dlsym(RTLD_DEFAULT, "avformat_version") == null) {
                return@lazy "FFmpeg isn't in this build (${dlerror()?.toKString() ?: "frameworks missing"})"
            }
            val versions = listOf(
                Triple("avformat", formatVersion(), FfmpegLayout.AVFORMAT_MAJOR),
                Triple("avcodec", codecVersion(), FfmpegLayout.AVCODEC_MAJOR),
                Triple("avutil", utilVersion(), FfmpegLayout.AVUTIL_MAJOR),
                Triple("swresample", swrVersion(), FfmpegLayout.SWRESAMPLE_MAJOR),
            )
            versions.firstOrNull { (_, version, major) -> (version shr 16).toInt() != major }?.let { (lib, version, major) ->
                return@lazy "FFmpeg lib$lib ${version shr 16} isn't the $major this app was built for"
            }
            // Every function bound below, looked up now so a missing one fails here, not mid-track.
            bound.forEach { it.value }
            logSetLevel(FfmpegLayout.LOG_ERROR)
            null
        } catch (e: Throwable) {
            e.message ?: "FFmpeg failed to load"
        }
    }

    private val bound = mutableListOf<Lazy<*>>()
    private fun <T> fn(name: String, cast: (COpaquePointer) -> T): Lazy<T> = lazy { cast(sym(name)) }.also { bound += it }

    // libavutil
    private val avutil_version by fn("avutil_version") { it.reinterpret<CFunction<() -> UInt>>() }
    private val av_log_set_level by fn("av_log_set_level") { it.reinterpret<CFunction<(Int) -> Unit>>() }
    private val av_malloc by fn("av_malloc") { it.reinterpret<CFunction<(ULong) -> P>>() }
    private val av_freep by fn("av_freep") { it.reinterpret<CFunction<(P) -> Unit>>() }
    private val av_strerror by fn("av_strerror") { it.reinterpret<CFunction<(Int, CPointer<ByteVar>?, ULong) -> Int>>() }
    private val av_frame_alloc by fn("av_frame_alloc") { it.reinterpret<CFunction<() -> P>>() }
    private val av_frame_free by fn("av_frame_free") { it.reinterpret<CFunction<(PP) -> Unit>>() }
    private val av_frame_unref by fn("av_frame_unref") { it.reinterpret<CFunction<(P) -> Unit>>() }
    private val av_channel_layout_default by fn("av_channel_layout_default") { it.reinterpret<CFunction<(P, Int) -> Unit>>() }
    private val av_channel_layout_uninit by fn("av_channel_layout_uninit") { it.reinterpret<CFunction<(P) -> Unit>>() }
    private val av_get_bytes_per_sample by fn("av_get_bytes_per_sample") { it.reinterpret<CFunction<(Int) -> Int>>() }
    private val av_sample_fmt_is_planar by fn("av_sample_fmt_is_planar") { it.reinterpret<CFunction<(Int) -> Int>>() }

    // libavformat
    private val avformat_version by fn("avformat_version") { it.reinterpret<CFunction<() -> UInt>>() }
    private val avformat_alloc_context by fn("avformat_alloc_context") { it.reinterpret<CFunction<() -> P>>() }
    private val avformat_open_input by fn("avformat_open_input") { it.reinterpret<CFunction<(PP, CPointer<ByteVar>?, P, P) -> Int>>() }
    private val avformat_find_stream_info by fn("avformat_find_stream_info") { it.reinterpret<CFunction<(P, P) -> Int>>() }
    private val av_find_best_stream by fn("av_find_best_stream") { it.reinterpret<CFunction<(P, Int, Int, Int, P, Int) -> Int>>() }
    private val av_read_frame by fn("av_read_frame") { it.reinterpret<CFunction<(P, P) -> Int>>() }
    private val av_seek_frame by fn("av_seek_frame") { it.reinterpret<CFunction<(P, Int, Long, Int) -> Int>>() }
    private val avformat_close_input by fn("avformat_close_input") { it.reinterpret<CFunction<(PP) -> Unit>>() }
    private val avio_alloc_context by fn("avio_alloc_context") {
        it.reinterpret<CFunction<(P, Int, Int, P, AvioRead?, P, AvioSeek?) -> P>>()
    }
    private val avio_context_free by fn("avio_context_free") { it.reinterpret<CFunction<(PP) -> Unit>>() }

    // libavcodec
    private val avcodec_version by fn("avcodec_version") { it.reinterpret<CFunction<() -> UInt>>() }
    private val avcodec_find_decoder by fn("avcodec_find_decoder") { it.reinterpret<CFunction<(Int) -> P>>() }
    private val avcodec_alloc_context3 by fn("avcodec_alloc_context3") { it.reinterpret<CFunction<(P) -> P>>() }
    private val avcodec_parameters_to_context by fn("avcodec_parameters_to_context") { it.reinterpret<CFunction<(P, P) -> Int>>() }
    private val avcodec_open2 by fn("avcodec_open2") { it.reinterpret<CFunction<(P, P, P) -> Int>>() }
    private val avcodec_send_packet by fn("avcodec_send_packet") { it.reinterpret<CFunction<(P, P) -> Int>>() }
    private val avcodec_receive_frame by fn("avcodec_receive_frame") { it.reinterpret<CFunction<(P, P) -> Int>>() }
    private val avcodec_flush_buffers by fn("avcodec_flush_buffers") { it.reinterpret<CFunction<(P) -> Unit>>() }
    private val avcodec_free_context by fn("avcodec_free_context") { it.reinterpret<CFunction<(PP) -> Unit>>() }
    private val avcodec_get_name by fn("avcodec_get_name") { it.reinterpret<CFunction<(Int) -> CPointer<ByteVar>?>>() }
    private val av_packet_alloc by fn("av_packet_alloc") { it.reinterpret<CFunction<() -> P>>() }
    private val av_packet_free by fn("av_packet_free") { it.reinterpret<CFunction<(PP) -> Unit>>() }
    private val av_packet_unref by fn("av_packet_unref") { it.reinterpret<CFunction<(P) -> Unit>>() }

    // libswresample
    private val swresample_version by fn("swresample_version") { it.reinterpret<CFunction<() -> UInt>>() }
    private val swr_alloc_set_opts2 by fn("swr_alloc_set_opts2") {
        it.reinterpret<CFunction<(PP, P, Int, Int, P, Int, Int, Int, P) -> Int>>()
    }
    private val swr_init by fn("swr_init") { it.reinterpret<CFunction<(P) -> Int>>() }
    private val swr_convert by fn("swr_convert") {
        it.reinterpret<CFunction<(P, CPointer<CPointerVar<UByteVar>>?, Int, CPointer<CPointerVar<UByteVar>>?, Int) -> Int>>()
    }
    private val swr_free by fn("swr_free") { it.reinterpret<CFunction<(PP) -> Unit>>() }

    fun utilVersion(): UInt = avutil_version()
    fun formatVersion(): UInt = avformat_version()
    fun codecVersion(): UInt = avcodec_version()
    fun swrVersion(): UInt = swresample_version()
    fun logSetLevel(level: Int) = av_log_set_level(level)
    fun malloc(size: Int): P = av_malloc(size.toULong())
    /** av_freep: frees `*slot` and nulls it. */
    fun freep(slot: CPointer<COpaquePointerVar>) = av_freep(slot)
    fun frameAlloc(): P = av_frame_alloc()
    fun frameFree(slot: CPointer<COpaquePointerVar>) = av_frame_free(slot)
    fun frameUnref(frame: P) = av_frame_unref(frame)
    fun channelLayoutDefault(layout: P, channels: Int) = av_channel_layout_default(layout, channels)
    fun channelLayoutUninit(layout: P) = av_channel_layout_uninit(layout)
    fun bytesPerSample(format: Int): Int = av_get_bytes_per_sample(format)
    fun isPlanar(format: Int): Boolean = av_sample_fmt_is_planar(format) != 0

    fun formatAllocContext(): P = avformat_alloc_context()
    fun formatOpenInput(slot: CPointer<COpaquePointerVar>, url: CPointer<ByteVar>?): Int = avformat_open_input(slot, url, null, null)
    fun findStreamInfo(format: P): Int = avformat_find_stream_info(format, null)
    fun findBestAudioStream(format: P): Int = av_find_best_stream(format, FfmpegLayout.MEDIA_TYPE_AUDIO, -1, -1, null, 0)
    fun readFrame(format: P, packet: P): Int = av_read_frame(format, packet)
    fun seekFrame(format: P, stream: Int, timestamp: Long, flags: Int): Int = av_seek_frame(format, stream, timestamp, flags)
    fun formatCloseInput(slot: CPointer<COpaquePointerVar>) = avformat_close_input(slot)
    fun avioAllocContext(buffer: P, size: Int, opaque: P, read: AvioRead, seek: AvioSeek): P =
        avio_alloc_context(buffer, size, 0, opaque, read, null, seek)
    fun avioContextFree(slot: CPointer<COpaquePointerVar>) = avio_context_free(slot)

    fun findDecoder(codecId: Int): P = avcodec_find_decoder(codecId)
    fun codecAllocContext(codec: P): P = avcodec_alloc_context3(codec)
    fun parametersToContext(context: P, parameters: P): Int = avcodec_parameters_to_context(context, parameters)
    fun codecOpen(context: P, codec: P): Int = avcodec_open2(context, codec, null)
    fun sendPacket(context: P, packet: P): Int = avcodec_send_packet(context, packet)
    fun receiveFrame(context: P, frame: P): Int = avcodec_receive_frame(context, frame)
    fun flushBuffers(context: P) = avcodec_flush_buffers(context)
    fun codecFreeContext(slot: CPointer<COpaquePointerVar>) = avcodec_free_context(slot)
    fun codecName(codecId: Int): String? = avcodec_get_name(codecId)?.toKString()
    fun packetAlloc(): P = av_packet_alloc()
    fun packetFree(slot: CPointer<COpaquePointerVar>) = av_packet_free(slot)
    fun packetUnref(packet: P) = av_packet_unref(packet)

    fun swrAllocSetOpts(
        slot: CPointer<COpaquePointerVar>,
        outLayout: P, outFormat: Int, outRate: Int,
        inLayout: P, inFormat: Int, inRate: Int,
    ): Int = swr_alloc_set_opts2(slot, outLayout, outFormat, outRate, inLayout, inFormat, inRate, 0, null)
    fun swrInit(swr: P): Int = swr_init(swr)
    fun swrConvert(swr: P, out: CPointer<CPointerVar<UByteVar>>?, outCount: Int, input: CPointer<CPointerVar<UByteVar>>?, inCount: Int): Int =
        swr_convert(swr, out, outCount, input, inCount)
    fun swrFree(slot: CPointer<COpaquePointerVar>) = swr_free(slot)

    /** FFmpeg's text for an AVERROR code. */
    fun describe(code: Int): String = when (code) {
        FfmpegLayout.ERROR_EOF -> "end of file"
        FfmpegLayout.ERROR_EXIT -> "cancelled"
        FfmpegLayout.ERROR_INVALIDDATA -> "invalid data"
        else -> memScoped {
            val buffer = allocArray<ByteVar>(128)
            if (av_strerror(code, buffer, 128u) == 0) buffer.toKString() else "error $code"
        }
    }
}

/** Reads FFmpeg's struct fields at [FfmpegLayout] offsets. */
@OptIn(ExperimentalForeignApi::class)
internal fun COpaquePointer.at(offset: Int): COpaquePointer = (reinterpret<ByteVar>() + offset)!!

@OptIn(ExperimentalForeignApi::class)
internal fun COpaquePointer.int(offset: Int): Int = at(offset).reinterpret<IntVar>().pointed.value

@OptIn(ExperimentalForeignApi::class)
internal fun COpaquePointer.setInt(offset: Int, value: Int) {
    at(offset).reinterpret<IntVar>().pointed.value = value
}

@OptIn(ExperimentalForeignApi::class)
internal fun COpaquePointer.long(offset: Int): Long = at(offset).reinterpret<LongVar>().pointed.value

@OptIn(ExperimentalForeignApi::class)
internal fun COpaquePointer.pointer(offset: Int): COpaquePointer? = at(offset).reinterpret<COpaquePointerVar>().pointed.value

@OptIn(ExperimentalForeignApi::class)
internal fun COpaquePointer.setPointer(offset: Int, value: COpaquePointer?) {
    at(offset).reinterpret<COpaquePointerVar>().pointed.value = value
}

/** An AVRational field, as a double (0 when the denominator is). */
@OptIn(ExperimentalForeignApi::class)
internal fun COpaquePointer.rational(offset: Int): Double {
    val num = int(offset)
    val den = int(offset + 4)
    return if (den == 0) 0.0 else num.toDouble() / den
}
