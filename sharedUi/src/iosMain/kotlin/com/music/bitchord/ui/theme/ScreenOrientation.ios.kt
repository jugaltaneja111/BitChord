package com.music.bitchord.ui.theme

import androidx.compose.runtime.Composable

/** Hands the full-screen buttons to the app's [orientationController], which owns the allowed orientations. */
private object ControllerScreen : ScreenOrientation {
    override fun landscape() {
        orientationController?.landscape()
    }

    override fun portrait() {
        orientationController?.portrait()
    }
}

@Composable
actual fun rememberScreenOrientation(): ScreenOrientation = ControllerScreen
