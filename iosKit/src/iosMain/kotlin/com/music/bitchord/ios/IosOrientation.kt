@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.music.bitchord.ios

import com.music.bitchord.data.TrackLog
import com.music.bitchord.ui.theme.OrientationController
import platform.UIKit.UIApplication
import platform.UIKit.setNeedsUpdateOfSupportedInterfaceOrientations
import platform.UIKit.UIDevice
import platform.UIKit.UIInterfaceOrientationMaskAll
import platform.UIKit.UIInterfaceOrientationMaskAllButUpsideDown
import platform.UIKit.UIInterfaceOrientationMaskLandscape
import platform.UIKit.UIInterfaceOrientationMaskPortrait
import platform.UIKit.UIUserInterfaceIdiomPad
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.UIKit.UIWindowSceneGeometryPreferencesIOS

/**
 * Which way the iPhone may turn. Portrait, except while Now Playing shows a music video's
 * picture: then sideways too, and turned sideways the player is the video, full screen. The
 * Swift app delegate asks [mask] whenever iOS wants to know; the full-screen buttons ask for a
 * side outright. An iPad turns every way regardless and gets no geometry requests.
 */
internal object IosOrientation : OrientationController {
    private val isPad: Boolean
        get() = UIDevice.currentDevice.userInterfaceIdiom == UIUserInterfaceIdiomPad

    var allowLandscape = false
        set(value) {
            if (field == value) return
            field = value
            refreshAllowed()
            // Never left sideways once the video is gone.
            if (!value && !isPad) request(UIInterfaceOrientationMaskPortrait)
        }

    /** The mask for this device and state; split out from UIKit so it can be tested. */
    fun mask(isPad: Boolean, allowLandscape: Boolean): ULong = when {
        isPad -> UIInterfaceOrientationMaskAll
        allowLandscape -> UIInterfaceOrientationMaskAllButUpsideDown
        else -> UIInterfaceOrientationMaskPortrait
    }

    fun mask(): ULong = mask(isPad, allowLandscape)

    override fun landscape() {
        if (isPad || !allowLandscape) return
        request(UIInterfaceOrientationMaskLandscape)
    }

    override fun portrait() {
        if (isPad) return
        request(UIInterfaceOrientationMaskPortrait)
    }

    private fun keyWindow(): UIWindow? =
        UIApplication.sharedApplication.connectedScenes.firstNotNullOfOrNull { scene ->
            (scene as? UIWindowScene)?.windows?.firstOrNull { (it as? UIWindow)?.isKeyWindow() == true } as? UIWindow
        }

    private fun refreshAllowed() {
        keyWindow()?.rootViewController?.setNeedsUpdateOfSupportedInterfaceOrientations()
    }

    private fun request(mask: ULong) {
        val scene = keyWindow()?.windowScene ?: return
        refreshAllowed()
        scene.requestGeometryUpdateWithPreferences(UIWindowSceneGeometryPreferencesIOS(mask)) { error ->
            TrackLog.w("Orientation", "geometry update refused: ${error?.localizedDescription}")
        }
    }
}
