package com.music.bitchord.data.innertube

import java.io.File

/** [InnerTubeXResolver.PlayerCache] in `innertubex_players` under [filesDir]. */
class FilePlayerCache(filesDir: File) : InnerTubeXResolver.PlayerCache {
    private val dir = File(filesDir, "innertubex_players").apply { mkdirs() }

    override fun read(key: String): String? = File(dir, key).takeIf { it.isFile }?.readText()

    override fun write(key: String, value: String?) {
        val file = File(dir, key)
        if (value == null) {
            file.delete()
            return
        }
        val tmp = File(dir, "$key.tmp")
        tmp.writeText(value)
        tmp.renameTo(file)
        // Players rotate every few days; only the newest are worth their megabytes.
        dir.listFiles()?.filter { !it.name.endsWith(".tmp") }?.sortedByDescending { it.lastModified() }
            ?.drop(KEPT_PLAYERS)?.forEach { it.delete() }
    }

    private companion object {
        const val KEPT_PLAYERS = 3
    }
}
