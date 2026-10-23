package com.music.bitchord.ios

import com.music.bitchord.data.TrackLog
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import platform.Foundation.NSBundle
import platform.Foundation.NSDate
import platform.Foundation.NSISO8601DateFormatter
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.stringWithContentsOfURL
import platform.Foundation.writeToFile
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.UniformTypeIdentifiers.UTTypeJSON
import platform.darwin.NSObject

/**
 * The phone's backup — every setting except credentials, and every month of listening — as one
 * JSON file in the phone's own schema, so a backup moves either way between the two. Listening is
 * the same format on both, so the Replay comes across whole; a setting comes across where both
 * apps have it under the same name (see [IosPrefs]). The playlists kept on this iPhone ride along
 * under a key of their own, which the phone ignores and its backups simply lack.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal object IosBackup {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    private const val APP_TAG = "bitchord"
    private const val SCHEMA_VERSION = 1

    @Serializable
    data class BackupFile(
        val app: String = APP_TAG,
        val version: Int = SCHEMA_VERSION,
        val versionName: String = "",
        val exportedAt: String = "",
        val settings: Map<String, PrefValue> = emptyMap(),
        val listening: List<StoredBucket> = emptyList(),
        val localPlaylists: List<StoredLocalPlaylist> = emptyList(),
    )

    /** One preference, with the type it has to be restored as; numbers travel as text on purpose. */
    @Serializable
    data class PrefValue(
        val type: String,
        val value: String? = null,
        val values: List<String> = emptyList(),
    )

    /** Writes a backup aside and offers it to the share sheet, where "Save to Files" keeps it. */
    fun export() {
        runCatching {
            IosSettings.autoplay // Every setting registers itself when the settings are first read.
            val now = NSISO8601DateFormatter().stringFromDate(NSDate())
            val file = BackupFile(
                versionName = (NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String).orEmpty(),
                exportedAt = now,
                settings = IosPrefs.all.mapNotNull { (name, entry) ->
                    entry.read()?.let { name to PrefValue(entry.type, it) }
                }.toMap(),
                listening = IosListeningStats.exportAll(),
                localPlaylists = IosLocalPlaylists.exportAll(),
            )
            val path = NSTemporaryDirectory() + "bitchord-backup-${now.take(10)}.json"
            NSString.create(string = json.encodeToString(BackupFile.serializer(), file))
                .writeToFile(path, atomically = true, encoding = NSUTF8StringEncoding, error = null)
            presentShareSheet(listOf(NSURL.fileURLWithPath(path)))
        }.onFailure {
            TrackLog.w("IosBackup", "export failed: ${it.message}")
            IosPlayerHost.showMessage("Couldn’t export your data")
        }
    }

    private var pickerDelegate: PickerDelegate? = null

    /** Opens the Files picker; a chosen backup replaces this phone's settings and listening. */
    fun import() {
        val picker = UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeJSON), asCopy = true)
        val delegate = PickerDelegate()
        pickerDelegate = delegate
        picker.delegate = delegate
        topController()?.presentViewController(picker, animated = true, completion = null)
    }

    private class PickerDelegate : NSObject(), UIDocumentPickerDelegateProtocol {
        override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
            val url = didPickDocumentsAtURLs.firstOrNull() as? NSURL ?: return
            val access = url.startAccessingSecurityScopedResource()
            val text = NSString.stringWithContentsOfURL(url, NSUTF8StringEncoding, null)
            if (access) url.stopAccessingSecurityScopedResource()
            IosPlayerHost.showMessage(
                restore(text).fold(
                    onSuccess = { (months, settings, playlists) ->
                        if (playlists > 0) {
                            "Restored $months months of listening, $settings settings and $playlists playlists"
                        } else {
                            "Restored $months months of listening and $settings settings"
                        }
                    },
                    onFailure = { it.message ?: "Couldn’t read that backup" },
                ),
            )
            pickerDelegate = null
        }

        override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
            pickerDelegate = null
        }
    }

    /**
     * Validated before anything is written: a file that isn't a backup, or is from a newer schema,
     * is refused whole, since half an import leaves two devices' settings mixed together.
     */
    private fun restore(text: String?): Result<Triple<Int, Int, Int>> = runCatching {
        require(!text.isNullOrBlank()) { "Couldn’t open that file" }
        val file = runCatching { json.decodeFromString(BackupFile.serializer(), text) }
            .getOrElse { error("That doesn’t look like a BitChord backup") }
        require(file.app == APP_TAG) { "That backup is from another app" }
        require(file.version <= SCHEMA_VERSION) { "That backup was written by a newer version of BitChord" }
        IosSettings.autoplay
        IosListeningStats.importAll(file.listening)
        val playlists = IosLocalPlaylists.importAll(file.localPlaylists)
        var applied = 0
        file.settings.forEach { (name, value) ->
            val entry = IosPrefs.all[name] ?: return@forEach
            val raw = value.value ?: return@forEach
            if (value.type != entry.type) return@forEach
            entry.write(raw)
            applied++
        }
        Triple(file.listening.size, applied, playlists)
    }

    private fun topController(): UIViewController? {
        var top: UIViewController? = UIApplication.sharedApplication.keyWindow?.rootViewController
        while (top?.presentedViewController != null) top = top.presentedViewController
        return top
    }
}
