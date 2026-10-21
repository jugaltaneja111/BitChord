package com.my.kizzy

import java.util.logging.Logger

/**
 * `System.nanoTime` stops during deep sleep on Android, which is exactly when this reading
 * matters, so Android's own clock is used where there is one, found reflectively because this
 * module is shared with the desktop and cannot import it.
 */
private val androidElapsedRealtime: java.lang.reflect.Method? = runCatching {
    Class.forName("android.os.SystemClock").getMethod("elapsedRealtime")
}.getOrNull()

internal actual fun elapsedRealtimeMs(): Long =
    androidElapsedRealtime?.let { runCatching { it.invoke(null) as Long }.getOrNull() }
        ?: (System.nanoTime() / 1_000_000L)

internal actual class KizzyLogger actual constructor(name: String) {
    private val logger = Logger.getLogger("com.my.kizzy.gateway.$name")

    actual fun info(message: String) = logger.info(message)

    actual fun warning(message: String) = logger.warning(message)
}
