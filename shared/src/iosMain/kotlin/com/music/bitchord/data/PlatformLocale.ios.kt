package com.music.bitchord.data

import platform.Foundation.NSLocale
import platform.Foundation.countryCode
import platform.Foundation.currentLocale
import platform.Foundation.localeIdentifier
import platform.Foundation.preferredLanguages

// The user's first preferred language is what an iOS app is localised into; the current locale
// is the fallback, with its identifier ("en_US") rewritten as a tag.
internal actual fun systemLanguageTag(): String =
    NSLocale.preferredLanguages.firstOrNull() as? String
        ?: NSLocale.currentLocale.localeIdentifier.replace('_', '-')

// Settings > General > Language & Region > Region, whatever language the app is shown in.
internal actual fun systemRegion(): String = NSLocale.currentLocale.countryCode.orEmpty()

private val LANGUAGE_SUBTAG = Regex("[A-Za-z]{2,8}")

actual fun primaryLanguage(tag: String): String =
    tag.substringBefore('-').takeIf { LANGUAGE_SUBTAG.matches(it) }?.lowercase().orEmpty()
