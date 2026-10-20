package com.music.bitchord.data

import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.dateWithTimeIntervalSince1970

// Kotlin/Native coroutines have no ThreadContextElement, so nothing carries a track across thread
// hops here: lines are filed against no track, and TrackLog.forTrack falls back to everything it
// holds, as it already does for a track nothing was filed against.
internal actual object TrackContext {
    actual fun current(): String? = null

    actual fun about(id: String?): CoroutineContext = EmptyCoroutineContext
}

internal actual fun clockTime(epochMs: Long): String {
    // NSDateFormatter is thread-safe from iOS 7; a fresh one keeps this free of shared state.
    val formatter = NSDateFormatter().apply {
        dateFormat = "HH:mm:ss.SSS"
        locale = NSLocale(localeIdentifier = "en_US_POSIX")
    }
    return formatter.stringFromDate(NSDate.dateWithTimeIntervalSince1970(epochMs / 1000.0))
}
