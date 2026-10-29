package com.domenota.medialoader.data.vkcommunity

import java.time.LocalDate
import java.time.ZoneId

/** Both visible dates are inclusive; API filtering uses the next day's start. */
data class VkDatePeriod private constructor(
    val start: LocalDate,
    val endInclusive: LocalDate,
) {
    fun startEpochSecond(zone: ZoneId): Long = start.atStartOfDay(zone).toEpochSecond()
    fun endExclusiveEpochSecond(zone: ZoneId): Long =
        endInclusive.plusDays(1).atStartOfDay(zone).toEpochSecond()

    fun contains(epochSecond: Long, zone: ZoneId): Boolean =
        epochSecond >= startEpochSecond(zone) && epochSecond < endExclusiveEpochSecond(zone)

    companion object {
        fun of(a: LocalDate, b: LocalDate): VkDatePeriod =
            if (a <= b) VkDatePeriod(a, b) else VkDatePeriod(b, a)

        /** Seven days means today plus the previous six calendar days. */
        fun lastDays(days: Long, today: LocalDate): VkDatePeriod {
            require(days > 0)
            return of(today.minusDays(days - 1), today)
        }

        fun wholeMonth(displayedMonth: LocalDate, today: LocalDate): VkDatePeriod {
            val start = displayedMonth.withDayOfMonth(1)
            require(!start.isAfter(today))
            return of(start, minOf(start.plusMonths(1).minusDays(1), today))
        }
    }
}
