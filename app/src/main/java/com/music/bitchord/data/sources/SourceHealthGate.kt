package com.music.bitchord.data.sources

import com.music.bitchord.data.MonotonicClock
import com.music.bitchord.data.TrackLog

/**
 * Sources that keep failing, set aside for a while instead of asked on every track.
 *
 * An upgrade waits on the sources it asks, so one that is down — an addon whose host answers 503,
 * a search that runs into its 30 s timeout every time, a stream that won't open — costs every
 * track its upgrade, or holds it until the song has changed. After [STRIKES] hard failures in a
 * row a source rests for [REST_MS]; one answer that works clears its record. A miss ("not in this
 * catalogue") is an answer, not a failure, and never counts.
 */
internal object SourceHealthGate {
    private const val TAG = "BitChord"
    private const val STRIKES = 2
    private const val REST_MS = 10 * 60_000L

    /** Strikes this close together are one failure: callers sharing one request each report it. */
    private const val SAME_FAILURE_MS = 3_000L

    private class Record(var strikes: Int = 0, var restingUntil: Long = 0L, var lastStrike: Long = Long.MIN_VALUE / 2)

    private val lock = Any()
    private val records = HashMap<String, Record>()

    /** [configId] failed hard: a timeout, a server error, a stream that wouldn't open. Any thread. */
    fun failed(configId: String, name: String, why: String?) {
        val now = MonotonicClock.nowMs()
        val rested = synchronized(lock) {
            val record = records.getOrPut(configId) { Record() }
            if (record.restingUntil > now || now - record.lastStrike < SAME_FAILURE_MS) return@synchronized false
            record.lastStrike = now
            record.strikes++
            if (record.strikes < STRIKES) return@synchronized false
            record.strikes = 0
            record.restingUntil = now + REST_MS
            true
        }
        if (rested) {
            TrackLog.w(TAG, "$name is not answering${why?.let { " ($it)" } ?: ""}; skipped for ${REST_MS / 60_000} min")
        }
    }

    /** [configId] answered. Any thread. */
    fun answered(configId: String) {
        synchronized(lock) { records.remove(configId) }
    }

    /** Whether [configId] is resting. Any thread. */
    fun isResting(configId: String): Boolean {
        val now = MonotonicClock.nowMs()
        return synchronized(lock) { (records[configId]?.restingUntil ?: 0L) > now }
    }
}
