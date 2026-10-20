package com.music.bitchord.ui.utils

/**
 * The least recently used entries dropped past [maxEntries]: what an access-ordered
 * `LinkedHashMap` with `removeEldestEntry` was on the JVM. Not thread-safe; the caches built on it
 * are read and written on the main thread.
 */
internal class LruCache<K, V : Any>(private val maxEntries: Int) {
    private val map = LinkedHashMap<K, V>()

    operator fun get(key: K): V? {
        val value = map.remove(key) ?: return null
        map[key] = value
        return value
    }

    operator fun set(key: K, value: V) {
        map.remove(key)
        map[key] = value
        while (map.size > maxEntries) map.remove(map.keys.first())
    }
}
