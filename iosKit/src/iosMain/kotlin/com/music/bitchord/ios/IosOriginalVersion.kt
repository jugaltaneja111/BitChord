package com.music.bitchord.ios

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import platform.Foundation.NSUserDefaults

/**
 * The phone's OriginalVersion: tracks the listener sent back to YouTube's own upload with
 * "Revert to original". Each one plays from YouTube from then on — no source stands in for it and
 * no upgrade swaps it — until "Upgrade quality" asks for a better copy again. Kept across launches,
 * the newest [MAX_PINNED].
 */
internal object IosOriginalVersion {
    private const val KEY = "bitchord.original_versions"
    private const val MAX_PINNED = 500

    private val defaults = NSUserDefaults.standardUserDefaults
    private val _pinned = MutableStateFlow(
        defaults.stringForKey(KEY)?.split('\n')?.filter { it.isNotBlank() }?.toSet().orEmpty(),
    )
    val pinned: StateFlow<Set<String>> = _pinned

    fun isPinned(videoId: String?): Boolean = videoId != null && videoId in _pinned.value

    fun pin(videoId: String) {
        if (videoId.isBlank() || isPinned(videoId)) return
        var next = _pinned.value + videoId
        while (next.size > MAX_PINNED) next = next - next.first()
        write(next)
    }

    fun unpin(videoId: String) {
        if (isPinned(videoId)) write(_pinned.value - videoId)
    }

    private fun write(ids: Set<String>) {
        _pinned.value = ids
        defaults.setObject(ids.joinToString("\n"), forKey = KEY)
    }
}
