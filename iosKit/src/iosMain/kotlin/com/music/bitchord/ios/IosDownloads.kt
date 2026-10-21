package com.music.bitchord.ios

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.appHttpClient
import com.music.bitchord.data.innertube.InnerTubeXResolver
import com.music.bitchord.data.model.ArtistRef
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.wallClockMs
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import platform.Foundation.NSData
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.closeFile
import platform.Foundation.create
import platform.Foundation.dataWithBytes
import platform.Foundation.fileHandleForWritingAtPath
import platform.Foundation.seekToEndOfFile
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeData
import platform.Foundation.writeToFile
import kotlin.coroutines.coroutineContext

/** A downloaded track as it is kept on disk: the song's own details, and its files by name. */
@Serializable
internal data class DownloadedTrack(
    val videoId: String,
    val title: String,
    val artist: String,
    val durationText: String? = null,
    val artistId: String? = null,
    val artists: List<StoredArtist> = emptyList(),
    val albumId: String? = null,
    val albumName: String? = null,
    /** The artwork's own address, kept for sharing and for when the copy is gone. */
    val remoteThumbnail: String? = null,
    val audioFile: String,
    val artworkFile: String? = null,
    val bytes: Long = 0,
    val kbps: Int = 0,
    val addedAt: Long = 0,
    /** YouTube's loudness figure for the track, so a download is levelled like the stream. */
    val loudnessDb: Double? = null,
)

@Serializable
internal data class StoredArtist(val name: String, val browseId: String? = null)

/** An album or playlist saved whole: its card on Library's On this iPhone shelf. */
@Serializable
internal data class DownloadedRelease(
    val browseId: String,
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String?,
    val videoIds: List<String>,
)

@Serializable
private data class DownloadIndex(
    val tracks: List<DownloadedTrack> = emptyList(),
    val releases: List<DownloadedRelease> = emptyList(),
)

/** Where a track stands: waiting its turn, coming down, or failed. Done tracks are in [IosDownloads.tracks]. */
internal sealed interface DownloadProgress {
    data object Queued : DownloadProgress
    data class Running(val fraction: Float) : DownloadProgress
    data class Failed(val message: String) : DownloadProgress
}

/**
 * Tracks kept on the phone to play with no connection, as the phone's Downloads does it: the AAC
 * stream resolved as for playback, then pulled onto disk in bounded ranges — googlevideo paces one
 * long response down to playback speed, but serves ranges at line rate — with the resolving
 * client's headers, which the URL is bound to.
 *
 * Files live in Documents/BitChordDownloads, out of iCloud backup: they can always be fetched
 * again. Only names are stored, since the app's container path changes between installs.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal object IosDownloads {
    private const val TAG = "IosDownloads"
    private const val CHUNK_BYTES = 2L * 1024 * 1024

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val json = Json { ignoreUnknownKeys = true }
    private val files = NSFileManager.defaultManager
    private val http by lazy { appHttpClient { install(HttpTimeout) } }

    private val dir: String by lazy {
        val documents = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).first() as String
        "$documents/BitChordDownloads".also { path ->
            files.createDirectoryAtPath(path, withIntermediateDirectories = true, attributes = null, error = null)
            NSURL.fileURLWithPath(path).setResourceValue(NSNumber(bool = true), NSURLIsExcludedFromBackupKey, null)
        }
    }

    private val _tracks = MutableStateFlow<List<DownloadedTrack>>(emptyList())
    /** Every downloaded track, newest first. */
    val tracks: StateFlow<List<DownloadedTrack>> = _tracks.asStateFlow()

    private val _releases = MutableStateFlow<List<DownloadedRelease>>(emptyList())
    val releases: StateFlow<List<DownloadedRelease>> = _releases.asStateFlow()

    private val _progress = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    /** Tracks not yet done, by video id. */
    val progress: StateFlow<Map<String, DownloadProgress>> = _progress.asStateFlow()

    private val _ids = MutableStateFlow<Set<String>>(emptySet())
    /** The downloaded tracks' video ids, for the mark on their rows. */
    val ids: StateFlow<Set<String>> = _ids.asStateFlow()

    private val waiting = ArrayDeque<Song>()
    private val titles = HashMap<String, String>()

    /** What a track in [progress] is called, for its row. */
    fun pendingTitle(videoId: String): String = titles[videoId] ?: videoId
    private var worker: Job? = null
    private var current: String? = null

    /** Reads the index. Called once, at launch. */
    fun restore() {
        val text = NSString.stringWithContentsOfFile("$dir/index.json", NSUTF8StringEncoding, null) ?: return
        val index = runCatching { json.decodeFromString<DownloadIndex>(text) }.getOrNull() ?: return
        // A file removed behind the app's back takes its row with it.
        val present = index.tracks.filter { files.fileExistsAtPath("$dir/${it.audioFile}") }
        publish(present, index.releases)
        if (present.size != index.tracks.size) save()
    }

    fun isDownloaded(videoId: String) = videoId in _ids.value

    /** The file to play for [videoId], when it is on the phone. */
    fun audioPath(videoId: String): String? =
        _tracks.value.firstOrNull { it.videoId == videoId }?.let { "$dir/${it.audioFile}" }
            ?.takeIf { files.fileExistsAtPath(it) }

    /** The song as the player and lists draw it, artwork from the copy on the phone. */
    fun song(track: DownloadedTrack): Song = Song(
        videoId = track.videoId,
        title = track.title,
        artist = track.artist,
        thumbnailUrl = track.artworkFile?.let { "$dir/$it" }?.takeIf { files.fileExistsAtPath(it) } ?: track.remoteThumbnail,
        durationText = track.durationText,
        artistId = track.artistId,
        artists = track.artists.map { ArtistRef(it.name, it.browseId) },
        albumId = track.albumId,
        albumName = track.albumName,
    )

    /** Queues [songs] that are neither on the phone nor already on their way. */
    fun download(songs: List<Song>) {
        val fresh = songs.filter { song ->
            song.videoId !in _ids.value && song.videoId != current &&
                waiting.none { it.videoId == song.videoId }
        }
        if (fresh.isEmpty()) {
            if (songs.isNotEmpty()) IosPlayerHost.showMessage("Already downloaded")
            return
        }
        fresh.forEach {
            waiting.addLast(it)
            titles[it.videoId] = "${it.title} · ${it.artist}"
        }
        _progress.update { map -> map + fresh.associate { it.videoId to DownloadProgress.Queued } }
        IosPlayerHost.showMessage(if (fresh.size == 1) "Downloading “${fresh.first().title}”" else "Downloading ${fresh.size} songs")
        if (worker?.isActive != true) worker = scope.launch { drain() }
    }

    /** A whole album or playlist: its tracks, and a card for it on Library. */
    fun downloadRelease(release: DownloadedRelease, songs: List<Song>) {
        _releases.value = listOf(release.copy(videoIds = songs.map { it.videoId })) +
            _releases.value.filterNot { it.browseId == release.browseId }
        save()
        download(songs)
    }

    fun cancel(videoId: String) {
        waiting.removeAll { it.videoId == videoId }
        if (current == videoId) worker?.cancel()
        _progress.update { it - videoId }
        if (current == videoId) {
            current = null
            if (waiting.isNotEmpty()) worker = scope.launch { drain() }
        }
    }

    fun remove(videoIds: Collection<String>) {
        val gone = videoIds.toSet()
        gone.forEach(::cancel)
        _tracks.value.filter { it.videoId in gone }.forEach { track ->
            files.removeItemAtPath("$dir/${track.audioFile}", null)
            track.artworkFile?.let { files.removeItemAtPath("$dir/$it", null) }
        }
        publish(_tracks.value.filterNot { it.videoId in gone }, _releases.value)
        save()
    }

    /** A saved release's card goes, and with it every track no other saved release still holds. */
    fun removeRelease(browseId: String) {
        val release = _releases.value.firstOrNull { it.browseId == browseId } ?: return
        val others = _releases.value.filter { it.browseId != browseId }
        val kept = others.flatMap { it.videoIds }.toSet()
        _releases.value = others
        remove(release.videoIds.filterNot { it in kept })
    }

    fun removeAll() {
        waiting.clear()
        worker?.cancel()
        current = null
        _progress.value = emptyMap()
        files.contentsOfDirectoryAtPath(dir, null).orEmpty().forEach { files.removeItemAtPath("$dir/$it", null) }
        publish(emptyList(), emptyList())
        save()
    }

    /** Bytes the downloads take on the phone. */
    fun totalBytes(): Long = _tracks.value.sumOf { it.bytes }

    private suspend fun drain() {
        while (true) {
            val song = waiting.removeFirstOrNull() ?: break
            current = song.videoId
            try {
                val track = fetch(song)
                _progress.update { it - song.videoId }
                publish(listOf(track) + _tracks.value.filterNot { it.videoId == track.videoId }, _releases.value)
                save()
            } catch (e: CancellationException) {
                files.removeItemAtPath("$dir/${song.videoId}.part", null)
                throw e
            } catch (e: Exception) {
                TrackLog.w(TAG, "${song.videoId}: ${e.message}")
                files.removeItemAtPath("$dir/${song.videoId}.part", null)
                _progress.update { it + (song.videoId to DownloadProgress.Failed(e.message ?: "Download failed")) }
                IosPlayerHost.showMessage("Couldn’t download “${song.title}”")
            } finally {
                if (current == song.videoId) current = null
            }
        }
        if (_progress.value.isEmpty()) IosPlayerHost.showMessage("Downloads finished")
    }

    private suspend fun fetch(song: Song): DownloadedTrack {
        val maxKbps = IosSettings.downloadKbps.flow.value
        var stream = InnerTubeXResolver.extract(song.videoId, maxKbps = maxKbps, requireM4a = true)
            ?: error("No downloadable stream")
        val total = contentLength(stream.url, stream.headers) ?: error("Track unavailable: no length to fetch")
        val part = "$dir/${song.videoId}.part"
        files.removeItemAtPath(part, null)
        files.createFileAtPath(part, null, null)
        val handle = NSFileHandle.fileHandleForWritingAtPath(part) ?: error("Couldn’t write the download")
        var position = 0L
        var reresolved = false
        try {
            while (position < total) {
                coroutineContext.ensureActive()
                val length = minOf(CHUNK_BYTES, total - position)
                val response = range(stream.url, stream.headers, position, length)
                val code = response.status.value
                // A URL that served its opening and then refuses means the identity behind it was
                // stood down mid-download, not that the track is gone: resolved once more.
                if (code in REFUSAL_CODES) {
                    if (reresolved) error("Download refused (HTTP $code)")
                    reresolved = true
                    stream = InnerTubeXResolver.extract(song.videoId, maxKbps = maxKbps, requireM4a = true)
                        ?: error("No downloadable stream")
                    // Another client can answer with another format: resuming one stream into the
                    // middle of another makes a file the right length and unplayable.
                    if (contentLength(stream.url, stream.headers) != total) error("The stream changed mid-download, try again")
                    continue
                }
                if (!response.status.isSuccess()) error("Download failed (HTTP $code)")
                // A server that ignores Range answers the whole file: written mid-file, that would
                // make something the right length and unplayable.
                if (code == 200 && position > 0) error("The server ignored the range asked for")
                val bytes = response.readRawBytes()
                if (bytes.isEmpty()) error("Download stalled")
                handle.writeData(bytes.toNSData())
                position += bytes.size
                _progress.update { it + (song.videoId to DownloadProgress.Running(position.toFloat() / total)) }
            }
        } finally {
            handle.closeFile()
        }
        val audioFile = "${song.videoId}.m4a"
        files.removeItemAtPath("$dir/$audioFile", null)
        files.moveItemAtPath(part, "$dir/$audioFile", null)
        val artworkFile = song.thumbnailUrl?.let { saveArtwork(song.videoId, it) }
        return DownloadedTrack(
            videoId = song.videoId,
            title = song.title,
            artist = song.artist,
            durationText = song.durationText,
            artistId = song.artistId,
            artists = song.artists.map { StoredArtist(it.name, it.browseId) },
            albumId = song.albumId,
            albumName = song.albumName,
            remoteThumbnail = song.thumbnailUrl?.takeUnless { it.startsWith("/") },
            audioFile = audioFile,
            artworkFile = artworkFile,
            bytes = fileSize("$dir/$audioFile").takeIf { it > 0 } ?: total,
            kbps = stream.kbps,
            addedAt = wallClockMs(),
            loudnessDb = stream.loudnessDb,
        )
    }

    /** The cover beside the audio, so lists and the player have it with no connection. */
    private suspend fun saveArtwork(videoId: String, url: String): String? = runCatching {
        if (url.startsWith("/")) return null
        val response = http.get(url) { timeout { requestTimeoutMillis = 15_000 } }
        if (!response.status.isSuccess()) return null
        val name = "$videoId.jpg"
        response.readRawBytes().toNSData().writeToFile("$dir/$name", atomically = true)
        name
    }.getOrNull()

    private suspend fun range(url: String, headers: Map<String, String>, position: Long, length: Long): HttpResponse =
        http.get(url) {
            headers.forEach { (name, value) -> header(name, value) }
            header("Range", "bytes=$position-${position + length - 1}")
            timeout {
                requestTimeoutMillis = 60_000
                connectTimeoutMillis = 15_000
            }
        }

    /**
     * The whole track's size: a progressive googlevideo URL carries it as `clen`, and for any that
     * doesn't, a one-byte range answers with it in Content-Range.
     */
    private suspend fun contentLength(url: String, headers: Map<String, String>): Long? {
        runCatching { Url(url).parameters["clen"]?.toLongOrNull() }.getOrNull()?.takeIf { it > 0 }?.let { return it }
        return runCatching {
            range(url, headers, 0, 1).headers["Content-Range"]?.substringAfter('/', "")?.toLongOrNull()?.takeIf { it > 0 }
        }.getOrNull()
    }

    private fun fileSize(path: String): Long =
        (files.attributesOfItemAtPath(path, null)?.get(NSFileSize) as? NSNumber)?.longLongValue ?: 0L

    private fun publish(tracks: List<DownloadedTrack>, releases: List<DownloadedRelease>) {
        _tracks.value = tracks
        _releases.value = releases
        _ids.value = tracks.map { it.videoId }.toSet()
    }

    private fun save() {
        val text = json.encodeToString(DownloadIndex.serializer(), DownloadIndex(_tracks.value, _releases.value))
        NSString.create(string = text).writeToFile("$dir/index.json", atomically = true, encoding = NSUTF8StringEncoding, error = null)
    }

    /** The saved releases as Library's On this iPhone cards. */
    fun releaseCards(): List<ShelfItem> = _releases.value.map { release ->
        ShelfItem(
            title = release.title,
            subtitle = release.subtitle,
            thumbnailUrl = release.thumbnailUrl,
            videoId = null,
            browseId = release.browseId,
        )
    }

    private fun ByteArray.toNSData(): NSData =
        if (isEmpty()) NSData() else usePinned { NSData.dataWithBytes(it.addressOf(0), size.toULong()) }

    private val REFUSAL_CODES = setOf(403, 404, 410)
}
