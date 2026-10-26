package com.music.bitchord.ios

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.Network.nw_path_get_status
import platform.Network.nw_path_is_expensive
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_status_satisfied
import platform.darwin.dispatch_get_main_queue

/**
 * Whether the connection costs money, as the phone's `AppSettings.meteredConnection` has it:
 * null with no network, true on mobile data or a personal hotspot (what iOS calls expensive),
 * false on Wi-Fi and wired.
 */
@OptIn(ExperimentalForeignApi::class)
internal object IosNetwork {
    private val _metered = MutableStateFlow<Boolean?>(false)
    val metered: StateFlow<Boolean?> = _metered.asStateFlow()

    private val monitor = nw_path_monitor_create()
    private var started = false

    fun start() {
        if (started) return
        started = true
        nw_path_monitor_set_update_handler(monitor) { path ->
            _metered.value = when {
                path == null || nw_path_get_status(path) != nw_path_status_satisfied -> null
                else -> nw_path_is_expensive(path)
            }
        }
        nw_path_monitor_set_queue(monitor, dispatch_get_main_queue())
        nw_path_monitor_start(monitor)
    }
}

/**
 * What a page says when loading it failed: a plain line for no connection or a timeout, rather
 * than the NSURLError dump Ktor's Darwin engine puts in its message; else the error's own words
 * when they are short enough to read, and [fallback] when they aren't.
 */
internal fun loadError(error: Throwable, fallback: String): String {
    val message = error.message.orEmpty()
    val code = Regex("""NSURLErrorDomain Code=(-?\d+)""").find(message)?.groupValues?.get(1)?.toIntOrNull()
    return when {
        IosNetwork.metered.value == null || code in OFFLINE_CODES ->
            tr("You’re offline. Check your connection, or play your downloads from Library.")
        code == -1001 || "timed out" in message.lowercase() -> tr("The connection timed out. Try again.")
        code != null || message.isBlank() || message.length > 160 -> fallback
        else -> message
    }
}

/** No internet, no route, host not found or unreachable, international roaming off, a call on the line. */
private val OFFLINE_CODES = setOf(-1009, -1004, -1003, -1005, -1018, -1020)
