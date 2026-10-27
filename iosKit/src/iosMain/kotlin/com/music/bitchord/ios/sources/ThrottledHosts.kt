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
 * it has, or takes the next source — and the host is tried again once the window has passed. A host
 * that refuses again when it is tried is left longer each time, up to an hour, until it next serves.
 */
internal object ThrottledHosts {
    private const val TAG = "BitChord"
    /** How long a host is left alone after its first, second and later refusals in a row. */
    private val COOLDOWNS_MS = longArrayOf(5 * 60_000L, 15 * 60_000L, 60 * 60_000L)

    private val lock = SynchronizedObject()
    private val until = HashMap<String, Long>()
    /** Refusals in a row per host, cleared by [served]. */
    private val strikes = HashMap<String, Int>()

    /** [url]'s host answered 429; [retryAfterMs] is what it asked for, when it said. Any thread. */
    fun refused(url: String, retryAfterMs: Long? = null) {
        val host = hostOf(url) ?: return
        val now = MonotonicClock.nowMs()
        var cooldown = 0L
        val fresh = synchronized(lock) {
            val was = until[host]
            val isFresh = was == null || was < now
            // One refusal per window: the retries inside it are the same refusal.
            val count = if (isFresh) (strikes[host] ?: 0) + 1 else strikes[host] ?: 1
            strikes[host] = count
            cooldown = maxOf(COOLDOWNS_MS[minOf(count, COOLDOWNS_MS.size) - 1], retryAfterMs ?: 0L)
            until[host] = maxOf(was ?: 0L, now + cooldown)
            isFresh
        }
        if (fresh) TrackLog.w(TAG, "$host is rate-limiting this phone; left alone for ${cooldown / 60_000} min")
    }

    /** [url]'s host served a request: its record is cleared. Any thread. */
    fun served(url: String) {
        val host = hostOf(url) ?: return
        synchronized(lock) {
            if (strikes.remove(host) != null) until.remove(host)
        }
    }

    /** Whether [url]'s host is still cooling down. Any thread. */
    fun isCooling(url: String): Boolean {
        val host = hostOf(url) ?: return false
        return synchronized(lock) {
            val end = until[host] ?: return@synchronized false
            end > MonotonicClock.nowMs()
        }
    }

    private fun hostOf(url: String): String? = runCatching { Url(url).host.lowercase() }.getOrNull()?.takeIf { it.isNotEmpty() }
}
