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
     * AltStore and SideStore register the group under the installer's team and write its real
     * name into the Info.plist; the widget reads the same key.
     */
    private val group: String by lazy {
        (NSBundle.mainBundle.objectForInfoDictionaryKey("ALTAppGroups") as? List<*>)
            ?.firstOrNull { it is String && it.isNotEmpty() } as? String
            ?: DEFAULT_GROUP
    }

    fun start() {
        if (started) return
        started = true
        val container = NSFileManager.defaultManager.containerURLForSecurityApplicationGroupIdentifier(group)
        if (container == null) {
            TrackLog.i(TAG, "no App Group ($group): the widget has nothing to show")
            return
        }
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
