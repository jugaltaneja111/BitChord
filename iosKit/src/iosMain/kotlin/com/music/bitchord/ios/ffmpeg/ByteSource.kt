@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.music.bitchord.ios.ffmpeg

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.appHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.Url
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.plus
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import platform.Foundation.NSCondition
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.posix.O_CREAT
import platform.posix.O_RDONLY
import platform.posix.O_RDWR
import platform.posix.O_TRUNC
import platform.posix.SEEK_END
import platform.posix.close
import platform.posix.lseek
import platform.posix.open
import platform.posix.pread
import platform.posix.pwrite
import platform.posix.unlink

/**
 * Where the demuxer's bytes come from. [read] runs on the decode thread and blocks until the
 * bytes at a position have arrived, the stream has ended, failed, or was cancelled.
 */
internal interface ByteSource {
    /** Copies up to [size] bytes at [position]: the count, 0 at the end, or a negative AVERROR. */
    fun read(position: Long, into: CPointer<ByteVar>, size: Int): Int

    /** The whole length, or -1 while unknown. */
    val size: Long

    /** Whether the demuxer may seek freely: any byte can be had, now or by waiting. */
    val seekable: Boolean

    /** Whether a [read] is waiting on the network. */
    val starved: Boolean

    /** Why the bytes stopped coming for good, when they did. */
    val failure: String?

    /** The manifest's length for a DASH stream, 0 otherwise. */
    val durationHintMs: Long

    /** The chosen DASH representation's bandwidth, 0 otherwise. */
    val bandwidthHint: Long

    /** Unblocks every [read] (they answer AVERROR_EXIT) and stops fetching. Any thread. */
    fun cancel()

    /** Cancels, then lets go of the file. Called once, by the decode thread, after FFmpeg is done with it. */
    fun close()
}

/** A file on the phone: a download. */
@OptIn(ExperimentalForeignApi::class)
internal class FileSource(path: String) : ByteSource {
    private val fd = open(path, O_RDONLY)
    override val size: Long = if (fd >= 0) lseek(fd, 0, SEEK_END) else -1
    override val seekable = true
    override val starved = false
    override val failure: String? = if (fd < 0) "can't open the file" else null
    override val durationHintMs = 0L
    override val bandwidthHint = 0L

    @kotlin.concurrent.Volatile
    private var cancelled = false

    override fun read(position: Long, into: CPointer<ByteVar>, size: Int): Int {
        if (cancelled) return FfmpegLayout.ERROR_EXIT
        if (fd < 0) return ERROR_EIO
        val n = pread(fd, into, size.convert(), position)
        return if (n < 0) ERROR_EIO else n.toInt()
    }

    override fun cancel() {
        cancelled = true
    }

    override fun close() {
        cancel()
        if (fd >= 0) close(fd)
    }
}

/**
 * Byte ranges held, as sorted, disjoint, non-touching [start, end) runs. Not thread-safe: the
 * owner's lock guards it.
 */
internal class Coverage {
    private val runs = ArrayList<LongArray>()

    fun add(start: Long, end: Long) {
        if (end <= start) return
        var s = start
        var e = end
        val kept = ArrayList<LongArray>(runs.size + 1)
        var placed = false
        for (run in runs) {
            when {
                run[1] < s -> kept += run
                run[0] > e -> {
                    if (!placed) {
                        kept += longArrayOf(s, e)
                        placed = true
                    }
                    kept += run
                }
                else -> {
                    s = minOf(s, run[0])
                    e = maxOf(e, run[1])
                }
            }
        }
        if (!placed) kept += longArrayOf(s, e)
        runs.clear()
        runs.addAll(kept)
    }

    /** The end of the run holding [position], or -1 when it isn't held. */
    fun runEnd(position: Long): Long {
        for (run in runs) {
            if (position < run[0]) return -1
            if (position < run[1]) return run[1]
        }
        return -1
    }

    /** The first byte at or after [from] and before [limit] not held, or null. */
    fun nextGap(from: Long, limit: Long): Long? {
        var x = from
        for (run in runs) {
            if (run[1] <= x) continue
            if (run[0] > x) break
            x = run[1]
        }
        return x.takeIf { it < limit }
    }

    /** Where the next held run after [position] starts, or null. */
    fun nextStart(position: Long): Long? = runs.firstOrNull { it[0] > position }?.get(0)

    fun covers(start: Long, end: Long): Boolean {
        val runEnd = runEnd(start)
        return runEnd >= end
    }
}

/**
 * A stream fetched in the background into a temp file the demuxer reads back, the way the phone's
 * player fetches it (ChunkedDataSource under its disk cache), with the resolving client's headers:
 *
 *  - A googlevideo URL (one carrying `clen`) in bounded ranges, back to back: googlevideo paces
 *    one long response down to playback speed, but serves ranges at line rate.
 *  - Anything else (an add-on's or JioSaavn's server) as one open-ended GET read straight
 *    through at line rate, so the whole track is on the phone seconds in and its server has
 *    seen one request. Asking such a server in pieces is what it answers with 429 mid-song.
 *
 * A progressive file is fetched front to back, and a read far from the download edge moves the
 * fetch there (an MP4 with its index at the end, a seek ahead), so the file fills in as runs. A
 * DASH manifest is resolved to its audio representation's segments, which are appended in order
 * so the demuxer sees one fragmented MP4 or WebM; a single-file representation is fetched as a
 * progressive file.
 */
@OptIn(ExperimentalForeignApi::class)
internal class RemoteSource(
    private val url: String,
    private val headers: Map<String, String>,
    private val dash: Boolean,
) : ByteSource {
    private val lock = NSCondition()
    private val path = run {
        // Before this stream's own file exists: the sweep would take it too.
        sweepOnce
        NSTemporaryDirectory().trimEnd('/') + "/$PREFIX${NSUUID().UUIDString}.part"
    }
    private val writeFd = open(path, O_RDWR or O_CREAT or O_TRUNC, 0x180)
    private val readFd = open(path, O_RDONLY)

    // Guarded by lock.
    private val coverage = Coverage()
    private var total = -1L
    private var complete = false
    private var segmented = false
    private var cancelled = false
    private var failed: String? = null
    private var waiting = false
    /** Where the fetch is (or is about to be) working. */
    private var cursor = 0L
    /** A read far from [cursor] asks for the fetch to move here. */
    private var wantFrom: Long? = null
    private var manifestDurationMs = 0L
    private var bandwidth = 0L
    private var closed = false

    private val job: Job

    /**
     * Asked before a read waits: true cuts the wait short (the read answers AVERROR_EXIT), so a
     * seek isn't stuck behind bytes it no longer wants. Called with the lock held; must not block.
     */
    @kotlin.concurrent.Volatile
    var interruptCheck: () -> Boolean = { false }

    init {
        job = scope.launch {
            try {
                if (writeFd < 0 || readFd < 0) error("can't create the stream's temp file")
                if (dash) {
                    val plan = resolveManifest()
                    when (plan) {
                        is DashPlan.Single -> fetchFile(plan.url)
                        is DashPlan.Segments -> fetchSegments(plan.segments)
                    }
                } else {
                    fetchFile(url)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                fail(e.message ?: "download failed")
            }
        }
        job.invokeOnCompletion { if (writeFd >= 0) close(writeFd) }
    }

    override val size: Long get() = locked { total }
    override val seekable: Boolean get() = locked { !segmented || complete }
    override val starved: Boolean get() = locked { waiting }
    override val failure: String? get() = locked { failed }
    override val durationHintMs: Long get() = locked { manifestDurationMs }
    override val bandwidthHint: Long get() = locked { bandwidth }

    override fun read(position: Long, into: CPointer<ByteVar>, size: Int): Int {
        if (size <= 0) return 0
        val available: Long
        lock.lock()
        try {
            while (true) {
                if (cancelled) return FfmpegLayout.ERROR_EXIT
                val end = coverage.runEnd(position)
                if (end > position) {
                    available = minOf(size.toLong(), end - position)
                    break
                }
                if (total >= 0 && position >= total) return 0
                if (failed != null) return ERROR_EIO
                if (interruptCheck()) return FfmpegLayout.ERROR_EXIT
                if (!segmented && (position < cursor || position > cursor + JUMP_BYTES)) wantFrom = position
                waiting = true
                lock.wait()
            }
        } finally {
            waiting = false
            lock.unlock()
        }
        val n = pread(readFd, into, available.convert(), position)
        return if (n < 0) ERROR_EIO else n.toInt()
    }

    /** Re-runs a waiting read's checks: [interruptCheck] may have changed its mind. */
    fun wake() {
        locked { lock.broadcast() }
    }

    override fun cancel() {
        locked {
            cancelled = true
            lock.broadcast()
        }
        job.cancel()
    }

    override fun close() {
        cancel()
        val first = locked { (!closed).also { closed = true } }
        if (!first) return
        if (readFd >= 0) close(readFd)
        // The fetch may still be writing for a moment; its descriptor keeps the unlinked file alive.
        unlink(path)
    }

    /** A single file: in ranges from googlevideo, in one read from anyone else. */
    private suspend fun fetchFile(target: String) {
        val clen = runCatching { Url(target).parameters["clen"]?.toLongOrNull() }.getOrNull()?.takeIf { it > 0 }
        if (clen != null) fetchRanges(target, clen) else fetchWhole(target)
    }

    /**
     * Where the fetch goes next: a seek's position if one was asked for, else the first byte not
     * held from the edge onward, then from the start. Null, and the stream complete, when every
     * byte is held. Called with the lock held.
     */
    private fun nextStart(): Long? {
        wantFrom?.let {
            cursor = it
            wantFrom = null
        }
        val limit = if (total >= 0) total else Long.MAX_VALUE
        val gap = coverage.nextGap(cursor, limit) ?: coverage.nextGap(0, limit)
        if (gap == null) {
            complete = true
            lock.broadcast()
            return null
        }
        cursor = gap
        return gap
    }

    private suspend fun fetchRanges(target: String, clen: Long) {
        locked { total = clen }
        while (true) {
            val (start, end) = locked<Pair<Long, Long>?> {
                val gap = nextStart() ?: return@locked null
                val limit = if (total >= 0) total else Long.MAX_VALUE
                gap to minOf(gap + CHUNK_BYTES, coverage.nextStart(gap) ?: Long.MAX_VALUE, limit)
            } ?: return
            val response = withRetries { get(target, start, end - 1) }
            val code = response.status.value
            val bytes: ByteArray
            when {
                code == 206 -> {
                    response.headers["Content-Range"]?.substringAfter('/', "")?.toLongOrNull()?.let { length ->
                        locked { if (total < 0) total = length }
                    }
                    bytes = response.readRawBytes()
                }
                code == 200 && start == 0L -> {
                    // A server that ignores Range answers the whole file.
                    bytes = response.readRawBytes()
                    locked { total = bytes.size.toLong() }
                }
                code == 200 -> error("the server ignored the range asked for")
                code == 416 && locked { total < 0 } -> {
                    locked { total = start }
                    bytes = ByteArray(0)
                }
                else -> error("HTTP $code")
            }
            if (bytes.isEmpty() && locked { total >= 0 }) error("download stalled")
            write(start, bytes)
            locked {
                coverage.add(start, start + bytes.size)
                cursor = start + bytes.size
                // A short answer with no length given is the end of the file.
                if (total < 0 && bytes.size < end - start) total = start + bytes.size
                lock.broadcast()
            }
        }
    }

    /**
     * As the phone's player reads a source's stream: one open-ended GET poured to disk as fast as
     * it arrives. Another request goes out only when the demuxer jumps past what has arrived (as
     * ExoPlayer reopens on a seek), for the hole that leaves behind, or when the connection drops.
     */
    private suspend fun fetchWhole(target: String) {
        var failures = 0
        while (true) {
            val start = locked { nextStart() } ?: return
            val got = try {
                pour(target, start)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Refused) {
                // The server's answer, already retried as far as it is worth: final.
                throw e
            } catch (e: Throwable) {
                if (++failures > RETRIES) throw e
                TrackLog.d(TAG, "stream broke at ${start}B (try $failures): ${e.message}; picking up from there")
                delay(1000L * failures)
                continue
            }
            if (got > 0) {
                failures = 0
            } else if (++failures > RETRIES) {
                error("download stalled")
            }
        }
    }

    /**
     * One open-ended request from [start], written to the file as it arrives until the end, a
     * byte already held, or a seek elsewhere. How many bytes it brought.
     */
    private suspend fun pour(target: String, start: Long): Long {
        var got = 0L
        var broke: Throwable? = null
        val response = withRetries {
            http.prepareGet(target) {
                this@RemoteSource.headers.forEach { (name, value) -> header(name, value) }
                if (start > 0) header("Range", "bytes=$start-")
                timeout {
                    requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                    connectTimeoutMillis = 15_000
                    socketTimeoutMillis = 30_000
                }
            }.execute { response ->
                val code = response.status.value
                if (code == 200 || code == 206) {
                    try {
                        if (code == 200 && start > 0) error("the server ignored the range asked for")
                        val length = if (code == 206) {
                            response.headers["Content-Range"]?.substringAfter('/', "")?.toLongOrNull()
                        } else {
                            response.headers["Content-Length"]?.toLongOrNull()
                        }
                        locked { if (total < 0 && length != null && length > 0) total = length }
                        got = drain(response.bodyAsChannel(), start)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        // Past the opening: picked up from the edge rather than asked for again.
                        broke = e
                    }
                }
                response
            }
        }
        broke?.let { throw it }
        val code = response.status.value
        when {
            code == 200 || code == 206 -> Unit
            code == 416 && locked { total < 0 } -> locked {
                total = start
                lock.broadcast()
            }
            else -> throw Refused(code)
        }
        return got
    }

    private class Refused(code: Int) : Exception("HTTP $code")

    private suspend fun drain(body: ByteReadChannel, start: Long): Long {
        val buffer = ByteArray(BUFFER_BYTES)
        val stop = locked { coverage.nextStart(start) } ?: Long.MAX_VALUE
        var at = start
        while (at < stop) {
            val n = body.readAvailable(buffer, 0, minOf(buffer.size.toLong(), stop - at).toInt())
            if (n < 0) {
                // The end of the file, when no length was given.
                locked {
                    if (total < 0) total = at
                    lock.broadcast()
                }
                break
            }
            if (n == 0) continue
            write(at, if (n == buffer.size) buffer else buffer.copyOf(n))
            val from = at
            at += n
            val moved = locked {
                coverage.add(from, at)
                cursor = at
                lock.broadcast()
                cancelled || wantFrom != null
            }
            if (moved) break
        }
        return at - start
    }

    private suspend fun fetchSegments(segments: List<DashSegment>) {
        locked { segmented = true }
        var offset = 0L
        for (segment in segments) {
            val response = withRetries { get(segment.url, segment.rangeStart, segment.rangeEnd) }
            val code = response.status.value
            if (code != 200 && code != 206) error("segment HTTP $code")
            val bytes = response.readRawBytes()
            write(offset, bytes)
            val start = offset
            offset += bytes.size
            locked {
                coverage.add(start, offset)
                lock.broadcast()
            }
        }
        locked {
            total = offset
            complete = true
            lock.broadcast()
        }
    }

    private suspend fun resolveManifest(): DashPlan {
        val response = withRetries { get(url, -1, -1) }
        if (response.status.value != 200) error("manifest HTTP ${response.status.value}")
        val plan = Dash.plan(response.bodyAsText(), url)
        locked {
            manifestDurationMs = plan.durationMs
            bandwidth = plan.bandwidth
        }
        TrackLog.d(TAG, "DASH: ${plan.describe()}")
        return plan
    }

    private fun write(offset: Long, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        bytes.usePinned { pinned ->
            var done = 0
            while (done < bytes.size) {
                val n = pwrite(writeFd, pinned.addressOf(done), (bytes.size - done).convert(), offset + done)
                if (n <= 0) error("can't write the stream's temp file")
                done += n.toInt()
            }
        }
    }

    private suspend fun get(target: String, first: Long, last: Long): HttpResponse =
        http.get(target) {
            this@RemoteSource.headers.forEach { (name, value) -> header(name, value) }
            if (first >= 0) header("Range", if (last >= first) "bytes=$first-$last" else "bytes=$first-")
            timeout {
                requestTimeoutMillis = 60_000
                connectTimeoutMillis = 15_000
            }
        }

    /**
     * Network errors and 5xx answers are tried again a couple of times; a 429 — a server asking
     * for a pause — a few more, waiting as long as it asks. Anything else is final.
     */
    private suspend fun withRetries(block: suspend () -> HttpResponse): HttpResponse {
        var attempt = 0
        while (true) {
            var error: Throwable? = null
            val result = try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (attempt >= RETRIES) throw e
                error = e
                null
            }
            val code = result?.status?.value
            if (code == 200 || code == 206) com.music.bitchord.ios.sources.ThrottledHosts.served(result.call.request.url.toString())
            if (code == 429) {
                com.music.bitchord.ios.sources.ThrottledHosts.refused(
                    result.call.request.url.toString(),
                    result.headers["Retry-After"]?.trim()?.toLongOrNull()?.let { it * 1000 },
                )
            }
            // Only googlevideo is waited out. Any other server answering 429 has rate-limited this
            // phone for minutes, and each retry only extends it: the stream fails at once, so the
            // player stays on (or goes back to) the copy it has.
            val limit = when {
                code != 429 -> RETRIES
                result.call.request.url.toString().let { "googlevideo.com" in it || "youtube.com" in it } -> THROTTLED_RETRIES
                else -> 0
            }
            if (result != null && (!retryable(code!!) || attempt >= limit)) return result
            val waitMs = if (result != null) retryAfterMs(result, attempt) else 1000L * (attempt + 1)
            attempt++
            // What the server said, so a log can tell a rate limit from a server falling over.
            val said = result?.let { response ->
                val excerpt = runCatching { response.bodyAsText().take(120).replace('\n', ' ') }.getOrDefault("")
                "HTTP ${response.status.value} from ${response.call.request.url.host}" +
                    (response.headers["Retry-After"]?.let { " retry-after=$it" } ?: "") +
                    (response.headers["Server"]?.let { " server=$it" } ?: "") +
                    if (excerpt.isNotBlank()) " \"$excerpt\"" else ""
            } ?: "no answer (${error?.message})"
            TrackLog.d(TAG, "fetch held off ${waitMs}ms (try $attempt): $said")
            delay(waitMs)
        }
    }

    private fun retryable(code: Int) = code == 429 || code >= 500

    /** The server's Retry-After in seconds, held to something a listener would sit through. */
    private fun retryAfterMs(response: HttpResponse, attempt: Int): Long =
        response.headers["Retry-After"]?.trim()?.toLongOrNull()?.let { it * 1000 }?.coerceIn(500L, MAX_RETRY_WAIT_MS)
            ?: minOf(1000L shl attempt, MAX_RETRY_WAIT_MS)

    private fun fail(message: String) {
        TrackLog.w(TAG, "stream failed: $message")
        locked {
            if (failed == null) failed = message
            lock.broadcast()
        }
    }

    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    private companion object {
        const val TAG = "IosFfmpeg"
        const val CHUNK_BYTES = 1024L * 1024
        const val BUFFER_BYTES = 64 * 1024
        /** A read this far past the fetch's edge moves the fetch rather than waiting for it. */
        const val JUMP_BYTES = 2 * CHUNK_BYTES
        const val RETRIES = 2
        const val THROTTLED_RETRIES = 5
        const val MAX_RETRY_WAIT_MS = 8_000L
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val http: HttpClient by lazy { appHttpClient { install(HttpTimeout) } }

        /** Temp files a previous run of the app left behind (it was killed mid-track), removed once. */
        val sweepOnce: Unit by lazy {
            val dir = NSTemporaryDirectory().trimEnd('/')
            val files = NSFileManager.defaultManager
            files.contentsOfDirectoryAtPath(dir, null).orEmpty()
                .filterIsInstance<String>()
                .filter { it.startsWith(PREFIX) }
                .forEach { files.removeItemAtPath("$dir/$it", null) }
        }
        const val PREFIX = "bitchord-ffmpeg-"
    }
}

/** `AVERROR(EIO)`: EIO is 5 on Darwin. */
internal const val ERROR_EIO = -5
