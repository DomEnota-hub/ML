package com.domenota.medialoader.ui.screens

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class GalleryDateRangeTest {
    private val utc = ZoneId.of("UTC")
    private val today = LocalDate.of(2026, 11, 2)
    private fun millis(date: LocalDate) = date.atStartOfDay(utc).toInstant().toEpochMilli()

    @Test fun limitsBothEndsToExistingMediaAndToday() {
        val range = galleryDateRange(listOf(millis(LocalDate.of(2023, 5, 15)),
            millis(today), millis(LocalDate.of(2028, 1, 1)), 0L), today, utc)!!
        assertEquals(LocalDate.of(2023, 5, 15), range.first)
        assertEquals(today, range.last)
        assertEquals(2023..2026, range.years)
        assertFalse(range.contains(LocalDate.of(2027, 1, 1)))
        assertFalse(range.contains(LocalDate.of(2023, 5, 14)))
        assertTrue(range.contains(today))
    }

    @Test fun emptyAndInvalidMediaDoNotExposeCenturies() {
        assertNull(galleryDateRange(emptyList(), today, utc))
        assertNull(galleryDateRange(listOf(0L, -1L, Long.MAX_VALUE), today, utc))
        assertNull(galleryDateRange(listOf(millis(today.plusDays(1))), today, utc))
    }

    @Test fun handlesSingleDayAndReindexedSource() {
        val a = galleryDateRange(listOf(millis(today)), today, utc)!!
        assertEquals(today, a.first)
        assertEquals(2026..2026, a.years)
        val b = galleryDateRange(listOf(millis(today.minusYears(5))), today, utc)!!
        assertEquals(2021..2026, b.years)
    }
}
