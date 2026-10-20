package com.music.bitchord.ios

import com.music.bitchord.data.innertube.InnerTubeXResolver
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile

/** InnerTubeX's small settings (client config, visitor data) in the app's user defaults. */
internal class UserDefaultsConfigStore : InnerTubeXResolver.ConfigStore {
    private val defaults = NSUserDefaults.standardUserDefaults

    override fun getString(key: String): String? = defaults.stringForKey(prefixed(key))

    override fun getLong(key: String): Long? =
        defaults.objectForKey(prefixed(key))?.let { defaults.integerForKey(prefixed(key)) }

    override fun putString(key: String, value: String) = defaults.setObject(value, prefixed(key))

    override fun putLong(key: String, value: Long) = defaults.setInteger(value, prefixed(key))

    private fun prefixed(key: String) = "innertubex.$key"
}

/**
 * Player scripts in `innertubex_players` under Caches, as the phone keeps them under its files:
 * a few megabytes each, and the system may purge them, which only costs a download.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal class CachesPlayerCache : InnerTubeXResolver.PlayerCache {
    private val files = NSFileManager.defaultManager
    private val dir: String = run {
        val caches = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true)
            .first() as String
        "$caches/innertubex_players".also {
            files.createDirectoryAtPath(it, withIntermediateDirectories = true, attributes = null, error = null)
        }
    }

    override fun read(key: String): String? =
        NSString.stringWithContentsOfFile("$dir/$key", NSUTF8StringEncoding, null)

    override fun write(key: String, value: String?) {
        val path = "$dir/$key"
        if (value == null) {
            files.removeItemAtPath(path, null)
            return
        }
        // Atomically: written beside the file, then moved over it.
        NSString.create(string = value).writeToFile(path, atomically = true, encoding = NSUTF8StringEncoding, error = null)
        // Players rotate every few days; only the newest are worth their megabytes.
        val names = files.contentsOfDirectoryAtPath(dir, null).orEmpty().map { it as String }
        names.sortedByDescending { name ->
            (files.attributesOfItemAtPath("$dir/$name", null)?.get(NSFileModificationDate) as? NSDate)
                ?.timeIntervalSince1970 ?: 0.0
        }.drop(KEPT_PLAYERS).forEach { files.removeItemAtPath("$dir/$it", null) }
    }

    private companion object {
        const val KEPT_PLAYERS = 3
    }
}
