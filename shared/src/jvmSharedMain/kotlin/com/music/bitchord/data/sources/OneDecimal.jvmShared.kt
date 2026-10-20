package com.music.bitchord.data.sources

import java.util.Locale

actual fun oneDecimal(value: Float): String = "%.1f".format(Locale.ROOT, value)
