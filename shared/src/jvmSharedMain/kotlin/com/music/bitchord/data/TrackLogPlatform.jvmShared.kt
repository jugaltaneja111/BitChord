package com.music.bitchord.data

import kotlinx.coroutines.asContextElement
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.CoroutineContext

internal actual object TrackContext {
    private val working = ThreadLocal<String?>()

    actual fun current(): String? = working.get()

    // A ThreadContextElement rather than a bare thread local, because the work hops threads
    // constantly — withContext(IO) for a fetch, Dispatchers.Default for the JS engine — and this
    // follows it, including into every child coroutine.
    actual fun about(id: String?): CoroutineContext = working.asContextElement(id)
}

private val CLOCK = ThreadLocal.withInitial { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }

internal actual fun clockTime(epochMs: Long): String = CLOCK.get().format(Date(epochMs))
