package com.music.bitchord.data

import java.util.Locale

internal actual fun systemLanguageTag(): String = Locale.getDefault().toLanguageTag()

internal actual fun systemRegion(): String = Locale.getDefault().country

actual fun primaryLanguage(tag: String): String =
    Locale.forLanguageTag(tag).language.lowercase()
