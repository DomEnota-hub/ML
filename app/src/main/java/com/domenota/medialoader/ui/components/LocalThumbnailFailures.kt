package com.domenota.medialoader.ui.components

/** Short-lived misses, never bitmap data. Keys include cover revision and explicit identity.
 * Missing embedded artwork and decoder errors should not reopen the same provider on each
 * composition. Expiry allows recovery from grants and externally edited metadata. */
internal class LocalThumbnailFailures(
    private val capacity: Int = 256,
    private val retryAfterMs: Long = 30_000L,
    private val now: () -> Long,
) {
    init { require(capacity > 0 && retryAfterMs > 0) }
    private val failures = object : LinkedHashMap<String, Long>(capacity, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > capacity
    }
    @Synchronized fun suppressed(key: String): Boolean {
        val failedAt = failures[key] ?: return false
        val elapsed = now() - failedAt
        if (elapsed in 0 until retryAfterMs) return true
        failures.remove(key)
        return false
    }
    @Synchronized fun failed(key: String) { failures[key] = now() }
    @Synchronized fun succeeded(key: String) { failures.remove(key) }
}
