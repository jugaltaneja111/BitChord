package com.music.bitchord.ui.components

import androidx.compose.ui.Modifier

// A touch screen has no secondary button; a long press opens the menu, as on the phone.
actual fun Modifier.contextClick(onClick: (() -> Unit)?): Modifier = this
