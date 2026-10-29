package com.domenota.medialoader.data.preview

/** URL-scoped negative cache. A fresh URL remains eligible even if an old CDN link expired. */
internal class ThumbnailRetryPolicy(private val now: () -> Long = System::currentTimeMillis) {
    private data class Failure(val attempts: Int, val nextAttemptAt: Long)

    private val failures = object : LinkedHashMap<String, Failure>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Failure>?): Boolean =
            size > 256
    }

    @Synchronized fun canAttempt(url: String): Boolean =
        (failures[url]?.nextAttemptAt ?: Long.MIN_VALUE) <= now()

    @Synchronized fun failed(url: String, status: Int?) {
        val attempts = ((failures[url]?.attempts ?: 0) + 1).coerceAtMost(5)
        val base = when (status) {
            403, 404 -> 30 * 60_000L
            429 -> 30_000L
            in 500..599 -> 20_000L
            null -> 15_000L
            else -> 2 * 60_000L
        }
        val delay = if (status == 403 || status == 404) base else
            (base * (1L shl (attempts - 1))).coerceAtMost(5 * 60_000L)
        failures[url] = Failure(attempts, now() + delay)
    }

    @Synchronized fun succeeded(url: String) {
        failures.remove(url)
    }
}
