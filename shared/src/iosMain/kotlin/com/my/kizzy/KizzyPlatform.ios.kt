package com.my.kizzy

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import platform.Foundation.NSLog
import platform.posix.CLOCK_MONOTONIC
import platform.posix.clock_gettime_nsec_np

/** Darwin's CLOCK_MONOTONIC keeps counting while the phone sleeps, which is the point here. */
@OptIn(ExperimentalForeignApi::class)
internal actual fun elapsedRealtimeMs(): Long = (clock_gettime_nsec_np(CLOCK_MONOTONIC.convert()) / 1_000_000uL).toLong()

internal actual class KizzyLogger actual constructor(private val name: String) {
    actual fun info(message: String) = NSLog("%@", "[$name] $message")

    actual fun warning(message: String) = NSLog("%@", "[$name] WARNING $message")
}
