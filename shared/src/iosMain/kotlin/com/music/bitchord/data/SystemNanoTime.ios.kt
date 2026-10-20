package com.music.bitchord.data

import platform.posix.CLOCK_UPTIME_RAW
import platform.posix.clock_gettime_nsec_np

// mach_absolute_time in nanoseconds — the clock CADisplayLink, and so Compose's frame clock, reads.
actual fun systemNanoTime(): Long = clock_gettime_nsec_np(CLOCK_UPTIME_RAW.toUInt()).toLong()
