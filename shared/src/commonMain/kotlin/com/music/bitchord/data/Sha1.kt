package com.music.bitchord.data

/** SHA-1 digest of [bytes]. Only for protocols that require it (YouTube's SAPISIDHASH), never for security. */
internal expect fun sha1(bytes: ByteArray): ByteArray
