package com.music.bitchord.data

/**
 * A monotonic clock in nanoseconds: `System.nanoTime` on the JVM. Playback positions are stamped
 * on it, and the UI compares those stamps with its frame times, so each platform reads the clock
 * its UI's frame clock runs on.
 */
expect fun systemNanoTime(): Long
