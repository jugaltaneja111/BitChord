package com.music.bitchord.data.sources

import platform.Foundation.NSLocale
import platform.Foundation.NSNumber
import platform.Foundation.NSNumberFormatter
import platform.Foundation.NSNumberFormatterDecimalStyle
import platform.Foundation.NSNumberFormatterRoundHalfUp

internal actual fun oneDecimal(value: Float): String {
    // A fresh formatter per call: NSNumberFormatter is safe to use from any thread, but not to
    // share while another thread reconfigures it.
    val formatter = NSNumberFormatter().apply {
        numberStyle = NSNumberFormatterDecimalStyle
        locale = NSLocale(localeIdentifier = "en_US_POSIX")
        usesGroupingSeparator = false
        minimumFractionDigits = 1u
        maximumFractionDigits = 1u
        roundingMode = NSNumberFormatterRoundHalfUp
    }
    // Widened exactly, so the rounding sees the float's binary value as the JVM does.
    return formatter.stringFromNumber(NSNumber(double = value.toDouble())) ?: value.toString()
}
