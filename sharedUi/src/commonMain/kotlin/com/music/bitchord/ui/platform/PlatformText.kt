package com.music.bitchord.ui.platform

/** [this] in upper case under the device locale's rules (Turkish dotted i, say). */
internal expect fun String.uppercaseInDefaultLocale(): String

/** [offsetMs] as signed seconds to one decimal, in the device locale: "+0.5 s", "-1,2 s". */
internal expect fun signedSeconds(offsetMs: Int): String

/** Which way [ch] runs: [RIGHT_TO_LEFT], [LEFT_TO_RIGHT], or 0 for neutral and weak characters. */
internal expect fun strongDirection(ch: Char): Int

internal const val RIGHT_TO_LEFT = -1
internal const val LEFT_TO_RIGHT = 1
