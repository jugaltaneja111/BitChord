package com.music.bitchord.data.sources

/**
 * [value] to one decimal place, rounded half up from its exact binary value, with a `.` separator:
 * what `"%.1f".format(Locale.ROOT, value)` gives on the JVM (22.05f is 22.0499…, so "22.0").
 */
internal expect fun oneDecimal(value: Float): String
