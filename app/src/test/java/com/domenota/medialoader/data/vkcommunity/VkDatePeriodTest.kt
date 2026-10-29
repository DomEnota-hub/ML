package com.domenota.medialoader.data.vkcommunity

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class VkDatePeriodTest {
    private val zone = ZoneId.of("Europe/Berlin")

    @Test fun reversedSelectionAndSingleDay() {
        val first = LocalDate.of(2026, 1, 2)
        val last = LocalDate.of(2025, 12, 31)
        val period = VkDatePeriod.of(first, last)
        assertEquals(last, period.start)
        assertEquals(first, period.endInclusive)
        assertEquals(first, VkDatePeriod.of(first, first).endInclusive)
    }

    @Test fun endExclusiveHandlesDstAndFinalDay() {
        val day = LocalDate.of(2026, 3, 29)
        val period = VkDatePeriod.of(day, day)
        assertEquals(23 * 3600L,
            period.endExclusiveEpochSecond(zone) - period.startEpochSecond(zone))
        assertEquals(day.plusDays(1).atStartOfDay(zone).toEpochSecond(),
            period.endExclusiveEpochSecond(zone))
    }

    @Test fun presetsClampAtToday() {
        val today = LocalDate.of(2026, 10, 23)
        assertEquals(LocalDate.of(2026, 10, 17), VkDatePeriod.lastDays(7, today).start)
        assertEquals(today, VkDatePeriod.wholeMonth(today, today).endInclusive)
        assertEquals(LocalDate.of(2026, 9, 30),
            VkDatePeriod.wholeMonth(LocalDate.of(2026, 9, 11), today).endInclusive)
    }
}
