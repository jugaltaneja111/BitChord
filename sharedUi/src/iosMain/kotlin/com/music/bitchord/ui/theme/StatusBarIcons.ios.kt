package com.music.bitchord.ui.theme

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import platform.Foundation.NSNumber
import platform.Foundation.numberWithLong
import platform.Foundation.setValue
import platform.UIKit.UIApplication
import platform.UIKit.UIStatusBarStyleDarkContent
import platform.UIKit.UIStatusBarStyleLightContent

/**
 * One screen's ask for the status bar. The app shell asks for its theme's glyphs and the player
 * asks for light ones over it; whichever came on screen last is the one shown, and when it leaves
 * the one beneath it is back.
 */
private class StatusBarRequest(var dark: Boolean)

private val requests = mutableListOf<StatusBarRequest>()

// The app's Info.plist leaves the style to the application (UIViewControllerBasedStatusBarAppearance
// off), so it is set there rather than through a view controller Compose owns.
@Composable
actual fun StatusBarIcons(dark: Boolean) {
    val request = remember { StatusBarRequest(dark) }
    DisposableEffect(request) {
        requests += request
        applyStatusBarStyle()
        onDispose {
            requests -= request
            applyStatusBarStyle()
        }
    }
    SideEffect {
        if (request.dark != dark) {
            request.dark = dark
            applyStatusBarStyle()
        }
    }
}

// The setter is deprecated and kept out of Kotlin's UIKit; key-value coding still reaches it.
private fun applyStatusBarStyle() {
    val request = requests.lastOrNull() ?: return
    val style = if (request.dark) UIStatusBarStyleDarkContent else UIStatusBarStyleLightContent
    UIApplication.sharedApplication.setValue(NSNumber.numberWithLong(style), forKey = "statusBarStyle")
}

@Composable
actual fun HideStatusBar(hidden: Boolean) = Unit

// The safe area's top inset, which does not change while the bar is shown.
@Composable
actual fun stableStatusBarInsets(): WindowInsets = WindowInsets.statusBars
