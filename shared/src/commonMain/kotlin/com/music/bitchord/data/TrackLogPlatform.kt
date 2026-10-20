package com.music.bitchord.data

import kotlin.coroutines.CoroutineContext

/** Which track the current thread's work is for; see [TrackLog.about]. */
internal expect object TrackContext {
    fun current(): String?

    /** A coroutine context under which [current] reads [id], across every thread the work hops to. */
    fun about(id: String?): CoroutineContext
}

/** [epochMs] as local wall-clock time, `HH:mm:ss.SSS`. */
internal expect fun clockTime(epochMs: Long): String
