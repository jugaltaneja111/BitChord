package com.music.bitchord.ios

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.appHttpClient
import com.music.bitchord.data.model.artworkAt
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import io.ktor.http.isSuccess
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.Foundation.NSBundle
import platform.Foundation.dataUsingEncoding
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.NSPropertyListSerialization
import platform.Foundation.NSISOLatin1StringEncoding
import platform.Foundation.create
import platform.Foundation.NSString
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDefaults
import platform.Foundation.dataWithBytes
import platform.Foundation.writeToURL
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation

/**
 * The home-screen and lock-screen widget's side in the app: what is playing, written where the
 * widget extension can read it (the App Group both are signed into), then the widget told to
 * redraw. The phone's MediaWidget publishes the same three things — the song, its cover and
 * whether it plays.
 *
 * With no App Group (a signing that left the entitlement out) there is nowhere to write, and the
 * widget shows its "Open BitChord" face.
 */
@OptIn(ExperimentalForeignApi::class, FlowPreview::class)
internal object IosWidget {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var started = false
    private var artFor: String? = null
    private val http by lazy { appHttpClient() }

    /** Set by the app's Swift side: WidgetCenter is Swift-only. */
    var reload: (() -> Unit)? = null

    /**
     * Every App Group name this signing might have granted, in the order the widget tries them
     * (BitChordWidget.swift): AltStore and SideStore write theirs into the Info.plist; a
     * certificate's own profile grants groups of its maker's naming, listed in the embedded
     * profile. The first the system opens a folder for is the one both sides use.
     */
    private fun candidates(): List<String> = buildList {
        (NSBundle.mainBundle.objectForInfoDictionaryKey("ALTAppGroups") as? List<*>)
            ?.filterIsInstance<String>()?.filter { it.isNotEmpty() }?.let(::addAll)
        add(DEFAULT_GROUP)
        addAll(profileGroups("${NSBundle.mainBundle.bundlePath}/embedded.mobileprovision"))
    }

    /** The application-groups a provisioning profile grants: the plist inside its signed envelope. */
    private fun profileGroups(path: String): List<String> {
        val data = NSData.dataWithContentsOfFile(path) ?: return emptyList()
        // Latin-1 keeps every byte one character, so offsets in the text are offsets in the file.
        val text = NSString.create(data = data, encoding = NSISOLatin1StringEncoding)?.toString() ?: return emptyList()
        val start = text.indexOf("<?xml").takeIf { it >= 0 } ?: return emptyList()
        val end = text.indexOf("</plist>", start).takeIf { it >= 0 } ?: return emptyList()
        val plist = text.substring(start, end + "</plist>".length)
        val xml = NSString.create(string = plist).dataUsingEncoding(NSISOLatin1StringEncoding) ?: return emptyList()
        val root = NSPropertyListSerialization.propertyListWithData(xml, 0u, null, null) as? Map<*, *> ?: return emptyList()
        val entitlements = root["Entitlements"] as? Map<*, *> ?: return emptyList()
        return (entitlements["com.apple.security.application-groups"] as? List<*>)
            ?.filterIsInstance<String>()?.filterNot { "*" in it }.orEmpty()
    }

    fun start() {
        if (started) return
        started = true
        val tried = candidates()
        val group = tried.firstOrNull { NSFileManager.defaultManager.containerURLForSecurityApplicationGroupIdentifier(it) != null }
        val container = group?.let { NSFileManager.defaultManager.containerURLForSecurityApplicationGroupIdentifier(it) }
        if (group == null || container == null) {
            TrackLog.i(TAG, "no App Group (tried ${tried.joinToString()}): the widget has nothing to show")
            return
        }
        TrackLog.i(TAG, "widget shares App Group $group")
        val defaults = NSUserDefaults(suiteName = group)
        scope.launch {
            combine(IosPlayer.song, IosPlayer.isPlaying) { song, playing -> song to playing }
                .distinctUntilChanged()
                // A track change flips playing off and on: one redraw, not three.
                .debounce(300)
                .collect { (song, playing) ->
                    defaults.setObject(song?.title, forKey = "title")
                    defaults.setObject(song?.artist, forKey = "artist")
                    defaults.setBool(playing, forKey = "playing")
                    defaults.setBool(song != null, forKey = "hasSong")
                    val cover = song?.thumbnailUrl
                    if (cover != artFor) {
                        artFor = cover
                        val saved = cover?.let { saveArt(it, container.URLByAppendingPathComponent(ART_FILE)) } == true
                        defaults.setBool(saved, forKey = "hasArt")
                        defaults.setDouble(platform.Foundation.NSDate().timeIntervalSince1970, forKey = "artVersion")
                    }
                    reload?.invoke()
                }
        }
    }

    /** The cover, small enough for a widget's memory budget, as a JPEG in the group's folder. */
    private suspend fun saveArt(cover: String, to: platform.Foundation.NSURL?): Boolean {
        to ?: return false
        val image = runCatching {
            if (cover.startsWith("/")) {
                UIImage.imageWithContentsOfFile(cover)
            } else {
                val bytes = withContext(Dispatchers.Default) {
                    val url = cover.artworkAt(ART_PX) ?: cover
                    http.get(url).takeIf { it.status.isSuccess() }?.readRawBytes()
                }
                bytes?.let { UIImage.imageWithData(it.toNSData()) }
            }
        }.onFailure { TrackLog.w(TAG, "widget artwork: ${it.message}") }.getOrNull() ?: return false
        val jpeg = UIImageJPEGRepresentation(image, 0.85) ?: return false
        return jpeg.writeToURL(to, atomically = true)
    }

    private fun ByteArray.toNSData(): NSData =
        if (isEmpty()) NSData() else usePinned { NSData.dataWithBytes(it.addressOf(0), size.toULong()) }

    private const val TAG = "IosWidget"
    private const val DEFAULT_GROUP = "group.com.music.bitchord"
    private const val ART_FILE = "widget-art.jpg"
    private const val ART_PX = 400
}

// ---- Called from Swift: the widget's buttons, as App Intents run in the app ----

/** The widget's play/pause button. On the main thread, as every other control. */
fun widgetTogglePlayPause() {
    ensureStarted()
    IosPlayer.togglePlayPause()
}

/** The widget's next button. */
fun widgetNext() {
    ensureStarted()
    IosPlayer.next()
}

/** The widget's previous button. */
fun widgetPrevious() {
    ensureStarted()
    IosPlayer.previous()
}

/** How the app asks WidgetKit to redraw; installed by the Swift shell at launch, before the app starts. */
fun setWidgetReloader(reload: () -> Unit) {
    IosWidget.reload = reload
}
