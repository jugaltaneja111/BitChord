@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.music.bitchord.ios.ffmpeg

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cstr
import kotlinx.cinterop.free
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.value
import platform.posix.memset
import kotlin.concurrent.Volatile

/**
 * A whole file on the phone, decoded to mono float at [rate] — what Automix's analyser reads
 * (TrackFeatures' 11,025 Hz). The player's FFmpeg pipeline without the player: the same AVIO over a
 * [FileSource], demuxer and decoder, and swresample to [rate]; the mono mix is the two channels'
 * average, as Android's `TrackAnalyzer` makes it, rather than swresample's own downmix.
 *
 * Blocking, and every FFmpeg object it makes is made, used and freed by the thread calling
 * [decode]. [cancel] may come from any thread: the file's reads then fail and the decode stops at
 * the next packet.
 */
internal class FfmpegMonoDecoder(
    private val path: String,
    private val rate: Int,
    /** A ceiling, so a mis-tagged eight-hour upload can't take the phone's memory. */
    private val maxSamples: Int,
) {
    class Decoded(
        /** The first [count] are the audio. */
        val samples: FloatArray,
        val count: Int,
        /** The container's own duration, 0 when it states none. */
        val containerSeconds: Double,
        val codec: String?,
        /** The file's own rate, before resampling. */
        val sourceRate: Int,
        val rate: Int,
    ) {
        val seconds: Double get() = count.toDouble() / rate
    }

    @Volatile
    private var cancelled = false

    @Volatile
    private var source: FileSource? = null

    fun cancel() {
        cancelled = true
        source?.cancel()
    }

    private val formatSlot = nativeHeap.alloc<COpaquePointerVar>()
    private val ioSlot = nativeHeap.alloc<COpaquePointerVar>()
    private val codecSlot = nativeHeap.alloc<COpaquePointerVar>()
    private val packetSlot = nativeHeap.alloc<COpaquePointerVar>()
    private val frameSlot = nativeHeap.alloc<COpaquePointerVar>()
    private val swrSlot = nativeHeap.alloc<COpaquePointerVar>()
    private val outPlanes = nativeHeap.allocArray<CPointerVar<UByteVar>>(2)
    private val outLayout = nativeHeap.allocArray<ByteVar>(FfmpegLayout.LAYOUT_SIZE)
    private val inLayout = nativeHeap.allocArray<ByteVar>(FfmpegLayout.LAYOUT_SIZE)
    private var bridge: StableRef<AvioBridge>? = null
    private var streamIndex = -1
    private var swrKey: Triple<Int, Int, Int>? = null
    private var swrChannels = 0
    private var left: CPointer<FloatVar>? = null
    private var right: CPointer<FloatVar>? = null
    private var planeCapacity = 0

    private var out = FloatArray(0)
    private var count = 0
    private var full = false

    init {
        listOf(formatSlot, ioSlot, codecSlot, packetSlot, frameSlot, swrSlot).forEach { it.value = null }
        memset(outLayout, 0, FfmpegLayout.LAYOUT_SIZE.toULong())
        memset(inLayout, 0, FfmpegLayout.LAYOUT_SIZE.toULong())
    }

    /**
     * The file, decoded; throws with the reason when it can't be. [expectedSeconds] sizes the
     * buffer up front so it isn't regrown a dozen times. Once only.
     */
    fun decode(expectedSeconds: Double): Decoded {
        try {
            Av.loadError?.let { error(it) }
            val src = FileSource(path)
            source = src
            if (cancelled) src.cancel()
            src.failure?.let { error(it) }
            out = FloatArray(((expectedSeconds.coerceAtLeast(0.0) + 5) * rate).toInt().coerceIn(rate, maxSamples + 1))
            packetSlot.value = Av.packetAlloc()
            frameSlot.value = Av.frameAlloc()
            if (packetSlot.value == null || frameSlot.value == null) error("out of memory")
            val (containerSeconds, codec, sourceRate) = openDemuxer(src)
            while (!full) {
                if (cancelled) error("cancelled")
                val packet = packetSlot.value
                val result = Av.readFrame(formatSlot.value, packet)
                if (result == FfmpegLayout.ERROR_EAGAIN) continue
                if (result < 0) {
                    if (cancelled) error("cancelled")
                    if (result != FfmpegLayout.ERROR_EOF) error("read failed: ${Av.describe(result)}")
                    break
                }
                try {
                    if (packet!!.int(FfmpegLayout.PACKET_STREAM_INDEX) != streamIndex) continue
                    val sent = Av.sendPacket(codecSlot.value, packet)
                    // A damaged packet is passed over, as the player does; anything else ends it.
                    if (sent < 0 && sent != FfmpegLayout.ERROR_EAGAIN && sent != FfmpegLayout.ERROR_INVALIDDATA) {
                        error("decode failed: ${Av.describe(sent)}")
                    }
                } finally {
                    Av.packetUnref(packet)
                }
                receiveFrames()
            }
            if (!full) {
                Av.sendPacket(codecSlot.value, null)
                receiveFrames()
                if (swrSlot.value != null) convert(null, 0)
            }
            return Decoded(out, count, containerSeconds, codec, sourceRate, rate)
        } finally {
            cleanup()
        }
    }

    private fun openDemuxer(src: FileSource): Triple<Double, String?, Int> {
        val buffer = Av.malloc(IO_BUFFER_BYTES) ?: error("out of memory")
        val ref = StableRef.create(AvioBridge(src))
        bridge = ref
        val io = Av.avioAllocContext(buffer, IO_BUFFER_BYTES, ref.asCPointer(), staticCFunction(::avioRead), staticCFunction(::avioSeek))
        if (io == null) {
            memScoped {
                val slot = alloc<COpaquePointerVar>()
                slot.value = buffer
                Av.freep(slot.ptr)
            }
            error("out of memory")
        }
        ioSlot.value = io
        io.setInt(FfmpegLayout.IO_SEEKABLE, FfmpegLayout.IO_SEEKABLE_NORMAL)
        val format = Av.formatAllocContext() ?: error("out of memory")
        format.setPointer(FfmpegLayout.FMT_PB, io)
        format.setInt(FfmpegLayout.FMT_FLAGS, format.int(FfmpegLayout.FMT_FLAGS) or FfmpegLayout.FMT_FLAG_CUSTOM_IO)
        formatSlot.value = format
        val opened = memScoped { Av.formatOpenInput(formatSlot.ptr, "".cstr.ptr) }
        // On failure FFmpeg has freed the context and nulled the slot; the AVIO is still ours.
        if (opened < 0) error("can't open the file: ${Av.describe(opened)}")
        val info = Av.findStreamInfo(formatSlot.value)
        if (info < 0) error("can't read the file: ${Av.describe(info)}")
        val ctx = formatSlot.value!!
        val index = Av.findBestAudioStream(ctx)
        if (index < 0) error("no audio in the file")
        streamIndex = index
        val stream = ctx.pointer(FfmpegLayout.FMT_STREAMS)!!.reinterpret<COpaquePointerVar>()[index]!!
        val parameters = stream.pointer(FfmpegLayout.STREAM_CODECPAR)!!
        val codecId = parameters.int(FfmpegLayout.PAR_CODEC_ID)
        val decoder = Av.findDecoder(codecId) ?: error("no decoder for ${Av.codecName(codecId) ?: "codec $codecId"}")
        val codec = Av.codecAllocContext(decoder) ?: error("out of memory")
        codecSlot.value = codec
        if (Av.parametersToContext(codec, parameters) < 0) error("can't set up the decoder")
        // pkt_timebase = the stream's, so Opus's pre-skip is applied as the player applies it.
        codec.setInt(FfmpegLayout.CODEC_PKT_TIMEBASE, stream.int(FfmpegLayout.STREAM_TIME_BASE))
        codec.setInt(FfmpegLayout.CODEC_PKT_TIMEBASE + 4, stream.int(FfmpegLayout.STREAM_TIME_BASE + 4))
        val codecOpened = Av.codecOpen(codec, decoder)
        if (codecOpened < 0) error("can't open the decoder: ${Av.describe(codecOpened)}")
        val timeBase = stream.rational(FfmpegLayout.STREAM_TIME_BASE)
        val formatSeconds = ctx.long(FfmpegLayout.FMT_DURATION)
            .takeIf { it > 0 && it != FfmpegLayout.NOPTS_VALUE }?.let { it.toDouble() / FfmpegLayout.TIME_BASE }
        val streamSeconds = stream.long(FfmpegLayout.STREAM_DURATION)
            .takeIf { it > 0 && it != FfmpegLayout.NOPTS_VALUE && timeBase > 0 }?.let { it * timeBase }
        return Triple(formatSeconds ?: streamSeconds ?: 0.0, Av.codecName(codecId), parameters.int(FfmpegLayout.PAR_SAMPLE_RATE))
    }

    private fun receiveFrames() {
        val frame = frameSlot.value
        while (!full) {
            val result = Av.receiveFrame(codecSlot.value, frame)
            if (result == FfmpegLayout.ERROR_EAGAIN || result == FfmpegLayout.ERROR_EOF) return
            if (result < 0) {
                if (result == FfmpegLayout.ERROR_INVALIDDATA) continue
                error("decode failed: ${Av.describe(result)}")
            }
            try {
                handleFrame(frame!!)
            } finally {
                Av.frameUnref(frame)
            }
        }
    }

    private fun handleFrame(frame: COpaquePointer) {
        val samples = frame.int(FfmpegLayout.FRAME_NB_SAMPLES)
        val inFormat = frame.int(FfmpegLayout.FRAME_FORMAT)
        val inRate = frame.int(FfmpegLayout.FRAME_SAMPLE_RATE)
        val inChannels = frame.int(FfmpegLayout.FRAME_CH_LAYOUT + FfmpegLayout.LAYOUT_NB_CHANNELS)
        if (samples <= 0 || inRate <= 0 || inChannels <= 0) return
        val key = Triple(inFormat, inRate, inChannels)
        if (key != swrKey) {
            // A new input shape: what the old resampler holds goes out first.
            if (swrSlot.value != null) convert(null, 0)
            setUpResampler(frame, inFormat, inRate, inChannels)
            swrKey = key
        }
        val data = frame.pointer(FfmpegLayout.FRAME_EXTENDED_DATA)!!.reinterpret<CPointerVar<UByteVar>>()
        convert(data, samples, inRate)
    }

    /** Planar float at [rate], in the input's own channels where that is one, else stereo. */
    private fun setUpResampler(frame: COpaquePointer, inFormat: Int, inRate: Int, inChannels: Int) {
        if (swrSlot.value != null) Av.swrFree(swrSlot.ptr)
        val outChannels = if (inChannels == 1) 1 else 2
        Av.channelLayoutUninit(outLayout)
        memset(outLayout, 0, FfmpegLayout.LAYOUT_SIZE.toULong())
        Av.channelLayoutDefault(outLayout, outChannels)
        var input: COpaquePointer = frame.at(FfmpegLayout.FRAME_CH_LAYOUT)
        if (input.int(FfmpegLayout.LAYOUT_ORDER) == FfmpegLayout.CHANNEL_ORDER_UNSPEC) {
            // Channels with no stated layout: the usual one for that many.
            Av.channelLayoutUninit(inLayout)
            memset(inLayout, 0, FfmpegLayout.LAYOUT_SIZE.toULong())
            Av.channelLayoutDefault(inLayout, inChannels)
            input = inLayout
        }
        val result = Av.swrAllocSetOpts(swrSlot.ptr, outLayout, FfmpegLayout.SAMPLE_FMT_FLTP, rate, input, inFormat, inRate)
        if (result < 0 || swrSlot.value == null) error("can't convert the audio: ${Av.describe(result)}")
        val init = Av.swrInit(swrSlot.value)
        if (init < 0) error("can't convert the audio: ${Av.describe(init)}")
        swrChannels = outChannels
    }

    /** Through swresample onto [out], as mono; null input drains it. */
    private fun convert(input: CPointer<CPointerVar<UByteVar>>?, frames: Int, inRate: Int = rate) {
        val capacity = maxOf(MIN_PLANE_SAMPLES, (frames.toLong() * rate / inRate).toInt() + 256)
        ensurePlanes(capacity)
        val l = left!!
        val r = right!!
        outPlanes[0] = l.reinterpret()
        outPlanes[1] = r.reinterpret()
        var data = input
        var count = frames
        while (true) {
            val made = Av.swrConvert(swrSlot.value, outPlanes, capacity, data, count)
            if (made < 0) error("can't convert the audio: ${Av.describe(made)}")
            append(l, r, made)
            data = null
            count = 0
            if (made < capacity || full) return
        }
    }

    private fun append(l: CPointer<FloatVar>, r: CPointer<FloatVar>, made: Int) {
        if (made <= 0) return
        val room = maxSamples - count
        val take = minOf(made, room)
        if (take <= 0) {
            full = true
            return
        }
        if (count + take > out.size) out = out.copyOf(maxOf(count + take, minOf(out.size * 3 / 2, maxSamples)))
        if (swrChannels == 1) {
            for (i in 0 until take) out[count + i] = l[i]
        } else {
            for (i in 0 until take) out[count + i] = (l[i] + r[i]) * 0.5f
        }
        count += take
        if (take < made) full = true
    }

    private fun ensurePlanes(samples: Int) {
        if (samples <= planeCapacity) return
        left?.let { nativeHeap.free(it) }
        right?.let { nativeHeap.free(it) }
        left = nativeHeap.allocArray(samples)
        right = nativeHeap.allocArray(samples)
        planeCapacity = samples
    }

    private fun cleanup() {
        if (swrSlot.value != null) Av.swrFree(swrSlot.ptr)
        if (codecSlot.value != null) Av.codecFreeContext(codecSlot.ptr)
        if (formatSlot.value != null) Av.formatCloseInput(formatSlot.ptr)
        ioSlot.value?.let { io ->
            // The AVIO may have swapped its buffer for another: free whichever it holds now.
            Av.freep(io.at(FfmpegLayout.IO_BUFFER).reinterpret())
            Av.avioContextFree(ioSlot.ptr)
        }
        if (packetSlot.value != null) Av.packetFree(packetSlot.ptr)
        if (frameSlot.value != null) Av.frameFree(frameSlot.ptr)
        bridge?.dispose()
        bridge = null
        source?.close()
        if (Av.loadError == null) {
            Av.channelLayoutUninit(outLayout)
            Av.channelLayoutUninit(inLayout)
        }
        left?.let { nativeHeap.free(it) }
        right?.let { nativeHeap.free(it) }
        left = null
        right = null
        listOf(formatSlot, ioSlot, codecSlot, packetSlot, frameSlot, swrSlot).forEach { nativeHeap.free(it) }
        nativeHeap.free(outPlanes)
        nativeHeap.free(outLayout)
        nativeHeap.free(inLayout)
    }

    private companion object {
        const val IO_BUFFER_BYTES = 64 * 1024
        const val MIN_PLANE_SAMPLES = 4096
    }
}
