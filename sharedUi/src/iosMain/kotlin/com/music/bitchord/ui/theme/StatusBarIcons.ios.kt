package com.music.bitchord.ui.theme

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable

// The status bar's style belongs to the hosting view controller on iOS; the app shell sets it
// once, light on the dark UI. Neither call reaches it yet.
@Composable
actual fun StatusBarIcons(dark: Boolean) = Unit

@Composable
actual fun HideStatusBar(hidden: Boolean) = Unit

// The safe area's top inset, which does not change while the bar is shown.
@Composable
actual fun stableStatusBarInsets(): WindowInsets = WindowInsets.statusBars
