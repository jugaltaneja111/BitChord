@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.music.bitchord.ios.ffmpeg

import com.music.bitchord.data.TrackLog
import com.music.bitchord.ios.TapDsp
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.autoreleasepool
import kotlinx.cinterop.cstr
import kotlinx.cinterop.free
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.plus
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.value
import platform.AVFAudio.AVAudioFormat
import platform.AVFAudio.AVAudioPCMBuffer
import platform.AVFAudio.AVAudioPlayerNode
import platform.AVFAudio.AVAudioPlayerNodeCompletionDataPlayedBack
import platform.Foundation.NSCondition
import platform.Foundation.NSQualityOfServiceUserInitiated
import platform.Foundation.NSRecursiveLock
import platform.Foundation.NSThread
import platform.Foundation.NSURL
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.posix.memset
import kotlin.concurrent.Volatile

/** What a stream decodes as, for the stats line: names as Media3 gives them. */
internal data class DecodedInfo(
    val mimeType: String?,
    val sampleRateHz: Int?,
    val channels: Int?,
    val bitDepth: Int?,
    val kbps: Int?,
)

/**
 * A player for what AVPlayer can't play — Opus, Vorbis, WebM/Ogg, DASH: FFmpeg demuxes and
 * decodes, and the samples go out through an AVAudioPlayerNode on the shared
 * [FfmpegAudioEngine], passing through [dsp] (the equaliser, loudness gain and limiter) on the way.
 *
 * Threads: the API is called from the main thread, and callbacks come back on it. Each [open]
 * starts a session with its own decode thread, which owns every FFmpeg object of that session;
 * bytes arrive from a [ByteSource] filled in the background. The decode thread keeps about
 * [BUFFER_SECONDS] scheduled on the node and sleeps while it's ahead.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosFfmpegPlayer(
    private val dsp: TapDsp,
    private val onEnded: () -> Unit,
    private val onFailed: (String) -> Unit,
) {
    private val node = AVAudioPlayerNode()

    /** Serialises the node's transport, scheduling and connection between main and decode threads. */
    private val transport = NSRecursiveLock()

    /** The format the node is connected in; guarded by [transport]. */
    private var connectedFormat: AVAudioFormat? = null

    /** Guarded by [transport]: once set, no session touches the node again. */
    private var nodeReleased = false

    private var session: Session? = null
    private var released = false

    private val onEngineReset: () -> Unit = {
        // The engine dropped whatever was queued: schedule again from where the playhead was.
        session?.takeIf { it.isReady }?.let { seekTo(it.positionMs()) }
    }

    init {
        FfmpegAudioEngine.register(onEngineReset)
    }

    /** Opens [url] — http(s) progressive file, a DASH manifest when [dash] (or a URL ending .mpd), or an absolute file path — positioned at [startMs], paused. [headers] go on every HTTP request. */
    fun open(url: String, headers: Map<String, String>, dash: Boolean = false, startMs: Long = 0L) {
        if (released) return
        session?.cancel()
        val isDash = dash || url.substringBefore('?').substringBefore('#').endsWith(".mpd", ignoreCase = true)
        session = Session(url, headers, isDash, startMs.coerceAtLeast(0L)).also { it.start() }
    }

    fun play() {
        session?.play()
    }

    fun pause() {
        session?.pause()
    }

    fun seekTo(positionMs: Long) {
        session?.seekTo(positionMs.coerceAtLeast(0L))
    }

    /** 0..1, the player node's volume (crossfade + gain). */
    var volume: Float
        get() = node.volume
        set(value) {
            node.setVolume(value.coerceIn(0f, 1f))
        }

    /** The playhead, accurate to ~20 ms. */
    val positionMs: Long get() = session?.positionMs() ?: 0L

    /** 0 when unknown. */
    val durationMs: Long get() = session?.durationMs ?: 0L

    val isPlaying: Boolean get() = session?.isPlaying ?: false

    /** Asked to play but starved. */
    val isBuffering: Boolean get() = session?.isBuffering ?: false

    /** Stream opened and first audio decoded. */
    val isReady: Boolean get() = session?.isReady ?: false

    /** Non-null once it failed for good. */
    val failure: String? get() = session?.failure

    val format: DecodedInfo? get() = session?.info

    /** Stops everything, frees FFmpeg/engine resources, deletes temp files. */
    fun release() {
        if (released) return
        released = true
        session?.cancel()
        session = null
        FfmpegAudioEngine.unregister(onEngineReset)
        locked(transport) {
            nodeReleased = true
            node.stop()
            FfmpegAudioEngine.detach(node)
            connectedFormat = null
        }
        dsp.attached = false
    }

    private fun onMain(block: () -> Unit) {
        dispatch_async(dispatch_get_main_queue()) { block() }
    }

    private enum class Work { DECODE, SEEK, ENDED, EXIT }

    /**
     * One opened stream. Fields under [cond] are shared between the main thread, the decode
     * thread and the node's completion handlers; the FFmpeg state below them is the decode
     * thread's alone.
     */
    private inner class Session(
        private val url: String,
        private val headers: Map<String, String>,
        private val dash: Boolean,
        private val startMs: Long,
    ) {
        private val cond = NSCondition()

        // Guarded by cond.
        private var cancelled = false
        private var wantPlay = false
        private var pendingSeekMs: Long? = null
        private var queuedFrames = 0L
        private var scheduledFrames = 0L
        private var completedFrames = 0L
        private var pausedFrames = 0L
        private var baseMs = startMs.toDouble()
        private var outRate = 0.0
        private var nodeStarted = false
        /** The next buffer restarts the node: fresh, after a seek, or after it ran dry. */
        private var needsRestart = true
        private var eofDecoded = false
        private var endedReported = false
        private var bufferGeneration = 0
        private var ready = false
        private var failed: String? = null
        private var decoded: DecodedInfo? = null
        private var duration = 0L

        /** Mirrors pendingSeekMs != null for the byte source's interrupt check, which can't take cond. */
        @Volatile
        private var seekRequested = false

        @Volatile
        private var opened = false

        @Volatile
        private var source: ByteSource? = null

        // The decode thread's.
        private val formatSlot = nativeHeap.alloc<COpaquePointerVar>()
        private val ioSlot = nativeHeap.alloc<COpaquePointerVar>()
        private val codecSlot = nativeHeap.alloc<COpaquePointerVar>()
        private val packetSlot = nativeHeap.alloc<COpaquePointerVar>()
        private val frameSlot = nativeHeap.alloc<COpaquePointerVar>()
        private val swrSlot = nativeHeap.alloc<COpaquePointerVar>()
        private val outPlanes = nativeHeap.allocArray<CPointerVar<UByteVar>>(2)
        private val inPlanes = nativeHeap.allocArray<CPointerVar<UByteVar>>(MAX_PLANES)
        private val outLayout = nativeHeap.allocArray<ByteVar>(FfmpegLayout.LAYOUT_SIZE)
        private val inLayout = nativeHeap.allocArray<ByteVar>(FfmpegLayout.LAYOUT_SIZE)
        private var bridge: StableRef<AvioBridge>? = null
        private var streamIndex = -1
        private var timeBase = 0.0
        private var startPts = 0L
        private var swrKey: Triple<Int, Int, Int>? = null
        private var swrChannels = 0
        private var bufferFormat: AVAudioFormat? = null
        private var pending: AVAudioPCMBuffer? = null
        private var pendingFilled = 0
        private var pendingStartMs = 0.0
        /** The time of the next sample swresample hands out; NaN until a frame sets it. */
        private var outMs = Double.NaN
        /** The time the next frame should start at, for frames without a timestamp. */
        private var nextFrameMs = Double.NaN
        /** Decoded samples before this are dropped: the target of the last seek. */
        private var dropUntilMs: Double? = null
        /** Packets before this are skipped undecoded: a seek the demuxer couldn't do itself. */
        private var skipPacketsUntilMs: Double? = null
        private var readInterrupted = false

        init {
            listOf(formatSlot, ioSlot, codecSlot, packetSlot, frameSlot, swrSlot).forEach { it.value = null }
            memset(outLayout, 0, FfmpegLayout.LAYOUT_SIZE.toULong())
            memset(inLayout, 0, FfmpegLayout.LAYOUT_SIZE.toULong())
        }

        fun start() {
            val thread = NSThread(block = { run() })
            thread.setName("BitChord FFmpeg")
            thread.setStackSize(1uL shl 20)
            thread.setQualityOfService(NSQualityOfServiceUserInitiated)
            thread.start()
        }

        // --- Main thread ---------------------------------------------------------------------

        fun cancel() {
            locked(transport) {
                locked(cond) {
                    cancelled = true
                    bufferGeneration++
                    cond.broadcast()
                }
                if (!nodeReleased) node.stop()
            }
            FfmpegAudioEngine.want(this, false)
            source?.cancel()
        }

        fun play() {
            locked(transport) {
                val start = locked(cond) {
                    if (cancelled || failed != null) return
                    wantPlay = true
                    cond.broadcast()
                    !needsRestart && !nodeStarted && queuedFrames > 0
                }
                FfmpegAudioEngine.want(this, true)
                if (start) startNode()
            }
        }

        fun pause() {
            locked(transport) {
                val playing = locked(cond) {
                    wantPlay = false
                    nodeStarted
                }
                if (playing && !nodeReleased) {
                    val played = playedFrames()
                    node.pause()
                    locked(cond) {
                        pausedFrames = played
                        nodeStarted = false
                    }
                }
            }
            FfmpegAudioEngine.want(this, false)
        }

        fun seekTo(positionMs: Long) {
            locked(cond) {
                if (cancelled) return
                pendingSeekMs = positionMs
                seekRequested = true
                eofDecoded = false
                endedReported = false
                cond.broadcast()
            }
            source?.let { (it as? RemoteSource)?.wake() }
        }

        fun positionMs(): Long {
            locked(cond) {
                pendingSeekMs?.let { return it }
                if (endedReported && duration > 0) return duration
            }
            return locked(transport) {
                val played = playedFrames()
                locked(cond) {
                    val rate = outRate
                    val ms = if (rate > 0) baseMs + played * 1000.0 / rate else baseMs
                    ms.toLong().coerceAtLeast(0L)
                }
            }
        }

        val durationMs: Long get() = locked(cond) { duration }
        val isReady: Boolean get() = locked(cond) { ready }
        val failure: String? get() = locked(cond) { failed }
        val info: DecodedInfo? get() = locked(cond) { decoded }

        val isBuffering: Boolean
            get() = locked(cond) {
                wantPlay && failed == null && !endedReported && !eofDecoded && (queuedFrames == 0L || needsRestart)
            }

        val isPlaying: Boolean
            get() = locked(cond) {
                wantPlay && nodeStarted && failed == null && !endedReported && queuedFrames > 0 && !needsRestart
            }

        /** Frames played since the node's last restart. Holding [transport]. */
        private fun playedFrames(): Long {
            val started = locked(cond) { nodeStarted }
            val sample = if (started && !nodeReleased) {
                node.lastRenderTime?.let { node.playerTimeForNodeTime(it) }?.takeIf { it.sampleTimeValid }?.sampleTime
            } else {
                null
            }
            return locked(cond) {
                if (sample != null) sample.coerceIn(0L, scheduledFrames)
                else maxOf(completedFrames, pausedFrames).coerceAtMost(scheduledFrames)
            }
        }

        /** Holding [transport]. */
        private fun startNode() {
            if (nodeReleased || locked(cond) { cancelled }) return
            val error = FfmpegAudioEngine.ensureRunning()
            if (error != null) {
                fail(error)
                return
            }
            node.play()
            locked(cond) { nodeStarted = true }
        }

        // --- Decode thread -------------------------------------------------------------------

        private fun run() {
            try {
                Av.loadError?.let {
                    fail(it)
                    return
                }
                val src = when {
                    url.startsWith("/") -> FileSource(url)
                    url.startsWith("file://") -> FileSource(NSURL.URLWithString(url)?.path ?: url.removePrefix("file://"))
                    else -> RemoteSource(url, headers, dash)
                }
                source = src
                (src as? RemoteSource)?.interruptCheck = { opened && seekRequested }
                if (locked(cond) { cancelled }) return
                src.failure?.let {
                    fail(it)
                    return
                }
                packetSlot.value = Av.packetAlloc()
                frameSlot.value = Av.frameAlloc()
                if (packetSlot.value == null || frameSlot.value == null) error("out of memory")
                openDemuxer()
                opened = true
                publishInfo()
                if (startMs > 0) {
                    locked(cond) {
                        if (pendingSeekMs == null) {
                            pendingSeekMs = startMs
                            seekRequested = true
                        }
                    }
                }
                var running = true
                while (running) {
                    running = autoreleasepool { step() }
                }
            } catch (e: Throwable) {
                if (!locked(cond) { cancelled }) fail(e.message ?: "playback failed")
            } finally {
                cleanup()
            }
        }

        /** One turn of the decode loop; false to stop. */
        private fun step(): Boolean {
            when (waitForWork()) {
                Work.EXIT -> return false
                Work.ENDED -> {
                    FfmpegAudioEngine.want(this, false)
                    onMain { if (session === this && !released) onEnded() }
                }
                Work.SEEK -> doSeek()
                Work.DECODE -> decodeOne()
            }
            return true
        }

        private fun waitForWork(): Work = locked(cond) {
            while (true) {
                if (cancelled || failed != null) return@locked Work.EXIT
                if (pendingSeekMs != null) return@locked Work.SEEK
                if (eofDecoded) {
                    if (queuedFrames == 0L && !endedReported) {
                        endedReported = true
                        return@locked Work.ENDED
                    }
                } else if (outRate <= 0 || queuedFrames < outRate * BUFFER_SECONDS) {
                    return@locked Work.DECODE
                }
                cond.wait()
            }
            @Suppress("UNREACHABLE_CODE")
            Work.EXIT
        }

        private fun openDemuxer() {
            val src = source ?: error("no source")
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
            io.setInt(FfmpegLayout.IO_SEEKABLE, if (src.seekable) FfmpegLayout.IO_SEEKABLE_NORMAL else 0)
            val format = Av.formatAllocContext() ?: error("out of memory")
            format.setPointer(FfmpegLayout.FMT_PB, io)
            format.setInt(FfmpegLayout.FMT_FLAGS, format.int(FfmpegLayout.FMT_FLAGS) or FfmpegLayout.FMT_FLAG_CUSTOM_IO)
            formatSlot.value = format
            val openResult = memScoped { Av.formatOpenInput(formatSlot.ptr, "".cstr.ptr) }
            // On failure FFmpeg has freed the context and nulled the slot; the AVIO is still ours.
            if (openResult < 0) throw demuxError("can't open the stream", openResult)
            val infoResult = Av.findStreamInfo(formatSlot.value)
            if (infoResult < 0) throw demuxError("can't read the stream", infoResult)
            val ctx = formatSlot.value!!
            val index = Av.findBestAudioStream(ctx)
            if (index < 0) error("no audio in the stream")
            streamIndex = index
            val stream = ctx.pointer(FfmpegLayout.FMT_STREAMS)!!.reinterpret<COpaquePointerVar>()[index]!!
            val parameters = stream.pointer(FfmpegLayout.STREAM_CODECPAR)!!
            val codecId = parameters.int(FfmpegLayout.PAR_CODEC_ID)
            val decoder = Av.findDecoder(codecId) ?: error("no decoder for ${Av.codecName(codecId) ?: "codec $codecId"}")
            val codec = Av.codecAllocContext(decoder) ?: error("out of memory")
            codecSlot.value = codec
            if (Av.parametersToContext(codec, parameters) < 0) error("can't set up the decoder")
            // pkt_timebase = the stream's, so the decoder's timestamps (and Opus's pre-skip) line up.
            codec.setInt(FfmpegLayout.CODEC_PKT_TIMEBASE, stream.int(FfmpegLayout.STREAM_TIME_BASE))
            codec.setInt(FfmpegLayout.CODEC_PKT_TIMEBASE + 4, stream.int(FfmpegLayout.STREAM_TIME_BASE + 4))
            val opened = Av.codecOpen(codec, decoder)
            if (opened < 0) throw demuxError("can't open the decoder", opened)
            timeBase = stream.rational(FfmpegLayout.STREAM_TIME_BASE)
            if (timeBase <= 0) error("stream has no time base")
            startPts = stream.long(FfmpegLayout.STREAM_START_TIME).takeIf { it != FfmpegLayout.NOPTS_VALUE } ?: 0L
            readInterrupted = false
        }

        private fun closeDemuxer() {
            if (codecSlot.value != null) Av.codecFreeContext(codecSlot.ptr)
            if (formatSlot.value != null) Av.formatCloseInput(formatSlot.ptr)
            ioSlot.value?.let { io ->
                // The AVIO may have swapped its buffer for another: free whichever it holds now.
                Av.freep(io.at(FfmpegLayout.IO_BUFFER).reinterpret())
                Av.avioContextFree(ioSlot.ptr)
            }
            bridge?.dispose()
            bridge = null
            streamIndex = -1
        }

        private fun publishInfo() {
            val ctx = formatSlot.value ?: return
            val stream = ctx.pointer(FfmpegLayout.FMT_STREAMS)!!.reinterpret<COpaquePointerVar>()[streamIndex]!!
            val parameters = stream.pointer(FfmpegLayout.STREAM_CODECPAR)!!
            val name = Av.codecName(parameters.int(FfmpegLayout.PAR_CODEC_ID))
            val lossless = name == "flac" || name == "alac" || name == "wavpack" || name == "ape" || name == "tta" ||
                name?.startsWith("pcm_") == true
            val bits = parameters.int(FfmpegLayout.PAR_BITS_PER_RAW_SAMPLE).takeIf { it > 0 }
                ?: parameters.int(FfmpegLayout.PAR_BITS_PER_CODED_SAMPLE).takeIf { it > 0 }
            val bitRate = parameters.long(FfmpegLayout.PAR_BIT_RATE).takeIf { it > 0 }
                ?: ctx.long(FfmpegLayout.FMT_BIT_RATE).takeIf { it > 0 && ctx.int(FfmpegLayout.FMT_NB_STREAMS) == 1 }
                ?: source?.bandwidthHint?.takeIf { it > 0 }
            val formatDuration = ctx.long(FfmpegLayout.FMT_DURATION)
                .takeIf { it > 0 && it != FfmpegLayout.NOPTS_VALUE }?.let { it * 1000 / FfmpegLayout.TIME_BASE }
            val streamDuration = stream.long(FfmpegLayout.STREAM_DURATION)
                .takeIf { it > 0 && it != FfmpegLayout.NOPTS_VALUE }?.let { (it * timeBase * 1000).toLong() }
            val info = DecodedInfo(
                mimeType = mimeFor(name),
                sampleRateHz = parameters.int(FfmpegLayout.PAR_SAMPLE_RATE).takeIf { it > 0 },
                channels = parameters.int(FfmpegLayout.PAR_CH_LAYOUT + FfmpegLayout.LAYOUT_NB_CHANNELS).takeIf { it > 0 },
                bitDepth = bits.takeIf { lossless },
                kbps = bitRate?.let { (it / 1000).toInt() }?.takeIf { it > 0 },
            )
            locked(cond) {
                decoded = info
                duration = formatDuration ?: streamDuration ?: source?.durationHintMs ?: 0L
            }
        }

        private fun decodeOne() {
            val format = formatSlot.value ?: error("stream closed")
            // A DASH stream whose last segment just arrived can seek freely from now on.
            ioSlot.value?.let { io ->
                if (io.int(FfmpegLayout.IO_SEEKABLE) == 0 && source?.seekable == true) {
                    io.setInt(FfmpegLayout.IO_SEEKABLE, FfmpegLayout.IO_SEEKABLE_NORMAL)
                }
            }
            val packet = packetSlot.value
            val result = Av.readFrame(format, packet)
            if (result < 0) {
                if (locked(cond) { cancelled }) return
                if (seekRequested) {
                    // A read waiting on the network was cut short for the seek: the demuxer is
                    // reopened rather than trusted after that.
                    readInterrupted = true
                    return
                }
                if (result == FfmpegLayout.ERROR_EAGAIN) return
                source?.failure?.let {
                    fail(it)
                    return
                }
                if (result != FfmpegLayout.ERROR_EOF) TrackLog.w(TAG, "read ended: ${Av.describe(result)}")
                finishStream()
                return
            }
            try {
                if (packet!!.int(FfmpegLayout.PACKET_STREAM_INDEX) != streamIndex) return
                skipPacketsUntilMs?.let { until ->
                    val pts = packet.long(FfmpegLayout.PACKET_PTS).takeIf { it != FfmpegLayout.NOPTS_VALUE }
                        ?: packet.long(FfmpegLayout.PACKET_DTS)
                    if (pts != FfmpegLayout.NOPTS_VALUE && ptsToMs(pts) < until) return
                    skipPacketsUntilMs = null
                }
                val sent = Av.sendPacket(codecSlot.value, packet)
                if (sent < 0 && sent != FfmpegLayout.ERROR_EAGAIN) {
                    // A damaged packet is passed over, as a player would; anything else ends it.
                    if (sent != FfmpegLayout.ERROR_INVALIDDATA) error("decode failed: ${Av.describe(sent)}")
                    TrackLog.w(TAG, "skipped a bad packet")
                }
            } finally {
                Av.packetUnref(packet)
            }
            receiveFrames()
        }

        private fun receiveFrames() {
            val frame = frameSlot.value
            while (true) {
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

        private fun finishStream() {
            Av.sendPacket(codecSlot.value, null)
            receiveFrames()
            if (swrSlot.value != null) convert(null, 0)
            flushPending()
            locked(cond) {
                eofDecoded = true
                cond.broadcast()
            }
        }

        private fun handleFrame(frame: COpaquePointer) {
            val samples = frame.int(FfmpegLayout.FRAME_NB_SAMPLES)
            val inFormat = frame.int(FfmpegLayout.FRAME_FORMAT)
            val inRate = frame.int(FfmpegLayout.FRAME_SAMPLE_RATE)
            val inChannels = frame.int(FfmpegLayout.FRAME_CH_LAYOUT + FfmpegLayout.LAYOUT_NB_CHANNELS)
            if (samples <= 0 || inRate <= 0 || inChannels <= 0) return
            var pts = frame.long(FfmpegLayout.FRAME_BEST_EFFORT_TIMESTAMP)
            if (pts == FfmpegLayout.NOPTS_VALUE) pts = frame.long(FfmpegLayout.FRAME_PTS)
            val frameMs = when {
                pts != FfmpegLayout.NOPTS_VALUE -> ptsToMs(pts)
                !nextFrameMs.isNaN() -> nextFrameMs
                else -> locked(cond) { baseMs }
            }
            val frameLengthMs = samples * 1000.0 / inRate
            nextFrameMs = frameMs + frameLengthMs
            var skip = 0
            dropUntilMs?.let { until ->
                if (frameMs + frameLengthMs <= until) return
                skip = ((until - frameMs) * inRate / 1000).toInt().coerceIn(0, samples - 1)
                dropUntilMs = null
            }
            val key = Triple(inFormat, inRate, inChannels)
            if (key != swrKey) {
                // A new input shape: what the old resampler holds goes out first.
                if (swrSlot.value != null) convert(null, 0)
                flushPending()
                setUpResampler(frame, inFormat, inRate, inChannels)
                swrKey = key
            }
            if (outMs.isNaN()) outMs = frameMs + skip * 1000.0 / inRate
            val data = frame.pointer(FfmpegLayout.FRAME_EXTENDED_DATA)!!.reinterpret<CPointerVar<UByteVar>>()
            val bytes = Av.bytesPerSample(inFormat)
            if (Av.isPlanar(inFormat)) {
                for (c in 0 until minOf(inChannels, MAX_PLANES)) inPlanes[c] = data[c]!! + (skip * bytes).toLong()
            } else {
                inPlanes[0] = data[0]!! + (skip.toLong() * bytes * inChannels)
            }
            convert(inPlanes, samples - skip)
        }

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
            val result = Av.swrAllocSetOpts(swrSlot.ptr, outLayout, FfmpegLayout.SAMPLE_FMT_FLTP, inRate, input, inFormat, inRate)
            if (result < 0 || swrSlot.value == null) error("can't convert the audio: ${Av.describe(result)}")
            val init = Av.swrInit(swrSlot.value)
            if (init < 0) error("can't convert the audio: ${Av.describe(init)}")
            swrChannels = outChannels
            val current = bufferFormat
            if (current == null || current.sampleRate != inRate.toDouble() || current.channelCount.toInt() != outChannels) {
                bufferFormat = AVAudioFormat(standardFormatWithSampleRate = inRate.toDouble(), channels = outChannels.toUInt())
            }
        }

        /** Through swresample into pending buffers, each scheduled as it fills; null input drains it. */
        private fun convert(input: CPointer<CPointerVar<UByteVar>>?, count: Int) {
            val format = bufferFormat ?: return
            val rate = format.sampleRate
            var data = input
            var frames = count
            while (true) {
                val buffer = pending ?: newBuffer(format)
                val capacity = buffer.frameCapacity.toInt()
                val channels = buffer.floatChannelData ?: error("no sample buffer")
                for (c in 0 until swrChannels) outPlanes[c] = (channels[c]!! + pendingFilled.toLong())!!.reinterpret()
                val made = Av.swrConvert(swrSlot.value, outPlanes, capacity - pendingFilled, data, frames)
                if (made < 0) error("can't convert the audio: ${Av.describe(made)}")
                pendingFilled += made
                if (!outMs.isNaN()) outMs += made * 1000.0 / rate
                data = null
                frames = 0
                if (pendingFilled >= capacity) flushPending() else return
            }
        }

        private fun newBuffer(format: AVAudioFormat): AVAudioPCMBuffer {
            val frames = (format.sampleRate * CHUNK_SECONDS).toInt().coerceAtLeast(1024)
            val buffer = AVAudioPCMBuffer(pCMFormat = format, frameCapacity = frames.toUInt())
            pending = buffer
            pendingFilled = 0
            pendingStartMs = if (outMs.isNaN()) locked(cond) { baseMs } else outMs
            return buffer
        }

        /** Hands the pending buffer to the node: connected in its format, through the DSP, then queued. */
        private fun flushPending() {
            val buffer = pending ?: return
            val frames = pendingFilled
            val startMs = pendingStartMs
            pending = null
            pendingFilled = 0
            if (frames <= 0) return
            buffer.setFrameLength(frames.toUInt())
            if (!connect(buffer.format)) return
            locked(cond) { outRate = buffer.format.sampleRate }
            locked(transport) {
                if (nodeReleased || locked(cond) { cancelled || pendingSeekMs != null }) return
                buffer.mutableAudioBufferList?.let { dsp.process(it.pointed, frames) }
                dsp.attached = true
                if (locked(cond) { needsRestart }) {
                    locked(cond) { bufferGeneration++ }
                    node.stop()
                    locked(cond) {
                        queuedFrames = 0
                        scheduledFrames = 0
                        completedFrames = 0
                        pausedFrames = 0
                        nodeStarted = false
                        needsRestart = false
                        baseMs = startMs
                    }
                }
                val generation = locked(cond) {
                    queuedFrames += frames
                    scheduledFrames += frames
                    bufferGeneration
                }
                val count = frames.toLong()
                node.scheduleBuffer(buffer, completionCallbackType = AVAudioPlayerNodeCompletionDataPlayedBack) { _ ->
                    completed(generation, count)
                }
                val start = locked(cond) {
                    ready = true
                    wantPlay && !nodeStarted
                }
                if (start) startNode()
            }
        }

        /** A node completion handler, on AVFoundation's thread: takes only [cond]. */
        private fun completed(generation: Int, frames: Long) {
            locked(cond) {
                if (generation != bufferGeneration) return
                queuedFrames -= frames
                completedFrames += frames
                if (queuedFrames <= 0L) {
                    queuedFrames = 0L
                    // Ran dry mid-stream: the next buffer restarts the node, so the playhead
                    // doesn't count the silence in between.
                    if (!eofDecoded) needsRestart = true
                }
                cond.broadcast()
            }
        }

        /**
         * Makes sure the node is connected in [format], waiting for what's queued in another format
         * to play out first. False when a seek or cancel came first.
         */
        private fun connect(format: AVAudioFormat): Boolean {
            fun matches(): Boolean = locked(transport) {
                val current = connectedFormat
                current != null && current.sampleRate == format.sampleRate &&
                    current.channelCount == format.channelCount && FfmpegAudioEngine.isAttached(node)
            }
            if (matches()) return true
            locked(cond) {
                while (queuedFrames > 0 && !cancelled && pendingSeekMs == null && FfmpegAudioEngine.isAttached(node)) cond.wait()
                if (cancelled || pendingSeekMs != null) return false
            }
            locked(transport) {
                if (nodeReleased || locked(cond) { cancelled }) return false
                locked(cond) {
                    bufferGeneration++
                    needsRestart = true
                    outRate = format.sampleRate
                }
                node.stop()
                locked(cond) {
                    queuedFrames = 0
                    nodeStarted = false
                }
                FfmpegAudioEngine.connect(node, format)
                connectedFormat = format
                format.streamDescription?.let { dsp.prepare(it.pointed) }
            }
            return true
        }

        private fun doSeek() {
            val target = locked(cond) {
                val target = pendingSeekMs ?: return
                pendingSeekMs = null
                seekRequested = false
                eofDecoded = false
                endedReported = false
                target
            }
            // Stop what's playing and forget the queue; the next buffer restarts the node.
            locked(transport) {
                locked(cond) { bufferGeneration++ }
                if (!nodeReleased) node.stop()
                locked(cond) {
                    queuedFrames = 0
                    scheduledFrames = 0
                    completedFrames = 0
                    pausedFrames = 0
                    nodeStarted = false
                    needsRestart = true
                    baseMs = target.toDouble()
                }
            }
            pending = null
            pendingFilled = 0
            outMs = Double.NaN
            nextFrameMs = Double.NaN
            dropUntilMs = target.toDouble()
            skipPacketsUntilMs = null
            val reopen = readInterrupted
            readInterrupted = false
            if (!reopen && seekDemuxer(target)) return
            if (seekRequested) {
                // Another seek cut this one short: that one reopens.
                readInterrupted = true
                return
            }
            // The demuxer couldn't seek (a DASH stream still arriving), or a read was cut short:
            // open it again from the start, and skip ahead to the target undecoded.
            opened = false
            closeDemuxer()
            openDemuxer()
            opened = true
            resetDecoderState()
            if (source?.seekable == true && seekDemuxer(target)) return
            if (seekRequested) {
                readInterrupted = true
                return
            }
            skipPacketsUntilMs = target - PREROLL_MS
        }

        private fun seekDemuxer(target: Long): Boolean {
            val format = formatSlot.value ?: return false
            val timestamp = (target / 1000.0 / timeBase).toLong() + startPts
            val result = Av.seekFrame(format, streamIndex, timestamp, FfmpegLayout.SEEK_FLAG_BACKWARD)
            if (result < 0) {
                TrackLog.d(TAG, "seek to ${target}ms: ${Av.describe(result)}")
                return false
            }
            resetDecoderState()
            return true
        }

        private fun resetDecoderState() {
            codecSlot.value?.let { Av.flushBuffers(it) }
            // A fresh resampler: nothing from before the seek comes out of it.
            if (swrSlot.value != null) Av.swrFree(swrSlot.ptr)
            swrKey = null
        }

        private fun ptsToMs(pts: Long): Double = (pts - startPts) * timeBase * 1000.0

        private fun fail(message: String) {
            val first = locked(cond) {
                if (cancelled || failed != null) return
                failed = message
                cond.broadcast()
                true
            }
            if (!first) return
            TrackLog.w(TAG, "failed: $message")
            locked(transport) { if (!nodeReleased) node.stop() }
            FfmpegAudioEngine.want(this, false)
            onMain { if (session === this && !released) onFailed(message) }
        }

        private fun demuxError(what: String, code: Int): Exception =
            IllegalStateException(source?.failure ?: "$what (${Av.describe(code)})")

        private fun cleanup() {
            runCatching { closeDemuxer() }
            if (packetSlot.value != null) Av.packetFree(packetSlot.ptr)
            if (frameSlot.value != null) Av.frameFree(frameSlot.ptr)
            if (swrSlot.value != null) Av.swrFree(swrSlot.ptr)
            if (Av.loadError == null) {
                Av.channelLayoutUninit(outLayout)
                Av.channelLayoutUninit(inLayout)
            }
            source?.close()
            nativeHeap.free(formatSlot)
            nativeHeap.free(ioSlot)
            nativeHeap.free(codecSlot)
            nativeHeap.free(packetSlot)
            nativeHeap.free(frameSlot)
            nativeHeap.free(swrSlot)
            nativeHeap.free(outPlanes)
            nativeHeap.free(inPlanes)
            nativeHeap.free(outLayout)
            nativeHeap.free(inLayout)
            pending = null
        }
    }

    private companion object {
        const val TAG = "IosFfmpeg"
        /** How much is kept scheduled ahead of the playhead; also how late an EQ change is heard. */
        const val BUFFER_SECONDS = 1.0
        const val CHUNK_SECONDS = 0.1
        const val IO_BUFFER_BYTES = 64 * 1024
        const val MAX_PLANES = 64
        /** How far before a seek's target decoding starts, when packets are skipped to get there. */
        const val PREROLL_MS = 500.0

        fun mimeFor(codec: String?): String? = when {
            codec == null -> null
            codec == "opus" -> "audio/opus"
            codec == "vorbis" -> "audio/vorbis"
            codec == "flac" -> "audio/flac"
            codec == "aac" -> "audio/mp4a-latm"
            codec == "mp3" || codec == "mp3float" -> "audio/mpeg"
            codec == "alac" -> "audio/alac"
            codec == "ac3" -> "audio/ac3"
            codec == "eac3" -> "audio/eac3"
            codec.startsWith("pcm_") -> "audio/raw"
            else -> "audio/$codec"
        }
    }
}

/** The AVIOContext's view of a [ByteSource]: a read position over it. Decode thread only. */
@OptIn(ExperimentalForeignApi::class)
private class AvioBridge(val source: ByteSource) {
    var position = 0L

    fun read(buffer: CPointer<UByteVar>, size: Int): Int {
        val n = source.read(position, buffer.reinterpret(), size)
        if (n > 0) position += n
        return if (n == 0) FfmpegLayout.ERROR_EOF else n
    }

    fun seek(offset: Long, whence: Int): Long {
        if ((whence.toLong() and FfmpegLayout.SEEK_SIZE) != 0L) {
            return source.size.takeIf { it >= 0 } ?: ERROR_ENOSYS
        }
        val target = when (whence.toLong() and FfmpegLayout.SEEK_FORCE.inv()) {
            0L -> offset
            1L -> position + offset
            2L -> source.size.takeIf { it >= 0 }?.plus(offset) ?: return ERROR_ENOSYS
            else -> return ERROR_ENOSYS
        }
        if (target < 0) return ERROR_ENOSYS
        position = target
        return target
    }

    companion object {
        /** `AVERROR(ENOSYS)`: ENOSYS is 78 on Darwin. */
        const val ERROR_ENOSYS = -78L
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun avioRead(opaque: COpaquePointer?, buffer: CPointer<UByteVar>?, size: Int): Int = try {
    if (opaque == null || buffer == null) ERROR_EIO else opaque.asStableRef<AvioBridge>().get().read(buffer, size)
} catch (e: Throwable) {
    ERROR_EIO
}

@OptIn(ExperimentalForeignApi::class)
private fun avioSeek(opaque: COpaquePointer?, offset: Long, whence: Int): Long = try {
    opaque?.asStableRef<AvioBridge>()?.get()?.seek(offset, whence) ?: ERROR_EIO.toLong()
} catch (e: Throwable) {
    ERROR_EIO.toLong()
}

private inline fun <T> locked(lock: platform.Foundation.NSLockingProtocol, block: () -> T): T {
    lock.lock()
    try {
        return block()
    } finally {
        lock.unlock()
    }
}
