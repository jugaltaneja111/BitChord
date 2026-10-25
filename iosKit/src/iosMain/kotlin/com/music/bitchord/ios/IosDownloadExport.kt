package com.music.bitchord.ios

import com.music.bitchord.data.TrackLog
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.darwin.NSObject

/**
 * The phone's "Export compatible downloads", the iOS way: downloaded songs copied to a folder the
 * listener picks in Files — iCloud Drive, a USB drive, a server — named "Artist - Title" and
 * carrying their tags, for any other player to open. The copies in the app stay where they are.
 *
 * A download from before tagging is tagged on the way out (without lyrics: asking the lyric
 * services about a whole library at once is not this button's business). The files reach the
 * picker as hard links under the names they should have, so nothing is copied twice.
 */
@OptIn(ExperimentalForeignApi::class)
internal object IosDownloadExport {
    private const val TAG = "IosDownloadExport"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val files = NSFileManager.defaultManager
    private var delegate: Delegate? = null
    private var staging: String? = null

    /** Offers [videoIds] — every download when null — to the Files picker. */
    fun export(videoIds: Collection<String>? = null) {
        val tracks = IosDownloads.tracks.value.filter { videoIds == null || it.videoId in videoIds }
        if (tracks.isEmpty()) {
            IosPlayerHost.showMessage(tr("No downloads to save"))
            return
        }
        scope.launch {
            if (tracks.size > 1) IosPlayerHost.showMessage(trPlural(tracks.size, "Preparing {0} song…", "Preparing {0} songs…"))
            IosDownloads.ensureTagged(tracks.map { it.videoId })
            cleanUp()
            val dir = NSTemporaryDirectory() + "BitChord export"
            files.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
            staging = dir
            val taken = HashSet<String>()
            val urls = IosDownloads.tracks.value.filter { track -> tracks.any { it.videoId == track.videoId } }.mapNotNull { track ->
                val source = IosDownloads.audioPath(track.videoId) ?: return@mapNotNull null
                val extension = source.substringAfterLast('.', "m4a")
                val name = uniqueName(fileName(track.title, track.artist), extension, taken)
                val target = "$dir/$name"
                // A link costs nothing; a copy is the fallback for a volume that won't link.
                if (!files.linkItemAtPath(source, toPath = target, error = null) &&
                    !files.copyItemAtPath(source, toPath = target, error = null)
                ) {
                    return@mapNotNull null
                }
                NSURL.fileURLWithPath(target)
            }
            if (urls.isEmpty()) {
                IosPlayerHost.showMessage(tr("Couldn’t save the downloads"))
                cleanUp()
                return@launch
            }
            val picker = UIDocumentPickerViewController(forExportingURLs = urls, asCopy = true)
            val handler = Delegate()
            delegate = handler
            picker.delegate = handler
            topController()?.presentViewController(picker, animated = true, completion = null)
                ?: cleanUp()
        }
    }

    private class Delegate : NSObject(), UIDocumentPickerDelegateProtocol {
        override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
            val count = didPickDocumentsAtURLs.size
            IosPlayerHost.showMessage(trPlural(count, "Saved {0} song to Files", "Saved {0} songs to Files"))
            TrackLog.i(TAG, "$count files exported")
            cleanUp()
        }

        override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) = cleanUp()
    }

    private fun cleanUp() {
        delegate = null
        staging?.let { files.removeItemAtPath(it, null) }
        staging = null
    }

    /** "Artist - Title", as the phone names an exported file, with nothing a file system refuses. */
    private fun fileName(title: String, artist: String): String {
        val raw = if (artist.isNotBlank() && artist != "Unknown Artist") "$artist - $title" else title
        return raw.map { c -> if (c == '/' || c == '\\' || c == ':' || c.isISOControl()) '_' else c }
            .joinToString("").trim().trim('.').take(120).ifBlank { "Track" }
    }

    private fun uniqueName(base: String, extension: String, taken: MutableSet<String>): String {
        var name = "$base.$extension"
        var n = 1
        while (!taken.add(name.lowercase())) name = "$base (${n++}).$extension"
        return name
    }

    private fun topController(): UIViewController? {
        var top: UIViewController? = UIApplication.sharedApplication.keyWindow?.rootViewController
        while (top?.presentedViewController != null) top = top.presentedViewController
        return top
    }
}
