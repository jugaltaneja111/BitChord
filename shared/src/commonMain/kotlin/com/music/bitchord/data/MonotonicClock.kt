package com.music.bitchord.data

import kotlin.concurrent.Volatile
import kotlin.time.TimeSource

/**
 * Milliseconds on a monotonic clock. The phone installs
 * `SystemClock.elapsedRealtime`, which keeps counting through deep sleep; the
 * desktop keeps the JVM's own.
 */
object MonotonicClock {
    // TimeSource.Monotonic is System.nanoTime on the JVM.
    private val origin = TimeSource.Monotonic.markNow()

    @Volatile
    var nowMs: () -> Long = { origin.elapsedNow().inWholeMilliseconds }
}
