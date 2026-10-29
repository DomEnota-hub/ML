package com.domenota.medialoader.data.storage

import java.util.concurrent.ConcurrentHashMap

/** Used by history display only; actions explicitly refresh the selected file. */
internal class SavedFileStatusCache(
    private val check: (String?) -> SavedFileStatus,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private data class Entry(val status: SavedFileStatus, val time: Long)
    private val entries = ConcurrentHashMap<String, Entry>()
    fun get(uri: String?, fresh: Boolean = false): SavedFileStatus {
        if (uri == null) return check(null)
        val now = clock()
        val cached = entries[uri]
        if (!fresh && cached != null && now - cached.time < 60_000L) return cached.status
        return check(uri).also { entries[uri] = Entry(it, now) }
    }
}
