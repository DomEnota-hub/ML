package com.domenota.medialoader.data.playback

import java.time.*
import org.junit.Assert.*
import org.junit.Test

class SmartStatsIntervalTest {
    private val zone=ZoneId.of("Europe/Moscow")
    private val now=Instant.parse("2026-11-14T12:00:00Z").toEpochMilli()
    @Test fun selectedYearAndMonthUseCalendarBoundsIncludingZeroDays() {
        val overview=SmartStatsInterval.overview(SmartStatsPeriod.ALL,now,zone,LocalDate.of(2024,5,1))
        assertEquals(listOf(2024,2025,2026),overview.buckets().map { it.year })
        val year=requireNotNull(overview.drill(LocalDate.of(2026,1,1)))
        assertEquals(12,year.buckets().size)
        val november=requireNotNull(year.drill(LocalDate.of(2026,11,1)))
        assertEquals(LocalDate.of(2026,11,1),november.first)
        assertEquals(LocalDate.of(2026,12,1),november.end)
        assertEquals(30,november.buckets().size)
        assertNull(november.drill(november.first))
        assertEquals(year,requireNotNull(overview.drill(LocalDate.of(2026,1,1))))
    }
    @Test fun leapYearAndDstUseCalendarZoneRatherThanFixedMilliseconds() {
        val year=SmartStatsInterval(LocalDate.of(2024,1,1),LocalDate.of(2025,1,1),StatsGranularity.MONTH,"2024")
        assertEquals(29,requireNotNull(year.drill(LocalDate.of(2024,2,1))).buckets().size)
        val day=SmartStatsInterval(LocalDate.of(2026,3,29),LocalDate.of(2026,3,30),StatsGranularity.DAY,"day")
        assertEquals(23*3_600_000L,day.endMs(ZoneId.of("Europe/Berlin"))-day.startMs(ZoneId.of("Europe/Berlin")))
    }
    @Test fun emptyOverviewIncludesCurrentBucketAndPreservesRollingButtons() {
        assertEquals(LocalDate.of(2026,1,1),SmartStatsInterval.overview(SmartStatsPeriod.ALL,now,zone).buckets().single())
        assertEquals(30,SmartStatsInterval.overview(SmartStatsPeriod.MONTH,now,zone).buckets().size)
        assertEquals(12,SmartStatsInterval.overview(SmartStatsPeriod.YEAR,now,zone).buckets().size)
    }
}
