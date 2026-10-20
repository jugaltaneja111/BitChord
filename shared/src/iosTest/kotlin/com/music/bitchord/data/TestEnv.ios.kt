package com.music.bitchord.data

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

@OptIn(ExperimentalForeignApi::class)
actual fun testEnv(name: String): String? = getenv(name)?.toKString()
