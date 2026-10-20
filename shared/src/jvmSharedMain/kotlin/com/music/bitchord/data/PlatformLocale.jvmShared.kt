package com.music.bitchord.data

import java.util.Locale

internal actual fun systemLanguageTag(): String = Locale.getDefault().toLanguageTag()

actual fun primaryLanguage(tag: String): String =
    Locale.forLanguageTag(tag).language.lowercase()
