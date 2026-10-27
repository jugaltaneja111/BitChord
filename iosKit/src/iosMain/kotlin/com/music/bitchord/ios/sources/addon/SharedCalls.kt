package com.music.bitchord.ios.sources.addon

import com.music.bitchord.data.MonotonicClock
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/**
 * One in-flight-or-recent answer per key, shared by everyone who asks for it.
 *
 * The phone's `module/SharedCalls`, which the addon client borrows; ported here
 * because the module package it lives in is not.
 *
 * A track passes this way several times over — the live resolve, a re-resolve
 * when the player reopens the stream, the read-ahead for the next track — and
 * every one of those used to start from nothing. Measured on the phone, one
 * track in one ninety-second window was searched twice for the identical query
 * and asked twice for the identical stream.
 *
 * Two things are shared here, and they are not the same thing:
 *
 *  - **In flight**, always, whatever [ttlMs] says. Two callers asking the same
 *    question at the same moment is the ordinary case in this layer, because
 *    a search and a stream lookup overlap across tracks.
 *  - **Completed**, for [ttlMs]. Zero means in-flight only, which is right for
 *    an answer that depends on state this cannot see.
 *
 * ### Why the work is not started in the caller's scope
 *
 * Because this layer's callers are *designed* to give up.
 * [SourceResolver.bestAcross][com.music.bitchord.ios.sources.SourceResolver.bestAcross]
 * cancels every source still running as soon as one of them answers. Started in
 * the caller's scope, each of those cancellations tore down a request that had
 * already reached the addon's server — so the server did the work, answered,
 * and found nobody listening. Given a [scope] that outlives any one caller, a
 * cancelled caller drops its `await` and nothing else; the request finishes and
 * lands here, where it is usually wanted again moments later anyway.
 *
 * ### What is not kept
 *
 * A failure. An addon that was briefly unreachable should be asked again on the
 * next track rather than written off for the window. An *empty* answer is not a
 * failure: "this catalogue does not have that recording" is a real answer, and
 * re-asking for it is exactly the waste this exists to stop.
 *
 * The map is guarded by a plain lock rather than the phone's `Mutex` +
 * `ConcurrentHashMap` pair: nothing done while holding it suspends — starting
 * the work is an `async`, not an `await` — so the lookup and the insert are one
 * short critical section either way.
 */
internal class SharedCalls<T>(
    private val ttlMs: Long,
    private val scope: CoroutineScope,
    /** Where a reuse is reported, so a log still shows one line per question asked. */
    private val log: (String) -> Unit = {},
    private val now: () -> Long = { MonotonicClock.nowMs() },
) {

    /**
     * The work behind one key, and when it was started.
     *
     * Held as the running [Deferred] rather than as its result, which is what
     * lets a caller arriving mid-flight wait on it instead of starting a
     * second copy.
     */
    private class Pending<T>(val work: Deferred<Result<T>>, val startedAtMs: Long)

    private val lock = SynchronizedObject()
    private val entries = HashMap<String, Pending<T>>()

    suspend fun get(key: String, describe: () -> String, produce: suspend () -> Result<T>): Result<T> {
        val entry = synchronized(lock) {
            val live = entries[key]?.takeIf { it.work.isActive || now() - it.startedAtMs < ttlMs }
            if (live != null) {
                log(describe() + if (live.work.isActive) " — ALREADY RUNNING" else " — CACHE HIT")
                live
            } else {
                if (entries.size >= MAX_ENTRIES) prune()
                log(describe())
                Pending(scope.async { produce() }, now()).also { entries[key] = it }
            }
        }
        return entry.work.await().also { result ->
            // By identity, so a retry that has already replaced this entry is
            // not thrown away along with the failure it replaced.
            if (result.isFailure) {
                synchronized(lock) { if (entries[key] === entry) entries.remove(key) }
            }
        }
    }

    /** Everything held, dropped — the configuration it was all about is gone. */
    fun clear() = synchronized(lock) { entries.clear() }

    /**
     * Drops answers that have already completed while preserving work still in
     * flight — for an explicit refresh, which should ask the server again
     * rather than accept a cached empty answer, without starting a duplicate
     * of a request already on the wire.
     */
    fun clearCompleted() = synchronized(lock) {
        entries.entries.removeAll { !it.value.work.isActive }
        Unit
    }

    /** Called with [lock] held. */
    private fun prune() {
        val at = now()
        // Never an entry still running: something is waiting on it, and
        // dropping it here would not cancel it, only hide it from the next
        // caller — who would then start a second copy of work already in
        // flight, which is the one thing this class exists to prevent.
        entries.entries.removeAll { !it.value.work.isActive && at - it.value.startedAtMs >= ttlMs }
        if (entries.size >= MAX_ENTRIES) {
            entries.entries
                .filterNot { it.value.work.isActive }
                .minByOrNull { it.value.startedAtMs }
                ?.let { entries.remove(it.key) }
        }
    }

    private companion object {
        /**
         * How many answers to hold. A queue's worth of tracks at two queries
         * and a stream call each, several times over, with room to spare — the
         * point is a bound, not a budget.
         */
        const val MAX_ENTRIES = 128
    }
}
