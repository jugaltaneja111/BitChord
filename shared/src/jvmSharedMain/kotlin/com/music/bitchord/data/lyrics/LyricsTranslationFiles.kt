package com.music.bitchord.data.lyrics

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Where translated lyrics are kept between runs — the phone's cache directory, the desktop's
 * media cache. Null keeps them in memory only.
 */
var LyricsTranslation.cacheDir: File?
    get() = (store as? GzipFileStore)?.root
    set(value) {
        store = value?.let(::GzipFileStore)
    }

/** One gzipped JSON file per key, the oldest let go once the folder passes 2 MB. */
private class GzipFileStore(val root: File) : LyricsTranslationStore {
    private val mutex = Mutex()
    private val directory get() = File(root, "lyrics_translation_v3")

    override suspend fun read(key: String): String? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val file = File(directory, "$key.json.gz")
            if (!file.isFile) return@withLock null
            runCatching {
                GZIPInputStream(FileInputStream(file)).bufferedReader().use { it.readText() }
                    .also { file.setLastModified(System.currentTimeMillis()) }
            }.getOrNull()
        }
    }

    override suspend fun write(key: String, text: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val directory = directory
            if (!directory.exists() && !directory.mkdirs()) return@withLock
            val destination = File(directory, "$key.json.gz")
            val temporary = File(directory, "$key.tmp")
            runCatching {
                GZIPOutputStream(FileOutputStream(temporary)).bufferedWriter().use { it.write(text) }
                if (!temporary.renameTo(destination)) {
                    temporary.copyTo(destination, overwrite = true)
                    temporary.delete()
                }
                trim(directory)
            }.onFailure { temporary.delete() }
            Unit
        }
    }

    private fun trim(directory: File) {
        val files = directory.listFiles { file -> file.extension == "gz" }
            ?.sortedByDescending(File::lastModified)
            .orEmpty()
        var kept = 0L
        files.forEach { file ->
            kept += file.length()
            if (kept > MAX_BYTES) file.delete()
        }
    }

    private companion object {
        const val MAX_BYTES = 2L * 1024L * 1024L
    }
}
