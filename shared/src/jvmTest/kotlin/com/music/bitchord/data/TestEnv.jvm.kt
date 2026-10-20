package com.music.bitchord.data

actual fun testEnv(name: String): String? = System.getenv(name)
