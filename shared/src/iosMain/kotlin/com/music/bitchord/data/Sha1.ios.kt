package com.music.bitchord.data

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA1
import platform.CoreCrypto.CC_SHA1_DIGEST_LENGTH

@OptIn(ExperimentalForeignApi::class)
internal actual fun sha1(bytes: ByteArray): ByteArray {
    val digest = UByteArray(CC_SHA1_DIGEST_LENGTH)
    bytes.usePinned { input ->
        digest.usePinned { output ->
            CC_SHA1(
                if (bytes.isEmpty()) null else input.addressOf(0),
                bytes.size.convert(),
                output.addressOf(0),
            )
        }
    }
    return digest.asByteArray()
}
