package com.domenota.medialoader.ui.navigation

import java.util.LinkedHashMap
import java.util.UUID

/**
 * Navigation's SavedStateHandle is persisted through Android's Bundle/IPC and must
 * never carry the full photo gallery queue (10k+ IDs). Only a small lookup token
 * goes into SavedStateHandle; the queue itself stays in bounded process memory.
 *
 * After process death the token cannot be resolved: the viewer falls back to
 * its opened item instead of crashing the entire Activity during onStop().
 */
internal object ViewerQueueCache {
    private const val MAX_ACTIVE_QUEUES = 3
    private val queues = LinkedHashMap<String, List<String>>(4, .75f, true)

    @Synchronized
    fun store(ids: List<String>): String? {
        if (ids.isEmpty()) return null
        val token = UUID.randomUUID().toString()
        queues[token] = ids.toList()
        while (queues.size > MAX_ACTIVE_QUEUES) queues.remove(queues.keys.first())
        return token
    }

    @Synchronized
    fun resolve(token: String?): List<String> =
        if (token == null) emptyList() else queues[token].orEmpty()

    @Synchronized
    fun clearForTesting() { queues.clear() }
}
