package com.music.bitchord.ios

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDefaults
import platform.Foundation.create
import platform.Foundation.dataWithBytes
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/**
 * Credentials at rest, as the phone keeps them in its encrypted prefs: the Keychain, readable
 * after the first unlock so background playback can still sign its requests, never backed up
 * to another device.
 *
 * A sideloaded build can be signed without a Keychain entitlement, and then every call fails.
 * Rather than leave such an install unable to stay signed in, the value falls back to the
 * app's own defaults — the same trade the phone makes when its Keystore cannot be used.
 */
@OptIn(ExperimentalForeignApi::class)
internal object IosKeychain {
    private const val SERVICE = "com.music.bitchord.auth"
    private const val FALLBACK_PREFIX = "bitchord.auth.fallback."

    fun get(key: String): String? =
        keychainGet(key) ?: NSUserDefaults.standardUserDefaults.stringForKey(FALLBACK_PREFIX + key)

    fun set(key: String, value: String) {
        keychainDelete(key)
        if (keychainAdd(key, value)) {
            NSUserDefaults.standardUserDefaults.removeObjectForKey(FALLBACK_PREFIX + key)
        } else {
            NSUserDefaults.standardUserDefaults.setObject(value, FALLBACK_PREFIX + key)
        }
    }

    fun remove(key: String) {
        keychainDelete(key)
        NSUserDefaults.standardUserDefaults.removeObjectForKey(FALLBACK_PREFIX + key)
    }

    private fun query(key: String): CFMutableDictionaryRef? {
        val query = CFDictionaryCreateMutable(null, 6, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
        CFDictionaryAddValue(query, kSecClass, kSecClassGenericPassword)
        CFDictionaryAddValue(query, kSecAttrService, CFBridgingRetain(SERVICE))
        CFDictionaryAddValue(query, kSecAttrAccount, CFBridgingRetain(key))
        return query
    }

    private fun keychainGet(key: String): String? = memScoped {
        val query = query(key) ?: return null
        CFDictionaryAddValue(query, kSecReturnData, kCFBooleanTrue)
        CFDictionaryAddValue(query, kSecMatchLimit, kSecMatchLimitOne)
        val result = alloc<CFTypeRefVar>()
        val status = SecItemCopyMatching(query, result.ptr)
        CFRelease(query)
        if (status != errSecSuccess) return null
        val data = CFBridgingRelease(result.value) as? NSData ?: return null
        NSString.create(data = data, encoding = NSUTF8StringEncoding)?.toString()
    }

    private fun keychainAdd(key: String, value: String): Boolean {
        val query = query(key) ?: return false
        val bytes = value.encodeToByteArray()
        val data = bytes.usePinned { NSData.dataWithBytes(it.addressOf(0), bytes.size.toULong()) }
        CFDictionaryAddValue(query, kSecValueData, CFBridgingRetain(data))
        CFDictionaryAddValue(query, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
        val status = SecItemAdd(query, null)
        CFRelease(query)
        return status == errSecSuccess
    }

    private fun keychainDelete(key: String) {
        val query = query(key) ?: return
        SecItemDelete(query)
        CFRelease(query)
    }
}
