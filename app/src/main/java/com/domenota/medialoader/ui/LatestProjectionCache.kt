package com.domenota.medialoader.ui

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Retains one immutable source generation, never a visibility-filtered/public result. */
internal class LatestProjectionCache<K, V> {
    private data class Entry<K, V>(val key: K, val value: V)
    private val mutex = Mutex()
    private var entry: Entry<K, V>? = null
    suspend fun get(key: K, build: suspend () -> V): V = mutex.withLock {
        entry?.takeIf { it.key == key }?.let { return@withLock it.value }
        build().also { entry = Entry(key, it) }
    }
}
