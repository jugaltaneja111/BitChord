package com.music.bitchord.data

/** HMAC-SHA256 of [message] under [key]. For request signing a service requires. */
internal expect fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray
