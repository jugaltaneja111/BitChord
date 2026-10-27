package com.music.bitchord.ios.sources

import com.music.bitchord.data.MonotonicClock
import com.music.bitchord.data.TrackLog
import io.ktor.http.Url
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Hosts that have just answered "too many requests" (HTTP 429), and until when they're left alone.
 *
 * A server that rate-limits this phone goes on refusing for minutes, and every request in that
 * window only extends it. Measured: a FLAC host refused a stream mid-song, and the player's
 * recovery and the next song's upgrade both went straight back to it, stalling twice more. So
 * while a host is cooling down, no source stream on it is offered — the player stays on the copy
 * it has, or takes the next source — and the host is tried again once the window has passed.
 */
internal object ThrottledHosts {
    private const val TAG = "BitChord"
    private const val COOLDOWN_MS = 5 * 60_000L

    private val lock = SynchronizedObject()
    private val until = HashMap<String, Long>()

    /** [url]'s host answered 429; [retryAfterMs] is what it asked for, when it said. Any thread. */
    fun refused(url: String, retryAfterMs: Long? = null) {
        val host = hostOf(url) ?: return
        val end = MonotonicClock.nowMs() + maxOf(COOLDOWN_MS, retryAfterMs ?: 0L)
        val fresh = synchronized(lock) {
            val was = until[host]
            until[host] = maxOf(was ?: 0L, end)
            was == null || was < MonotonicClock.nowMs()
        }
        if (fresh) TrackLog.w(TAG, "$host is rate-limiting this phone; left alone for ${COOLDOWN_MS / 60_000} min")
    }

    /** Whether [url]'s host is still cooling down. Any thread. */
    fun isCooling(url: String): Boolean {
        val host = hostOf(url) ?: return false
        return synchronized(lock) {
            val end = until[host] ?: return@synchronized false
            (end > MonotonicClock.nowMs()).also { if (!it) until.remove(host) }
        }
    }

    private fun hostOf(url: String): String? = runCatching { Url(url).host.lowercase() }.getOrNull()?.takeIf { it.isNotEmpty() }
}
