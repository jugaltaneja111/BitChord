package com.music.bitchord.ui.platform

import java.util.Locale

internal actual fun String.uppercaseInDefaultLocale(): String = uppercase(Locale.getDefault())

internal actual fun signedSeconds(offsetMs: Int): String =
    String.format(Locale.getDefault(), "%+.1f s", offsetMs / 1_000f)

internal actual fun strongDirection(ch: Char): Int = when (Character.getDirectionality(ch)) {
    Character.DIRECTIONALITY_RIGHT_TO_LEFT,
    Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> RIGHT_TO_LEFT
    Character.DIRECTIONALITY_LEFT_TO_RIGHT -> LEFT_TO_RIGHT
    else -> 0
}
