package com.my.kizzy.rpc

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

internal object ArtworkCache {
    private val lock = SynchronizedObject()
    private val cache = HashMap<String, String>()

    suspend fun getOrFetch(key: String, fetch: suspend () -> String?): String? {
        synchronized(lock) { cache[key] }?.let { return it }
        return fetch()?.also { synchronized(lock) { cache[key] = it } }
    }
}
