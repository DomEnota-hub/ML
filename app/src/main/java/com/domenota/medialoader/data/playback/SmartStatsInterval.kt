package com.domenota.medialoader.data.playback

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal enum class StatsGranularity { YEAR, MONTH, DAY }

/** Calendar bounds are half open. Historical sessions have no reliable hourly slices. */
internal data class SmartStatsInterval(val first: LocalDate, val end: LocalDate,
    val granularity: StatsGranularity, val title: String) {
    fun startMs(zone: ZoneId) = first.atStartOfDay(zone).toInstant().toEpochMilli()
    fun endMs(zone: ZoneId) = end.atStartOfDay(zone).toInstant().toEpochMilli()
    fun bucket(date: LocalDate): LocalDate = when(granularity) {
        StatsGranularity.YEAR -> date.withDayOfYear(1)
        StatsGranularity.MONTH -> date.withDayOfMonth(1)
        StatsGranularity.DAY -> date
    }
    fun bucket(at: Long, zone: ZoneId) = bucket(Instant.ofEpochMilli(at).atZone(zone).toLocalDate())
    fun buckets(): List<LocalDate> = generateSequence(bucket(first)) { date -> when(granularity) {
        StatsGranularity.YEAR -> date.plusYears(1)
        StatsGranularity.MONTH -> date.plusMonths(1)
        StatsGranularity.DAY -> date.plusDays(1)
    }}.takeWhile { it < end }.toList()
    fun label(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern(when(granularity) {
        StatsGranularity.YEAR -> "yyyy"
        StatsGranularity.MONTH -> "MM.yyyy"
        StatsGranularity.DAY -> "dd.MM"
    },Locale.ROOT))
    fun drill(date: LocalDate): SmartStatsInterval? = when(granularity) {
        StatsGranularity.YEAR -> SmartStatsInterval(date.withDayOfYear(1),date.withDayOfYear(1).plusYears(1),StatsGranularity.MONTH,date.year.toString())
        StatsGranularity.MONTH -> SmartStatsInterval(date.withDayOfMonth(1),date.withDayOfMonth(1).plusMonths(1),StatsGranularity.DAY,date.format(DateTimeFormatter.ofPattern("LLLL yyyy",Locale.forLanguageTag("ru"))))
        StatsGranularity.DAY -> null
    }
    companion object {
        fun overview(period: SmartStatsPeriod, now: Long, zone: ZoneId, earliest: LocalDate? = null): SmartStatsInterval {
            val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            val first = if(period == SmartStatsPeriod.ALL) minOf(earliest ?: today,today).withDayOfYear(1) else period.first(now,zone)
            return SmartStatsInterval(first,today.plusDays(1),when(period) {
                SmartStatsPeriod.ALL -> StatsGranularity.YEAR
                SmartStatsPeriod.YEAR -> StatsGranularity.MONTH
                else -> StatsGranularity.DAY
            },period.label)
        }
    }
}
