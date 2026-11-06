@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.music.bitchord.ios

import com.music.bitchord.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.MainScope
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
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

/**
 * Likes for the listener's own files (v1.9's offline player, upstream `OfflinePlaylists`' Liked):
 * a song on this iPhone or the WebDAV server can't be liked on YouTube Music, so its heart is kept
 * here, newest first, and the Local music page lists them as the Liked playlist.
 *
 * Only ids are kept. The rows come from the current scan, so a file that has gone drops out of
 * Liked without the like being forgotten: it comes back if the file does.
 */
internal object IosOfflineLikes {
    private val json = Json { ignoreUnknownKeys = true }
    private val files = NSFileManager.defaultManager

    private val path: String by lazy {
        val support = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true).first() as String
        val dir = "$support/BitChord"
        files.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
        "$dir/offline_likes.json"
    }

    private val _ids = MutableStateFlow<List<String>>(emptyList())
    /** Liked own files' ids, newest first. */
    val ids: StateFlow<List<String>> = _ids.asStateFlow()

    /** The liked files that are there now, in the order they were liked, newest first. */
    val songs: StateFlow<List<Song>> = combine(_ids, IosLocalMusic.songs, IosWebDav.songs) { ids, local, webdav ->
        val byId = (local + webdav).associateBy { it.videoId }
        ids.mapNotNull(byId::get)
    }.stateIn(MainScope(), SharingStarted.Eagerly, emptyList())

    fun restore() {
        val text = NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null) ?: return
        _ids.value = runCatching { json.decodeFromString(ListSerializer(String.serializer()), text) }.getOrNull() ?: return
    }

    fun isLiked(videoId: String): Boolean = videoId in _ids.value

    /** Likes [song], or takes the like back; returns whether it is now liked. */
    fun toggle(song: Song): Boolean {
        val liked = !isLiked(song.videoId)
        _ids.value = if (liked) listOf(song.videoId) + _ids.value else _ids.value - song.videoId
        save()
        return liked
    }

    private fun save() {
        val text = json.encodeToString(ListSerializer(String.serializer()), _ids.value)
        NSString.create(string = text).writeToFile(path, atomically = true, encoding = NSUTF8StringEncoding, error = null)
    }
}
