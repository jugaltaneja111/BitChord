package com.music.bitchord.data.lyrics

import kotlinx.cinterop.BetaInteropApi
import platform.Foundation.NSLocale
import platform.Foundation.NSString
import platform.Foundation.capitalizedStringWithLocale
import platform.Foundation.create
import platform.Foundation.localizedStringForLanguageCode

// NSLocale identifiers spell the region with an underscore; it accepts a BCP 47 tag's hyphen too.
internal actual fun languageDisplayName(code: String, inLocaleTag: String): String =
    NSLocale(localeIdentifier = inLocaleTag).localizedStringForLanguageCode(code).orEmpty()

@OptIn(BetaInteropApi::class)
internal actual fun titlecase(char: Char, localeTag: String): String =
    NSString.create(string = char.toString())
        .capitalizedStringWithLocale(NSLocale(localeIdentifier = localeTag))
