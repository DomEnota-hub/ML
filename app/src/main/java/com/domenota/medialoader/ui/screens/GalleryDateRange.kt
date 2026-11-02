package com.domenota.medialoader.ui.screens

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Limit the jump picker to actually indexed media in the current gallery view.
 * Zero, invalid, and future capture timestamps must not widen the calendar.
 */
internal data class GalleryDateRange(val first: LocalDate, val last: LocalDate) {
    init { require(!first.isAfter(last)) }
    val years: IntRange get() = first.year..last.year
    fun contains(date: LocalDate): Boolean = !date.isBefore(first) && !date.isAfter(last)
}

internal fun galleryDateRange(
    timestampsMillis: Iterable<Long>,
    today: LocalDate = LocalDate.now(),
    zone: ZoneId = ZoneId.systemDefault(),
): GalleryDateRange? {
    val first = timestampsMillis.asSequence()
        .filter { it > 0L }
        .mapNotNull { millis ->
            runCatching { Instant.ofEpochMilli(millis).atZone(zone).toLocalDate() }.getOrNull()
        }
        .filter { !it.isAfter(today) }
        .minOrNull() ?: return null
    return GalleryDateRange(first, today)
}
