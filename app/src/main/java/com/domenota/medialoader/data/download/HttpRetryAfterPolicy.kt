package com.domenota.medialoader.data.download

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Interpret HTTP Retry-After without logging or persisting the header itself. */
internal object HttpRetryAfterPolicy {
    fun delayMs(header: String?, nowMs: Long = System.currentTimeMillis()): Long? {
        val value = header?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        value.toLongOrNull()?.let { seconds ->
            if (seconds < 0) return null
            return if (seconds > Long.MAX_VALUE / 1_000L) Long.MAX_VALUE else seconds * 1_000L
        }
        val date = runCatching {
            ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                .toInstant().toEpochMilli()
        }.getOrNull() ?: return null
        return (date - nowMs).coerceAtLeast(0L)
    }
}
