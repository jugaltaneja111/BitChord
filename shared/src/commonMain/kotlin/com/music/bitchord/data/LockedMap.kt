package com.music.bitchord.data

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * A map whose single operations are atomic, for the small shared lookups that were
 * `ConcurrentHashMap`s on the JVM. Like those, a sequence of calls is not atomic as a whole.
 */
internal class LockedMap<K, V> {
    private val lock = SynchronizedObject()
    private val map = HashMap<K, V>()

    val size: Int get() = synchronized(lock) { map.size }

    operator fun get(key: K): V? = synchronized(lock) { map[key] }

    operator fun set(key: K, value: V) {
        synchronized(lock) { map[key] = value }
    }

    /** Stores [value] unless [key] already has one; returns what was there. */
    fun putIfAbsent(key: K, value: V): V? = synchronized(lock) {
        map[key] ?: null.also { map[key] = value }
    }

    fun remove(key: K): V? = synchronized(lock) { map.remove(key) }

    fun clear() {
        synchronized(lock) { map.clear() }
    }
}
