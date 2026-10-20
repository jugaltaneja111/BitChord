package com.music.bitchord.data.lyrics

import java.util.Locale

/** [translationLanguageName] for a locale the JVM already has in hand. */
fun translationLanguageName(code: String, inLocale: Locale): String =
    translationLanguageName(code, inLocale.toLanguageTag())

internal actual fun languageDisplayName(code: String, inLocaleTag: String): String =
    Locale.forLanguageTag(code).getDisplayName(Locale.forLanguageTag(inLocaleTag))

internal actual fun titlecase(char: Char, localeTag: String): String =
    char.titlecase(Locale.forLanguageTag(localeTag))
