package com.music.bitchord.ios

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.lyrics.LyricsRepository
import com.music.bitchord.data.lyrics.toEnhancedLrc
import com.music.bitchord.data.lyrics.toLrc
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.durationMillis
import com.music.bitchord.download.FlacTagger
import com.music.bitchord.download.Mp4Tagger
import com.music.bitchord.download.WebmTagger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import platform.Foundation.NSData
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.closeFile
import platform.Foundation.dataWithBytes
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.fileHandleForReadingAtPath
import platform.Foundation.fileHandleForWritingAtPath
import platform.Foundation.readDataOfLength
import platform.Foundation.seekToFileOffset
import platform.Foundation.writeData
import platform.Foundation.writeToFile
import platform.posix.memcpy

/**
 * The phone's MediaTagger for iOS downloads: title, artist, album, cover and lyrics written into
 * the file itself, so a track saved to Files or opened in another player reads as itself rather
 * than as a video id. The taggers are the phone's own, shared.
 *
 * Best-effort throughout, as there: the download has already landed, so a failure here costs the
 * tags and never the file — every tagger hands back its input unchanged on anything it doesn't
 * recognise, and the file is only replaced once the tagged copy is whole. A FLAC is tagged a
 * front at a time (its metadata is a block chain before the frames, and nothing in it addresses
 * the file by offset), so a hi-res track never has to be held in memory; MP4 and WebM are small
 * enough to rewrite whole.
 */
@OptIn(ExperimentalForeignApi::class)
internal object IosDownloadTags {
    private const val TAG = "IosDownloadTags"
    private const val FLAC_HEAD_BYTES = 16 * 1024 * 1024
    private const val COPY_BYTES: ULong = 1048576u
    private const val LYRICS_WAIT_MS = 30_000L
    private const val MAX_LRC_CHARS = 64_000

    /** One rewrite at a time: two whole tracks in memory at once is what gets an app stopped. */
    private val lock = Mutex()

    /** Lyrics in the two forms the containers take: plain LRC, and LRC with word timings. */
    class Lyrics(val plain: String, val enhanced: String?)

    fun carriesTags(extension: String) = extension in setOf("m4a", "flac", "webm")

    /**
     * [song]'s lyrics to embed, from the sources the listener allows the player to ask, or null.
     * Started beside the transfer, so it is usually in hand by the time the bytes are.
     */
    suspend fun lyricsFor(song: Song): Lyrics? {
        val enabled = IosSettings.lyricsSources.flow.value.toSet()
        val order = IosSettings.lyricsSourceOrder.flow.value
        val sources = if (IosSettings.syncedLyrics.flow.value) order.filter { it in enabled } else emptyList()
        if (sources.isEmpty()) return null
        val found = try {
            withTimeoutOrNull(LYRICS_WAIT_MS) {
                LyricsRepository.lyrics(
                    videoId = song.videoId,
                    title = song.title,
                    artist = song.artist,
                    durationMs = song.durationMillis(),
                    album = song.albumName,
                    sources = sources.toSet(),
                    order = sources,
                    prioritizeSyllableSync = IosSettings.prioritizeSyllableSync.flow.value,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return null
        // Timing with no words is an instrumental, not lyrics.
        if (found.lines.none { it.text.isNotBlank() }) return null
        val plain = found.lines.toLrc().takeIf { it.isNotBlank() && it.length <= MAX_LRC_CHARS } ?: return null
        val enhanced = found.lines.toEnhancedLrc().takeIf { it.isNotBlank() && it.length <= MAX_LRC_CHARS * 2 }
        return Lyrics(plain, enhanced)
    }

    /** Writes [song]'s tags into the file at [path]. True when the file now carries them. */
    suspend fun embed(path: String, song: Song, coverPath: String?, lyrics: Lyrics?): Boolean {
        val extension = path.substringAfterLast('.', "").lowercase()
        if (!carriesTags(extension)) return false
        return lock.withLock {
            withContext(Dispatchers.Default) {
                runCatching { embedNow(path, extension, song, coverPath, lyrics) }
                    .onFailure { TrackLog.w(TAG, "${song.videoId}: couldn’t tag: ${it.message}") }
                    .getOrDefault(false)
            }
        }
    }

    private fun embedNow(path: String, extension: String, song: Song, coverPath: String?, lyrics: Lyrics?): Boolean {
        val cover = coverPath?.let { NSData.dataWithContentsOfFile(it)?.toByteArray() }?.takeIf { it.isNotEmpty() }
        val isPng = cover != null && cover.size > 4 && cover[0] == 0x89.toByte() && cover[1] == 'P'.code.toByte()
        val mime = if (isPng) "image/png" else "image/jpeg"
        if (extension == "flac") return tagFlac(path, song, cover, mime, lyrics)
        val original = NSData.dataWithContentsOfFile(path)?.toByteArray() ?: return false
        val tagged = when (extension) {
            "m4a" -> Mp4Tagger.tag(original, song.title, song.artist, song.albumName, lyrics?.plain, cover, isPng, lyrics?.enhanced)
            else -> WebmTagger.tag(original, song.title, song.artist, song.albumName, lyrics?.plain, cover, mime, lyrics?.enhanced)
        }
        // The same array back means there was nothing safe to do.
        if (tagged === original) return false
        val temp = "$path.tagging"
        if (!tagged.toNSData().writeToFile(temp, atomically = true)) return false
        return replace(path, temp)
    }

    /** The front of the file rewritten, the frames copied through after it a megabyte at a time. */
    private fun tagFlac(path: String, song: Song, cover: ByteArray?, mime: String, lyrics: Lyrics?): Boolean {
        val reader = NSFileHandle.fileHandleForReadingAtPath(path) ?: return false
        try {
            val head = reader.readDataOfLength(FLAC_HEAD_BYTES.toULong()).toByteArray()
            val rewrite = FlacTagger.header(head, song.title, song.artist, song.albumName, lyrics?.plain, cover, mime, lyrics?.enhanced)
                ?: return false
            val temp = "$path.tagging"
            val files = NSFileManager.defaultManager
            files.removeItemAtPath(temp, null)
            files.createFileAtPath(temp, null, null)
            val writer = NSFileHandle.fileHandleForWritingAtPath(temp) ?: return false
            try {
                writer.writeData(rewrite.metadata.toNSData())
                reader.seekToFileOffset(rewrite.audioStart.toULong())
                while (true) {
                    val chunk = reader.readDataOfLength(COPY_BYTES)
                    if (chunk.length == 0uL) break
                    writer.writeData(chunk)
                }
            } finally {
                writer.closeFile()
            }
            return replace(path, temp)
        } finally {
            reader.closeFile()
        }
    }

    /** [temp] over [path] in one move, leaving nothing behind either way. */
    private fun replace(path: String, temp: String): Boolean {
        val files = NSFileManager.defaultManager
        // Swapped by the file system, so a failure leaves the untagged file where it was.
        val replaced = files.replaceItemAtURL(
            NSURL.fileURLWithPath(path), withItemAtURL = NSURL.fileURLWithPath(temp),
            backupItemName = null, options = 0u, resultingItemURL = null, error = null,
        )
        if (!replaced) files.removeItemAtPath(temp, null)
        return replaced
    }

    private fun NSData.toByteArray(): ByteArray {
        val size = length.toInt()
        if (size == 0) return ByteArray(0)
        val out = ByteArray(size)
        out.usePinned { memcpy(it.addressOf(0), bytes, length) }
        return out
    }

    private fun ByteArray.toNSData(): NSData =
        if (isEmpty()) NSData() else usePinned { NSData.dataWithBytes(it.addressOf(0), size.toULong()) }
}
