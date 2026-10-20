package com.music.bitchord.ui.platform

import kotlinx.cinterop.BetaInteropApi
import platform.Foundation.NSLocale
import platform.Foundation.NSNumber
import platform.Foundation.NSNumberFormatter
import platform.Foundation.NSNumberFormatterDecimalStyle
import platform.Foundation.NSString
import platform.Foundation.create
import platform.Foundation.currentLocale
import platform.Foundation.uppercaseStringWithLocale

@OptIn(BetaInteropApi::class)
internal actual fun String.uppercaseInDefaultLocale(): String =
    NSString.create(string = this).uppercaseStringWithLocale(NSLocale.currentLocale)

private val signedTenths = NSNumberFormatter().apply {
    numberStyle = NSNumberFormatterDecimalStyle
    locale = NSLocale.currentLocale
    minimumFractionDigits = 1u
    maximumFractionDigits = 1u
    // Zero included, as "%+.1f" signs it.
    positivePrefix = "+"
}

internal actual fun signedSeconds(offsetMs: Int): String =
    "${signedTenths.stringFromNumber(NSNumber(float = offsetMs / 1_000f))} s"

// Foundation has no per-character bidi class, so the scripts written right to left are named by
// block — Hebrew, Arabic, Syriac, Thaana, NKo, Samaritan, Mandaic, and their presentation forms —
// and other letters count as left to right.
internal actual fun strongDirection(ch: Char): Int = when {
    ch in '֐'..'ࣿ' || ch in 'יִ'..'﷿' || ch in 'ﹰ'..'﻿' ->
        if (ch.isLetter()) RIGHT_TO_LEFT else 0
    ch.isLetter() -> LEFT_TO_RIGHT
    else -> 0
}
