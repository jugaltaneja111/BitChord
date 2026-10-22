package com.music.bitchord.ios

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.BooleanVar
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import platform.AVFoundation.AVMetadataCommonKeyAlbumName
import platform.AVFoundation.AVMetadataCommonKeyArtist
import platform.AVFoundation.AVMetadataCommonKeyArtwork
import platform.AVFoundation.AVMetadataCommonKeyTitle
import platform.AVFoundation.AVMetadataIdentifierID3MetadataTrackNumber
import platform.AVFoundation.AVMetadataIdentifieriTunesMetadataTrackNumber
import platform.AVFoundation.AVMetadataItem
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.commonKey
import platform.AVFoundation.commonMetadata
import platform.AVFoundation.dataValue
import platform.AVFoundation.numberValue
import platform.AVFoundation.stringValue
import platform.AVFoundation.duration
import platform.AVFoundation.metadata
import platform.CoreMedia.CMTimeGetSeconds
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileCreationDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSFileSize
import platform.Foundation.NSFileType
import platform.Foundation.NSFileTypeRegular
import platform.Foundation.NSNumber
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.UniformTypeIdentifiers.UTTypeFolder
import platform.darwin.NSObject

/**
 * [name] in Application Support/BitChord, moved there from Documents where earlier builds kept it:
 * Documents is BitChord's folder in the Files app now, which is for the listener's music, and
 * Replay's listening there would be one tap from deleted.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun appDataPath(name: String): String {
    val files = NSFileManager.defaultManager
    val support = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true).first() as String
    val dir = "$support/BitChord"
    files.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
    val path = "$dir/$name"
    val documents = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).first() as String
    if (!files.fileExistsAtPath(path) && files.fileExistsAtPath("$documents/$name")) {
        files.moveItemAtPath("$documents/$name", path, null)
    }
    return path
}

/** An audio file found on the phone, as the index keeps it between launches. */
@Serializable
internal data class LocalTrack(
    /** The song's id: [LOCAL_SONG_PREFIX], the root's tag, then the path under it. */
    val id: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long = 0,
    val trackNumber: Int? = null,
    /** The cover lifted out of the file's tags, by name in the artwork cache. */
    val artworkFile: String? = null,
    val bytes: Long = 0,
    val modifiedAt: Long = 0,
    val addedAt: Long = 0,
)

@Serializable
private data class LocalIndex(val tracks: List<LocalTrack> = emptyList())

/** What a song read off the phone's own files has for an id, before its root and path. */
internal const val LOCAL_SONG_PREFIX = "local:file:"

/**
 * The listener's own audio files, as the phone's Local Music has them — iOS has no MediaStore,
 * so two places are walked instead: BitChord's folder in the Files app (Documents, with a Music
 * folder made ready in it), and one folder picked from Files, held by a security-scoped bookmark
 * and kept open while the app runs.
 *
 * Tags come from AVFoundation (title, artist, album, cover, length) where it can read the
 * container, the file name where it can't — Ogg, Opus and WebM, which only the FFmpeg player
 * opens. The index is kept in Application Support so the list is up at once; the walk runs again
 * at launch and on Rescan, and re-reads only files whose size or date moved.
 *
 * A song's id is its root and its path under it, never the container's absolute path, which
 * changes between installs: a playlist kept on this iPhone still finds the file.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal object IosLocalMusic {
    private const val TAG = "IosLocalMusic"
    private const val BOOKMARK_KEY = "bitchord.local_music.folder_bookmark"
    private const val FOLDER_NAME_KEY = "bitchord.local_music.folder_name"
    private const val DOCUMENTS_TAG = "files"
    private const val FOLDER_TAG = "folder"
    /** Android's floor for "Hide short and non-music audio". */
    private const val MIN_MUSIC_MS = 30_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true }
    private val files = NSFileManager.defaultManager
    private val defaults = NSUserDefaults.standardUserDefaults

    private val documents: String by lazy {
        NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).first() as String
    }

    private val indexPath: String by lazy {
        val support = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true).first() as String
        val dir = "$support/BitChord"
        files.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
        "$dir/local_music.json"
    }

    /** Covers lifted out of the files: Caches, since the next scan can always lift them again. */
    private val artDir: String by lazy {
        val caches = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).first() as String
        "$caches/LocalMusicArt".also {
            files.createDirectoryAtPath(it, withIntermediateDirectories = true, attributes = null, error = null)
        }
    }

    private val _tracks = MutableStateFlow<List<LocalTrack>>(emptyList())

    /** Every file found, whatever the filter says; [songs] is what the pages show. */
    val tracks: StateFlow<List<LocalTrack>> = _tracks.asStateFlow()

    /** The songs the pages show: everything found, less short and non-music audio when that is hidden. */
    val songs: StateFlow<List<Song>> = combine(_tracks, IosSettings.hideNonMusicAudio.flow) { tracks, hide ->
        tracks.filter { !hide || isMusic(it) }.map(::song)
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _folderName = MutableStateFlow(defaults.stringForKey(FOLDER_NAME_KEY))
    /** The picked folder's name, or null when none is picked. */
    val folderName: StateFlow<String?> = _folderName.asStateFlow()

    /** The picked folder, resolved from its bookmark and open for reading until the app quits. */
    private var folder: NSURL? = null
    private var scanJob: Job? = null
    private var pickerDelegate: PickerDelegate? = null

    fun isLocal(videoId: String?) = videoId?.startsWith(LOCAL_SONG_PREFIX) == true

    fun isLocal(song: Song) = isLocal(song.videoId)

    /** Reads the index, opens the picked folder and walks both places again. Called once, at launch. */
    fun restore() {
        NSString.stringWithContentsOfFile(indexPath, NSUTF8StringEncoding, null)?.let { text ->
            runCatching { json.decodeFromString<LocalIndex>(text) }.getOrNull()?.let { _tracks.value = it.tracks }
        }
        // The folder the Files app shows as BitChord's, ready for music to be dropped in.
        files.createDirectoryAtPath("$documents/Music", withIntermediateDirectories = true, attributes = null, error = null)
        openFolder()
        rescan()
    }

    /** The file to play for [videoId], when it is one of these and is still there. */
    fun audioPath(videoId: String): String? {
        if (!isLocal(videoId)) return null
        return absolutePath(videoId)?.takeIf { files.fileExistsAtPath(it) }
    }

    /** Walks both places again; one already running starts over, so it sees the latest folder. */
    fun rescan() {
        scanJob?.cancel()
        scanJob = scope.launch {
            _scanning.value = true
            try {
                scan()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                TrackLog.w(TAG, "scan failed: ${e.message}")
            } finally {
                _scanning.value = false
            }
        }
    }

    /** Opens the Files picker for a folder; the one chosen replaces any picked before. */
    fun chooseFolder() {
        val picker = UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeFolder))
        val delegate = PickerDelegate()
        pickerDelegate = delegate
        picker.delegate = delegate
        topController()?.presentViewController(picker, animated = true, completion = null)
    }

    /** Opens the Files app on BitChord's Music folder, where songs copied in are found. */
    fun openInFiles() {
        val url = NSURL.URLWithString("shareddocuments://" + NSURL.fileURLWithPath("$documents/Music").path) ?: return
        UIApplication.sharedApplication.openURL(url, emptyMap<Any?, Any>(), null)
    }

    /** Stops reading the picked folder; its songs leave the list, the files stay where they are. */
    fun forgetFolder() {
        folder?.stopAccessingSecurityScopedResource()
        folder = null
        defaults.removeObjectForKey(BOOKMARK_KEY)
        defaults.removeObjectForKey(FOLDER_NAME_KEY)
        _folderName.value = null
        rescan()
    }

    private class PickerDelegate : NSObject(), UIDocumentPickerDelegateProtocol {
        override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
            (didPickDocumentsAtURLs.firstOrNull() as? NSURL)?.let(::adopt)
            pickerDelegate = null
        }

        override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
            pickerDelegate = null
        }
    }

    private fun adopt(url: NSURL) {
        folder?.stopAccessingSecurityScopedResource()
        folder = null
        if (!url.startAccessingSecurityScopedResource()) {
            IosPlayerHost.showMessage("BitChord couldn’t open that folder")
            return
        }
        // On iOS a bookmark made while the folder is open carries its security scope with it.
        val bookmark = url.bookmarkDataWithOptions(0u, includingResourceValuesForKeys = null, relativeToURL = null, error = null)
        if (bookmark == null) {
            url.stopAccessingSecurityScopedResource()
            IosPlayerHost.showMessage("BitChord couldn’t keep that folder")
            return
        }
        folder = url
        defaults.setObject(bookmark, BOOKMARK_KEY)
        val name = url.lastPathComponent ?: "Folder"
        defaults.setObject(name, FOLDER_NAME_KEY)
        _folderName.value = name
        IosPlayerHost.showMessage("Looking for music in “$name”")
        rescan()
    }

    /** The bookmark back to a folder, opened; made again when iOS says it has gone stale. */
    private fun openFolder() {
        val bookmark = defaults.dataForKey(BOOKMARK_KEY) ?: return
        memScoped {
            val stale = alloc<BooleanVar>()
            val url = NSURL.URLByResolvingBookmarkData(bookmark, options = 0u, relativeToURL = null, bookmarkDataIsStale = stale.ptr, error = null)
            if (url == null || !url.startAccessingSecurityScopedResource()) {
                TrackLog.w(TAG, "the music folder's bookmark no longer opens")
                return
            }
            folder = url
            if (stale.value) {
                url.bookmarkDataWithOptions(0u, includingResourceValuesForKeys = null, relativeToURL = null, error = null)
                    ?.let { defaults.setObject(it, BOOKMARK_KEY) }
            }
            url.lastPathComponent?.let {
                defaults.setObject(it, FOLDER_NAME_KEY)
                _folderName.value = it
            }
        }
    }

    /** The places walked, by tag. A picked folder inside BitChord's own is walked as part of it. */
    private fun roots(): List<Pair<String, String>> = buildList {
        add(DOCUMENTS_TAG to documents)
        folder?.path?.let { path ->
            if (path != documents && !path.startsWith("$documents/")) add(FOLDER_TAG to path)
        }
    }

    private fun absolutePath(id: String): String? {
        val rest = id.removePrefix(LOCAL_SONG_PREFIX)
        val tag = rest.substringBefore('/')
        val relative = rest.substringAfter('/', "").takeIf(String::isNotEmpty) ?: return null
        val root = roots().firstOrNull { it.first == tag }?.second ?: return null
        return "$root/$relative"
    }

    private suspend fun scan() {
        val previous = _tracks.value.associateBy { it.id }
        val found = ArrayList<LocalTrack>()
        for ((tag, root) in roots()) {
            val enumerator = files.enumeratorAtPath(root) ?: continue
            while (true) {
                kotlin.coroutines.coroutineContext.ensureActive()
                val relative = enumerator.nextObject() as? String ?: break
                val name = relative.substringAfterLast('/')
                // Downloads are the app's own and already listed apart; dot files are the
                // system's, and an `.icloud` stand-in is a file not yet on the phone.
                if (name.startsWith(".")) {
                    enumerator.skipDescendants()
                    continue
                }
                if (tag == DOCUMENTS_TAG && EXCLUDED_DOCUMENTS.any { relative == it || relative.startsWith("$it/") }) {
                    enumerator.skipDescendants()
                    continue
                }
                val extension = name.substringAfterLast('.', "").lowercase()
                if (extension !in EXTENSIONS) continue
                val attributes = enumerator.fileAttributes ?: continue
                if (attributes[NSFileType] != NSFileTypeRegular) continue
                val bytes = (attributes[NSFileSize] as? NSNumber)?.longLongValue ?: 0L
                val modified = (attributes[NSFileModificationDate] as? NSDate)?.timeIntervalSince1970?.toLong() ?: 0L
                val id = "$LOCAL_SONG_PREFIX$tag/$relative"
                val known = previous[id]
                val track = if (known != null && known.bytes == bytes && known.modifiedAt == modified &&
                    (known.artworkFile == null || files.fileExistsAtPath("$artDir/${known.artworkFile}"))
                ) {
                    known
                } else {
                    val added = (attributes[NSFileCreationDate] as? NSDate)?.timeIntervalSince1970?.toLong() ?: modified
                    read(id, "$root/$relative", name, bytes, modified, added)
                }
                found += track
                // A first scan of a big folder shows itself filling in rather than all at once.
                if (previous.isEmpty() && found.size % 200 == 0) _tracks.value = found.toList()
            }
        }
        _tracks.value = found
        save(found)
        // Covers of files that have gone.
        val kept = found.mapNotNull { it.artworkFile }.toSet()
        files.contentsOfDirectoryAtPath(artDir, null).orEmpty().filterIsInstance<String>()
            .filter { it !in kept }
            .forEach { files.removeItemAtPath("$artDir/$it", null) }
        TrackLog.i(TAG, "${found.size} files found")
    }

    /** One file's tags; the file name stands in for whatever AVFoundation can't read. */
    private fun read(id: String, path: String, name: String, bytes: Long, modified: Long, added: Long): LocalTrack {
        val parsed = parseFileName(name.substringBeforeLast('.'))
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var durationMs = 0L
        var trackNumber: Int? = null
        var artworkFile: String? = null
        runCatching {
            val asset = AVURLAsset(uRL = NSURL.fileURLWithPath(path), options = null)
            val seconds = CMTimeGetSeconds(asset.duration)
            if (seconds.isFinite() && seconds > 0) durationMs = (seconds * 1000).toLong()
            asset.commonMetadata.filterIsInstance<AVMetadataItem>().forEach { item ->
                when (item.commonKey) {
                    AVMetadataCommonKeyTitle -> title = title ?: item.stringValue?.trim()?.takeIf(String::isNotEmpty)
                    AVMetadataCommonKeyArtist -> artist = artist ?: item.stringValue?.trim()?.takeIf(String::isNotEmpty)
                    AVMetadataCommonKeyAlbumName -> album = album ?: item.stringValue?.trim()?.takeIf(String::isNotEmpty)
                    AVMetadataCommonKeyArtwork -> if (artworkFile == null) {
                        item.dataValue?.takeIf { it.length > 0u }?.let { data ->
                            val file = "${fnv64(id).toULong().toString(16)}.img"
                            if (data.writeToFile("$artDir/$file", atomically = true)) artworkFile = file
                        }
                    }
                }
            }
            trackNumber = asset.metadata.filterIsInstance<AVMetadataItem>().firstNotNullOfOrNull(::trackNumberOf)
        }.onFailure { TrackLog.w(TAG, "couldn’t read the tags of $name: ${it.message}") }
        return LocalTrack(
            id = id,
            title = title ?: parsed.first,
            artist = artist ?: parsed.second,
            album = album,
            durationMs = durationMs,
            trackNumber = trackNumber,
            artworkFile = artworkFile,
            bytes = bytes,
            modifiedAt = modified,
            addedAt = added,
        )
    }

    /** ID3's "3/12" as text, or iTunes' `trkn` as bytes: two of padding, then the number big-endian. */
    private fun trackNumberOf(item: AVMetadataItem): Int? = when (item.identifier) {
        AVMetadataIdentifierID3MetadataTrackNumber ->
            (item.stringValue ?: item.numberValue?.stringValue)?.substringBefore('/')?.trim()?.toIntOrNull()
        AVMetadataIdentifieriTunesMetadataTrackNumber -> item.numberValue?.intValue
            ?: item.dataValue?.takeIf { it.length >= 4u }?.let { data ->
                val bytes = data.bytes?.reinterpret<ByteVar>() ?: return@let null
                ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)
            }
        else -> null
    }?.takeIf { it > 0 }

    /** "Artist - Title", as the desktop reads a file name; just the name when it isn't that. */
    private fun parseFileName(name: String): Pair<String, String> {
        val parts = name.split(" - ", limit = 2)
        return if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
            parts[1].trim() to parts[0].trim()
        } else {
            name to "Unknown Artist"
        }
    }

    /**
     * The phone's filter: thirty seconds or more, and not from a folder that holds ringtones,
     * recordings or the like. A length AVFoundation couldn't read is let through, as there.
     */
    private fun isMusic(track: LocalTrack): Boolean {
        if (track.durationMs in 1 until MIN_MUSIC_MS) return false
        val path = "/" + track.id.removePrefix(LOCAL_SONG_PREFIX).lowercase()
        return NON_MUSIC_SEGMENTS.none { it in path }
    }

    private var lookup: Pair<List<LocalTrack>, Map<String, LocalTrack>>? = null

    /** A song kept elsewhere (a playlist on this iPhone) as the latest scan has it, when it is one of these. */
    fun current(song: Song, tracks: List<LocalTrack> = _tracks.value): Song {
        if (!isLocal(song)) return song
        val byId = lookup?.takeIf { it.first === tracks }?.second
            ?: tracks.associateBy { it.id }.also { lookup = tracks to it }
        return byId[song.videoId]?.let(::song) ?: song
    }

    /** The song as the player and lists have it, cover from the cache. */
    fun song(track: LocalTrack): Song {
        val path = absolutePath(track.id)
        return Song(
            videoId = track.id,
            title = track.title,
            artist = track.artist,
            thumbnailUrl = track.artworkFile?.let { "$artDir/$it" }?.takeIf { files.fileExistsAtPath(it) },
            durationText = track.durationMs.takeIf { it > 0 }?.let(::durationText),
            albumName = track.album,
            localUri = path?.let { NSURL.fileURLWithPath(it).absoluteString },
            localPath = path,
            localDateAddedSeconds = track.addedAt,
            localDateModifiedSeconds = track.modifiedAt,
            trackNumber = track.trackNumber,
        )
    }

    private fun durationText(ms: Long): String {
        val total = ms / 1000
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val seconds = (total % 60).toString().padStart(2, '0')
        return if (hours > 0) "$hours:${minutes.toString().padStart(2, '0')}:$seconds" else "$minutes:$seconds"
    }

    private fun save(tracks: List<LocalTrack>) {
        val text = json.encodeToString(LocalIndex.serializer(), LocalIndex(tracks))
        NSString.create(string = text).writeToFile(indexPath, atomically = true, encoding = NSUTF8StringEncoding, error = null)
    }

    private fun fnv64(text: String): Long {
        var hash = -0x340d631b7bdddcdbL
        text.encodeToByteArray().forEach { byte ->
            hash = hash xor (byte.toLong() and 0xFF)
            hash *= 0x100000001b3L
        }
        return hash
    }

    private fun topController(): UIViewController? {
        var top: UIViewController? = UIApplication.sharedApplication.keyWindow?.rootViewController
        while (top?.presentedViewController != null) top = top.presentedViewController
        return top
    }

    /**
     * What AVPlayer or the FFmpeg player opens. The phone's DSD (dsf, dff), Monkey's Audio and
     * WavPack are left out: neither player here decodes them.
     */
    private val EXTENSIONS = setOf("mp3", "m4a", "mp4", "aac", "flac", "wav", "aif", "aiff", "alac", "caf", "ogg", "oga", "opus", "webm")

    /** BitChord's own folders in Documents, which are not the listener's music. */
    private val EXCLUDED_DOCUMENTS = listOf("BitChordDownloads", "listening")

    /** The phone's folders of things that are not music. */
    private val NON_MUSIC_SEGMENTS = listOf(
        "/alarms/", "/notifications/", "/ringtones/", "/podcasts/", "/audiobooks/", "/recordings/",
        "/voice recorder/", "/sound_recorder/", "/call_rec/", "/whatsapp voice notes/", "/voice memos/",
    )
}
