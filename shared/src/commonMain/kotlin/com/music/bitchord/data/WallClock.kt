package com.music.bitchord.data

import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Milliseconds since the Unix epoch on the wall clock; `System.currentTimeMillis` on the JVM. */
@OptIn(ExperimentalTime::class)
fun wallClockMs(): Long = Clock.System.now().toEpochMilliseconds()
