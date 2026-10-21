package com.music.bitchord.data

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CCHmac
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.CoreCrypto.kCCHmacAlgSHA256

@OptIn(ExperimentalForeignApi::class)
internal actual fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
    val digest = UByteArray(CC_SHA256_DIGEST_LENGTH)
    key.usePinned { k ->
        message.usePinned { m ->
            digest.usePinned { out ->
                CCHmac(
                    kCCHmacAlgSHA256,
                    if (key.isEmpty()) null else k.addressOf(0),
                    key.size.convert(),
                    if (message.isEmpty()) null else m.addressOf(0),
                    message.size.convert(),
                    out.addressOf(0),
                )
            }
        }
    }
    return digest.asByteArray()
}
