package com.music.bitchord.ios

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.wallClockMs
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile

/** A track in a playlist kept on the phone, under the phone's own LocalPlaylistStore field names. */
@Serializable
internal data class StoredLocalSong(
    val videoId: String,
    val title: String = "",
    val artist: String = "",
    val thumbnailUrl: String? = null,
    val durationText: String? = null,
    val artistId: String? = null,
    val albumId: String? = null,
    val albumName: String? = null,
    val isVideo: Boolean = false,
)

@Serializable
internal data class StoredLocalPlaylist(
    val id: String,
    val title: String,
    val songs: List<StoredLocalSong> = emptyList(),
)

/** A playlist on this phone and nowhere else, as the pages read it. */
internal data class LocalPlaylist(val id: String, val title: String, val songs: List<Song>) {
    val browseId: String get() = "$LOCAL_PLAYLIST_PREFIX$id"

    /** The first track with artwork lends the playlist its cover, as on the phone. */
    val thumbnailUrl: String? get() = songs.firstOrNull { !it.thumbnailUrl.isNullOrBlank() }?.thumbnailUrl

    val subtitle: String get() = "${if (songs.size == 1) "1 song" else "${songs.size} songs"} · On this device"
}

/** What a local playlist's browse id starts with, as the phone names it. */
internal const val LOCAL_PLAYLIST_PREFIX = "local:playlist:"

/**
 * The phone's LocalPlaylistStore: playlists made with no YouTube Music account (or when making one
 * there failed), kept on the phone, newest first. Signed out, they are the only playlists there are;
 * signed in, they sit beside the account's.
 *
 * One JSON file in Application Support — backed up with the phone, unlike Caches.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal object IosLocalPlaylists {
    private val json = Json { ignoreUnknownKeys = true }
    private val files = NSFileManager.defaultManager

    private val path: String by lazy {
        val support = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true).first() as String
        val dir = "$support/BitChord"
        files.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
        "$dir/local_playlists.json"
    }

    private val _playlists = MutableStateFlow<List<LocalPlaylist>>(emptyList())
    val playlists: StateFlow<List<LocalPlaylist>> = _playlists.asStateFlow()

    /** Reads the file. Called once, at launch. */
    fun restore() {
        val text = NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null) ?: return
        val stored = runCatching { json.decodeFromString<List<StoredLocalPlaylist>>(text) }.getOrNull() ?: return
        _playlists.value = stored.map { it.toPlaylist() }
    }

    fun isLocal(browseId: String?) = browseId?.startsWith(LOCAL_PLAYLIST_PREFIX) == true

    /** Takes the raw id, the `local:playlist:` id, or either with YouTube's `VL` in front. */
    fun get(id: String): LocalPlaylist? {
        val clean = id.removePrefix(LOCAL_PLAYLIST_PREFIX).removePrefix("VL")
        return _playlists.value.firstOrNull { it.id == clean || it.id == id || it.browseId == id }
    }

    fun create(title: String, songs: List<Song>): LocalPlaylist {
        var id = "sp_local_${wallClockMs()}"
        while (_playlists.value.any { it.id == id }) id += "_"
        val playlist = LocalPlaylist(id, title.trim().ifBlank { "New playlist" }, songs.map(::portable))
        update(listOf(playlist) + _playlists.value)
        return playlist
    }

    fun rename(id: String, title: String) {
        val target = get(id) ?: return
        update(_playlists.value.map { if (it.id == target.id) it.copy(title = title) else it })
    }

    fun delete(id: String) {
        val target = get(id) ?: return
        update(_playlists.value.filterNot { it.id == target.id })
    }

    /** Adds the tracks not already in the playlist; how many went in. */
    fun add(id: String, songs: List<Song>): Int {
        val target = get(id) ?: return 0
        val have = target.songs.map { it.videoId }.toMutableSet()
        val fresh = songs.filter { have.add(it.videoId) }.map(::portable)
        if (fresh.isNotEmpty()) update(_playlists.value.map { if (it.id == target.id) it.copy(songs = it.songs + fresh) else it })
        return fresh.size
    }

    fun remove(id: String, videoId: String) {
        val target = get(id) ?: return
        update(_playlists.value.map { if (it.id == target.id) it.copy(songs = it.songs.filterNot { s -> s.videoId == videoId }) else it })
    }

    /** Every playlist, for the backup file. */
    fun exportAll(): List<StoredLocalPlaylist> = _playlists.value.map { it.toStored() }

    /** A backup's playlists: one with an id already here replaces it, the rest join. How many came in. */
    fun importAll(stored: List<StoredLocalPlaylist>): Int {
        if (stored.isEmpty()) return 0
        val incoming = stored.map { it.toPlaylist() }
        val ids = incoming.map { it.id }.toSet()
        update(incoming + _playlists.value.filterNot { it.id in ids })
        return incoming.size
    }

    private fun update(list: List<LocalPlaylist>) {
        _playlists.value = list
        val text = json.encodeToString(list.map { it.toStored() })
        NSString.create(string = text).writeToFile(path, atomically = true, encoding = NSUTF8StringEncoding, error = null)
    }

    /**
     * The track as it is kept, and nothing more: a downloaded row's artwork is a file in the app's
     * container, whose path changes between installs, so its remote address is kept instead.
     */
    private fun portable(song: Song): Song {
        val thumbnail = song.thumbnailUrl?.let { url ->
            if (!url.startsWith("/")) url else IosDownloads.tracks.value.firstOrNull { it.videoId == song.videoId }?.remoteThumbnail
        }
        return song.copy(thumbnailUrl = thumbnail).toStored().toSong()
    }

    private fun Song.toStored() =
        StoredLocalSong(videoId, title, artist, thumbnailUrl, durationText, artistId, albumId, albumName, isVideo)

    private fun StoredLocalSong.toSong() = Song(
        videoId = videoId,
        title = title,
        artist = artist,
        thumbnailUrl = thumbnailUrl?.takeIf(String::isNotBlank),
        durationText = durationText?.takeIf(String::isNotBlank),
        artistId = artistId?.takeIf(String::isNotBlank),
        albumId = albumId?.takeIf(String::isNotBlank),
        albumName = albumName?.takeIf(String::isNotBlank),
        isVideo = isVideo,
    )

    private fun LocalPlaylist.toStored() = StoredLocalPlaylist(id, title, songs.map { it.toStored() })

    private fun StoredLocalPlaylist.toPlaylist() =
        LocalPlaylist(id, title, songs.filter { it.videoId.isNotBlank() }.map { it.toSong() })
}
