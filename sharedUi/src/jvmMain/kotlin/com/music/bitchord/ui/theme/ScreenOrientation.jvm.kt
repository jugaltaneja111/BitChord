package com.music.bitchord.ui.theme

import androidx.compose.runtime.Composable

private object FixedScreen : ScreenOrientation {
    override fun landscape() = Unit
    override fun portrait() = Unit
}

// A desktop window doesn't turn.
@Composable
actual fun rememberScreenOrientation(): ScreenOrientation = FixedScreen
