package com.music.bitchord.ios

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.appHttpClient
import com.music.bitchord.data.wallClockMs
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
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
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.closeFile
import platform.Foundation.create
import platform.Foundation.dataWithBytes
import platform.Foundation.fileHandleForWritingAtPath
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeData
import platform.Foundation.writeToFile
import kotlin.coroutines.coroutineContext

/**
 * The phone's song cache: tracks already played, kept on the iPhone so a replay starts at once and
 * plays without a connection, the least recently played let go past the limit set in Settings.
 *
 * The phone keeps the bytes its player streamed. AVPlayer hands none of its own over, so a played
 * track is fetched a second time, in the background, once it has started — and only off mobile
 * data, where that second fetch would cost the listener. It lives under Caches, which the system
 * may empty when space runs short; that only costs a fetch.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal object IosSongCache {
    private const val TAG = "IosSongCache"
    private const val CHUNK_BYTES = 2L * 1024 * 1024

    /** The limit's slider: off at the left, unlimited at the right, 512 MB a step. */
    const val STEP_MB = 512
    const val UNLIMITED_MB = 21 * STEP_MB

    @Serializable
    private data class Entry(
        val videoId: String,
        val file: String,
        val bytes: Long,
        val lastPlayedMs: Long,
        val loudnessDb: Double? = null,
        /** googlevideo's `dur`: the copy is a fragmented MP4, whose header doesn't say. */
        val durationMs: Long? = null,
    )

    @Serializable
    private data class Index(val entries: List<Entry> = emptyList())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val json = Json { ignoreUnknownKeys = true }
    private val files = NSFileManager.defaultManager
    private val http by lazy { appHttpClient { install(HttpTimeout) } }

    private val dir: String by lazy {
        val caches = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).first() as String
        "$caches/BitChordSongCache".also {
            files.createDirectoryAtPath(it, withIntermediateDirectories = true, attributes = null, error = null)
        }
    }

    private var entries: List<Entry> = emptyList()
    private val _bytes = MutableStateFlow(0L)
    /** What the cache holds on disk now, for the Settings row. */
    val bytes: StateFlow<Long> = _bytes.asStateFlow()

    /** Kept as `.webm`, so the engine hands the copy to the FFmpeg player by its name. */
    private val webmIds = mutableSetOf<String>()
    private val waiting = ArrayDeque<Triple<String, String, Pair<Map<String, String>, Double?>>>()
    private var worker: Job? = null

    private val limitBytes: Long?
        get() = when (val mb = IosSettings.songCacheMb.flow.value) {
            0 -> 0L
            UNLIMITED_MB -> null
            else -> mb * 1024L * 1024L
        }

    /** Reads the index. Called once, at launch; a file the system purged takes its row with it. */
    fun restore() {
        val text = NSString.stringWithContentsOfFile("$dir/index.json", NSUTF8StringEncoding, null)
        val index = text?.let { runCatching { json.decodeFromString<Index>(it) }.getOrNull() } ?: Index()
        publish(index.entries.filter { files.fileExistsAtPath("$dir/${it.file}") })
        trim()
    }

    /** The cached file for [videoId] and its loudness, marked as just played; null when not kept. */
    fun audio(videoId: String): Pair<String, Double?>? {
        val entry = entries.firstOrNull { it.videoId == videoId } ?: return null
        val path = "$dir/${entry.file}"
        if (!files.fileExistsAtPath(path)) {
            publish(entries - entry)
            save()
            return null
        }
        publish(listOf(entry.copy(lastPlayedMs = wallClockMs())) + (entries - entry))
        save()
        return path to entry.loudnessDb
    }

    /** How long [videoId]'s kept copy is, when the stream it came from said; else null. */
    fun lengthMs(videoId: String): Long? = entries.firstOrNull { it.videoId == videoId }?.durationMs?.takeIf { it > 0 }

    /**
     * The cached file for [videoId], or null, without marking it played: for Automix's analyser,
     * which reads tracks that may never play and must not keep them from being let go.
     */
    fun peek(videoId: String): String? =
        entries.firstOrNull { it.videoId == videoId }?.let { "$dir/${it.file}" }?.takeIf { files.fileExistsAtPath(it) }

    /**
     * A track has started from the network: keep a copy, unless the cache is off, it is already
     * kept, it is a download, or this is mobile data.
     */
    fun remember(videoId: String, url: String, headers: Map<String, String>, loudnessDb: Double?, webm: Boolean = false) {
        if (limitBytes == 0L || IosNetwork.metered.value == true) return
        if (entries.any { it.videoId == videoId } || IosDownloads.isDownloaded(videoId)) return
        if (waiting.any { it.first == videoId }) return
        waiting.addLast(Triple(videoId, url, headers to loudnessDb))
        if (webm) webmIds += videoId
        // A skip-happy listener queues more than is worth fetching; only the latest few are.
        while (waiting.size > 3) waiting.removeFirst()
        if (worker?.isActive != true) worker = scope.launch { drain() }
    }

    private suspend fun drain() {
        while (true) {
            val (videoId, url, rest) = waiting.removeFirstOrNull() ?: break
            if (IosNetwork.metered.value == true) continue
            val part = "$dir/$videoId.part"
            try {
                val size = fetch(url, rest.first, part)
                val file = if (webmIds.remove(videoId)) "$videoId.webm" else "$videoId.m4a"
                files.removeItemAtPath("$dir/$file", null)
                files.moveItemAtPath(part, "$dir/$file", null)
                val length = runCatching { Url(url).parameters["dur"]?.toDoubleOrNull() }.getOrNull()
                    ?.takeIf { it > 0 }?.let { (it * 1000).toLong() }
                publish(listOf(Entry(videoId, file, size, wallClockMs(), rest.second, length)) + entries.filterNot { it.videoId == videoId })
                trim()
                save()
            } catch (e: CancellationException) {
                files.removeItemAtPath(part, null)
                throw e
            } catch (e: Exception) {
                // A cache that missed a track has only missed it; the next play tries again.
                TrackLog.w(TAG, "$videoId: ${e.message}")
                files.removeItemAtPath(part, null)
            }
        }
    }

    private suspend fun fetch(url: String, headers: Map<String, String>, part: String): Long {
        val total = runCatching { Url(url).parameters["clen"]?.toLongOrNull() }.getOrNull()?.takeIf { it > 0 }
            ?: error("no length to fetch")
        files.removeItemAtPath(part, null)
        files.createFileAtPath(part, null, null)
        val handle = NSFileHandle.fileHandleForWritingAtPath(part) ?: error("couldn’t write")
        var position = 0L
        try {
            while (position < total) {
                coroutineContext.ensureActive()
                if (IosNetwork.metered.value == true) error("moved to mobile data")
                val length = minOf(CHUNK_BYTES, total - position)
                val response = http.get(url) {
                    headers.forEach { (name, value) -> header(name, value) }
                    header("Range", "bytes=$position-${position + length - 1}")
                    timeout {
                        requestTimeoutMillis = 60_000
                        connectTimeoutMillis = 15_000
                    }
                }
                if (!response.status.isSuccess()) error("HTTP ${response.status.value}")
                if (response.status.value == 200 && position > 0) error("range ignored")
                val bytes = response.readRawBytes()
                if (bytes.isEmpty()) error("stalled")
                handle.writeData(bytes.toNSData())
                position += bytes.size
            }
        } finally {
            handle.closeFile()
        }
        return total
    }

    /** Lets the least recently played go until the cache fits its limit; called after any change. */
    fun trim() {
        val limit = limitBytes
        if (limit == null) return
        var kept = 0L
        val (keep, drop) = entries.sortedByDescending { it.lastPlayedMs }.partition { entry ->
            kept += entry.bytes
            kept <= limit
        }
        if (drop.isEmpty()) return
        drop.forEach { files.removeItemAtPath("$dir/${it.file}", null) }
        publish(keep)
        save()
    }

    fun clear() {
        worker?.cancel()
        waiting.clear()
        entries.forEach { files.removeItemAtPath("$dir/${it.file}", null) }
        publish(emptyList())
        save()
    }

    private fun publish(value: List<Entry>) {
        entries = value
        _bytes.value = value.sumOf { it.bytes }
    }

    private fun save() {
        val text = json.encodeToString(Index.serializer(), Index(entries))
        NSString.create(string = text).writeToFile("$dir/index.json", atomically = true, encoding = NSUTF8StringEncoding, error = null)
    }

    private fun ByteArray.toNSData(): NSData =
        if (isEmpty()) NSData() else usePinned { NSData.dataWithBytes(it.addressOf(0), size.toULong()) }
}
