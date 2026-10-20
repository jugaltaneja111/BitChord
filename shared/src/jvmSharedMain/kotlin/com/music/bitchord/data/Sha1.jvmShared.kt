package com.music.bitchord.data

import java.security.MessageDigest

internal actual fun sha1(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-1").digest(bytes)
