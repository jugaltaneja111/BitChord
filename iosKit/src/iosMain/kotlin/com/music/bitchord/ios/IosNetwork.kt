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
