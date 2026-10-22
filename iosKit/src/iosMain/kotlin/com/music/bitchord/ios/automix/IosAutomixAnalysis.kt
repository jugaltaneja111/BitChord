package com.music.bitchord.ios.automix

import com.music.bitchord.data.MonotonicClock
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.appHttpClient
import com.music.bitchord.data.innertube.InnerTubeXResolver
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.SmartAnalysis
import com.music.bitchord.data.settings.TrackAnalysisState
import com.music.bitchord.ios.IosDownloads
import com.music.bitchord.ios.IosSongCache
import com.music.bitchord.ios.ffmpeg.Av
import com.music.bitchord.ios.ffmpeg.FfmpegMonoDecoder
import com.music.bitchord.ios.party.IosListenTogether
import com.music.bitchord.ios.sources.IosSources
import com.music.bitchord.playback.smart.TrackAnalysis
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.readRawBytes
import io.ktor.http.Url
import io.ktor.http.isSuccess
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.NSQualityOfServiceUtility
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSThread
import platform.Foundation.NSUserDomainMask
import platform.Foundation.closeFile
import platform.Foundation.dataWithBytes
import platform.Foundation.fileHandleForWritingAtPath
import platform.Foundation.writeData
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToLong

/**
 * Automix's track analyses: the evidence the shared planner (TransitionPlanner) mixes on — tempo,
 * beat grid, key, structure, and where a track can be entered and left.
 *
 * The desktop's DesktopTrackAnalyzer, closely: one track at a time, the whole of it decoded to mono
 * float at the analyzer's 11,025 Hz (FFmpeg, [FfmpegMonoDecoder]) and measured by the same native
 * DSP analyzer Android and the desktop run ([IosAnalyzerNative]); a decode short of 95% of the
 * track is refused, as Android refuses one, since it reads as the music ending early; results kept
 * in memory and on disk ([IosAnalysisStore], AnalysisStore's format); a failure retried after a
 * cooldown.
 *
 * What is not here, and why: Android's Beat This! and open-unmix models run on ONNX Runtime, which
 * the iOS build doesn't have. The beat grid, its confidence and the vocal mask are the DSP's own —
 * Phase 1's numbers, which the policy already treats as it treats a model that failed to load.
 *
 * Audio, first that is already on the phone: a download, the song cache (read without marking it
 * played), a local file. Otherwise the track's YouTube copy at the lowest rung, fetched into Caches
 * for the measurement and deleted after it — a megabyte or two, since an 11 kHz mono analysis needs
 * nothing better. A source track (add-on, JioSaavn) is measured on its YouTube match, as the
 * desktop and Android measure on YouTube's Opus, unless its own copy is downloaded or cached.
 *
 * Nothing runs in a Listen Together party, where Automix is off: requests are ignored and work in
 * flight is called off, recording nothing, so the track is measured once the party ends.
 *
 * Main thread, apart from the measurement itself, which runs on a utility-QoS thread of its own so
 * it yields to playback.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal object IosAutomixAnalysis {
    private const val TAG = "IosAutomix"

    /** How long a track that failed waits before it is tried again. */
    private const val RETRY_AFTER_MS = 60_000L

    /** Tries per track per launch; past it the track is left alone, as a failure, until relaunch. */
    private const val MAX_ATTEMPTS = 3

    /** Android's: a decode shorter than this share of the track is refused, not analysed. */
    private const val MIN_DECODED_FRACTION = 0.95

    /** About twenty minutes at the analyzer's rate: a mis-tagged eight-hour upload can't eat the heap. */
    private const val MAX_SAMPLES = 20 * 60 * 11_025

    /** The lowest rung InnerTubeX offers (its LOW quality): an 11 kHz mono analysis needs no more. */
    private const val FETCH_KBPS = 64

    /** A fetch past this is a mix or a stream, not a track worth measuring. */
    private const val MAX_FETCH_BYTES = 40L * 1024 * 1024
    private const val CHUNK_BYTES = 1L * 1024 * 1024

    /** Requests waiting behind the one in flight: the current track and the next are what matter. */
    private const val MAX_QUEUED = 2

    /** How many tracks [states] reports on, newest first. */
    private const val MAX_TRACKED = 32

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val store = IosAnalysisStore()
    private val files = NSFileManager.defaultManager
    private val http by lazy { appHttpClient { install(HttpTimeout) } }

    /** Caches/BitChordAnalysis, for fetched audio between its download and its measurement. */
    private val scratch: String by lazy {
        val caches = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).first() as String
        "$caches/BitChordAnalysis".also { path ->
            // Anything left here is from a run that was killed mid-measurement.
            files.removeItemAtPath(path, null)
            files.createDirectoryAtPath(path, withIntermediateDirectories = true, attributes = null, error = null)
        }
    }

    private val results = HashMap<String, TrackAnalysis>()
    private val queue = ArrayDeque<Song>()
    private var running: String? = null
    private var worker: Job? = null
    private val failedAt = HashMap<String, Long>()
    private val attempts = HashMap<String, Int>()
    private val tracked = ArrayDeque<String>()
    private var watchingParty = false
    private var warnedUnavailable = false

    private val _states = MutableStateFlow<Map<String, TrackAnalysisState>>(emptyMap())

    /**
     * Where each recently requested track stands, for the player's Automix line; see [stateFor] and
     * [smartAnalysis] for the shape the player's `smartAnalysis` flow wants.
     */
    val states: StateFlow<Map<String, TrackAnalysisState>> = _states.asStateFlow()

    /** Asks for [song] to be analysed, unless it already is or is under way. Main thread. */
    fun request(song: Song) {
        val id = song.videoId
        if (id.isBlank()) return
        watchParty()
        if (IosListenTogether.state.value.inParty) return
        val newlyTracked = track(id)
        if (analysisFor(id) != null || running == id || queue.any { it.videoId == id }) {
            if (newlyTracked) publish()
            return
        }
        unavailableReason()?.let { reason ->
            if (!warnedUnavailable) {
                warnedUnavailable = true
                TrackLog.w(TAG, "automix: nothing will be measured: $reason", about = id)
            }
            if (newlyTracked) publish()
            return
        }
        val gaveUp = (attempts[id] ?: 0) >= MAX_ATTEMPTS
        // Asked every tick; a track that just failed is not fetched and decoded again each time.
        val coolingDown = failedAt[id]?.let { MonotonicClock.nowMs() - it < RETRY_AFTER_MS } == true
        if (gaveUp || coolingDown) {
            if (newlyTracked) publish()
            return
        }
        queue.addLast(song)
        while (queue.size > MAX_QUEUED) queue.removeFirst()
        TrackLog.i(TAG, "automix: analysis requested for '${song.title}'", about = id)
        publish()
        if (worker?.isActive != true) worker = scope.launch { drain() }
    }

    /** [videoId]'s finished analysis, or null. Main thread. */
    fun analysisFor(videoId: String): TrackAnalysis? {
        if (videoId.isBlank()) return null
        results[videoId]?.let { return it }
        return store.load(videoId)?.also { results[videoId] = it }
    }

    /** How far [videoId] has got. Main thread. */
    fun stateFor(videoId: String?): TrackAnalysisState = when {
        videoId.isNullOrBlank() -> TrackAnalysisState.WAITING
        analysisFor(videoId)?.isUsable == true -> TrackAnalysisState.ANALYSED
        running == videoId -> TrackAnalysisState.ANALYSING
        // A recorded-but-unusable result is the analyzer saying it tried and found no tempo.
        results.containsKey(videoId) || videoId in failedAt -> TrackAnalysisState.FAILED
        unavailableReason() != null -> TrackAnalysisState.FAILED
        else -> TrackAnalysisState.WAITING
    }

    /** Both sides of the next transition, as the player's `smartAnalysis` flow carries them. Main thread. */
    fun smartAnalysis(currentId: String?, nextId: String?): SmartAnalysis =
        SmartAnalysis(current = stateFor(currentId), next = stateFor(nextId))

    /** Calls off the measurement in flight and everything queued, recording nothing. Main thread. */
    fun cancelAll() {
        queue.clear()
        worker?.cancel()
        worker = null
        running = null
        publish()
    }

    private fun unavailableReason(): String? = IosAnalyzerNative.loadError ?: Av.loadError

    private fun watchParty() {
        if (watchingParty) return
        watchingParty = true
        scope.launch {
            IosListenTogether.state.map { it.inParty }.distinctUntilChanged().collect { inParty ->
                if (inParty && (running != null || queue.isNotEmpty())) {
                    TrackLog.i(TAG, "automix: a party started; analysis called off")
                    cancelAll()
                }
            }
        }
    }

    private suspend fun drain() {
        while (true) {
            val song = queue.removeFirstOrNull() ?: break
            val id = song.videoId
            if (analysisFor(id) != null) continue
            if (IosListenTogether.state.value.inParty) break
            running = id
            publish()
            attempts[id] = (attempts[id] ?: 0) + 1
            try {
                val analysis = measure(song)
                failedAt.remove(id)
                results[id] = analysis
                if (analysis.isUsable) {
                    withContext(Dispatchers.Default) { runCatching { store.save(id, analysis) } }
                    store.markStored(id)
                }
            } catch (e: CancellationException) {
                // Called off, not failed: free to be measured later, and not counted as a try.
                attempts[id] = (attempts[id] ?: 1) - 1
                if (running == id) running = null
                publish()
                throw e
            } catch (e: Throwable) {
                failedAt[id] = MonotonicClock.nowMs()
                TrackLog.w(TAG, "automix: analysis of '${song.title}' failed: ${e.message}", about = id)
            }
            if (running == id) running = null
            publish()
        }
    }

    /** [song], measured: from the first copy on the phone, else a fetched one. */
    private suspend fun measure(song: Song): TrackAnalysis {
        val id = song.videoId
        val hintSeconds = song.durationText.durationSeconds()
        val local = IosDownloads.audioPath(id)?.let { it to "download" }
            ?: IosSongCache.peek(id)?.let { it to "song cache" }
            ?: song.localPath?.takeIf { files.fileExistsAtPath(it) }?.let { it to "local file" }
        if (local != null) {
            TrackLog.i(TAG, "automix: measuring '${song.title}' from the ${local.second}", about = id)
            return analyse(id, local.first, hintSeconds)
        }
        if (song.localPath != null || id.startsWith("local:")) error("the local file is gone")
        val fetched = fetch(song)
        try {
            return analyse(id, fetched, hintSeconds)
        } finally {
            files.removeItemAtPath(fetched, null)
        }
    }

    /** Decodes and analyses [path] on the analysis thread. */
    private suspend fun analyse(id: String, path: String, hintSeconds: Double): TrackAnalysis {
        val decoder = FfmpegMonoDecoder(path, IosAnalyzerNative.sampleRate.toInt(), MAX_SAMPLES)
        val measured = onAnalysisThread(onCancel = decoder::cancel) {
            val started = MonotonicClock.nowMs()
            val decoded = decoder.decode(hintSeconds)
            val decodedAt = MonotonicClock.nowMs()
            val duration = decoded.containerSeconds.takeIf { it > 0 } ?: hintSeconds.takeIf { it > 0 } ?: decoded.seconds
            // A short decode reads, downstream, as the music ending early: the mix-out lands where the
            // bytes ran out. Refused, since no analysis degrades to a crossfade and a wrong one doesn't.
            if (decoded.seconds < duration * MIN_DECODED_FRACTION) {
                error("decoded ${decoded.seconds.oneDecimal()}s of a ${duration.oneDecimal()}s ${decoded.codec} file; truncated?")
            }
            val json = IosAnalyzerNative.analyze(decoded.samples, decoded.count, duration) ?: error("the analyzer declined it")
            val analysis = IosTrackFeatures.parse(json, id, duration)
            Measured(analysis, decoded, decodedAt - started, MonotonicClock.nowMs() - decodedAt)
        }
        val analysis = measured.analysis
        TrackLog.i(
            TAG,
            "automix: analysed ${measured.decoded.codec} ${measured.decoded.sourceRate} Hz, " +
                "${measured.decoded.seconds.oneDecimal()}s: decode ${measured.decodeMs} ms, analyse ${measured.analyseMs} ms; " +
                "${analysis.bpm.oneDecimal()} bpm (confidence ${analysis.beatConfidence.twoDecimals()}), " +
                "key ${analysis.key.ifBlank { "unknown" }}, ${analysis.downbeats.size} downbeats, " +
                "mix in ${analysis.mixInTime.oneDecimal()}s, out ${analysis.mixOutTime.oneDecimal()}s" +
                if (analysis.isUsable) "" else "; no usable tempo",
            about = id,
        )
        return analysis
    }

    private class Measured(val analysis: TrackAnalysis, val decoded: FfmpegMonoDecoder.Decoded, val decodeMs: Long, val analyseMs: Long)

    /**
     * Runs [block] on a thread of its own at utility QoS, so the measurement yields to playback and
     * the UI, with room on its stack for the analyzer. Cancelling calls [onCancel] and stops waiting;
     * the thread finishes on its own and its result is dropped.
     */
    private suspend fun <T> onAnalysisThread(onCancel: () -> Unit, block: () -> T): T =
        suspendCancellableCoroutine { continuation ->
            val thread = NSThread(block = { continuation.resumeWith(runCatching(block)) })
            thread.setName("BitChord Analysis")
            thread.setStackSize(16uL shl 20)
            thread.setQualityOfService(NSQualityOfServiceUtility)
            continuation.invokeOnCancellation { onCancel() }
            thread.start()
        }

    /**
     * [song]'s YouTube copy at the lowest rung, into [scratch]; the path. On the main thread, as
     * IosDownloads fetches: the requests suspend rather than block, and each chunk's write is small.
     */
    private suspend fun fetch(song: Song): String {
        val id = song.videoId
        val youTubeId = if (IosSources.isSourceTrack(song)) {
            IosSources.youtubeFallback(song) ?: error("no YouTube copy to measure")
        } else {
            id
        }
        if (youTubeId != id) TrackLog.i(TAG, "automix: measuring '${song.title}' on YouTube's $youTubeId", about = id)
        val stream = InnerTubeXResolver.extract(youTubeId, maxKbps = FETCH_KBPS) ?: error("no stream to measure")
        val total = contentLength(stream.url, stream.headers) ?: error("no length to fetch")
        if (total > MAX_FETCH_BYTES) error("too long to measure (${total / (1024 * 1024)} MB)")
        TrackLog.i(TAG, "automix: fetching ${stream.mimeType} ${stream.kbps} kbps, ${total / 1024} KB to measure", about = id)
        val started = MonotonicClock.nowMs()
        val path = "$scratch/${id.hashCode().toUInt()}_${MonotonicClock.nowMs()}.part"
        files.createFileAtPath(path, null, null)
        val handle = NSFileHandle.fileHandleForWritingAtPath(path) ?: error("couldn’t write the audio")
        var position = 0L
        try {
            while (position < total) {
                coroutineContext.ensureActive()
                if (IosListenTogether.state.value.inParty) throw CancellationException("a party started")
                val length = minOf(CHUNK_BYTES, total - position)
                val response = range(stream.url, stream.headers, position, length)
                if (!response.status.isSuccess()) error("HTTP ${response.status.value}")
                // A server that ignores Range answers the whole file; written mid-file, that is junk.
                if (response.status.value == 200 && position > 0) error("range ignored")
                val bytes = response.readRawBytes()
                if (bytes.isEmpty()) error("stalled")
                handle.writeData(bytes.toNSData())
                position += bytes.size
            }
        } catch (e: Throwable) {
            handle.closeFile()
            files.removeItemAtPath(path, null)
            throw e
        }
        handle.closeFile()
        TrackLog.i(TAG, "automix: fetched in ${MonotonicClock.nowMs() - started} ms", about = id)
        return path
    }

    private suspend fun range(url: String, headers: Map<String, String>, position: Long, length: Long): HttpResponse =
        http.get(url) {
            headers.forEach { (name, value) -> header(name, value) }
            header("Range", "bytes=$position-${position + length - 1}")
            timeout {
                requestTimeoutMillis = 60_000
                connectTimeoutMillis = 15_000
            }
        }

    /** As IosDownloads finds it: `clen` on a googlevideo URL, else a one-byte range's Content-Range. */
    private suspend fun contentLength(url: String, headers: Map<String, String>): Long? {
        runCatching { Url(url).parameters["clen"]?.toLongOrNull() }.getOrNull()?.takeIf { it > 0 }?.let { return it }
        return runCatching {
            range(url, headers, 0, 1).headers["Content-Range"]?.substringAfter('/', "")?.toLongOrNull()?.takeIf { it > 0 }
        }.getOrNull()
    }

    /** Puts [id] first among the tracks [states] reports on; true when it wasn't among them. */
    private fun track(id: String): Boolean {
        if (tracked.firstOrNull() == id) return false
        val fresh = !tracked.remove(id)
        tracked.addFirst(id)
        while (tracked.size > MAX_TRACKED) tracked.removeLast()
        return fresh
    }

    private fun publish() {
        _states.value = tracked.associateWith { stateFor(it) }
    }

    private fun ByteArray.toNSData(): NSData =
        if (isEmpty()) NSData() else usePinned { NSData.dataWithBytes(it.addressOf(0), size.toULong()) }

    private fun Double.oneDecimal(): String = ((this * 10).roundToLong() / 10.0).toString()

    private fun Double.twoDecimals(): String = ((this * 100).roundToLong() / 100.0).toString()

    /** "3:27" as seconds, or zero when there is nothing to read. */
    private fun String?.durationSeconds(): Double {
        val parts = this?.split(':')?.mapNotNull { it.trim().toIntOrNull() } ?: return 0.0
        return when (parts.size) {
            2 -> (parts[0] * 60 + parts[1]).toDouble()
            3 -> (parts[0] * 3_600 + parts[1] * 60 + parts[2]).toDouble()
            else -> 0.0
        }
    }
}
