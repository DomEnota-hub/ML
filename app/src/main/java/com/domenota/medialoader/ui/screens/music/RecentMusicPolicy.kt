package com.domenota.medialoader.ui.screens.music

import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import java.time.*

internal data class RecentMusicDay(val date: LocalDate?, val tracks: List<DownloadEntity>)

/** Uses original indexed chronology, not modification or scan time. Null date is explicit. */
internal object RecentMusicPolicy {
    fun days(rows: List<DownloadEntity>, zone: ZoneId): List<RecentMusicDay> = rows.asSequence()
        .filter { it.mediaType == MediaType.AUDIO && it.state == DownloadState.COMPLETED &&
            !it.hidden && !it.isPrivate && !it.savedUri.isNullOrBlank() }
        .distinctBy { it.id }
        .sortedWith(compareByDescending<DownloadEntity> { it.createdAtEpochMillis }.thenBy { it.id })
        .groupBy { row -> row.createdAtEpochMillis.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() } }
        .map { (day, tracks) -> RecentMusicDay(day, tracks) }

    fun calendarCells(month: YearMonth): List<LocalDate?> {
        val first = month.atDay(1)
        val cells = List<LocalDate?>(first.dayOfWeek.value - 1) { null } + (1..month.lengthOfMonth()).map(month::atDay)
        return cells + List((7 - cells.size % 7) % 7) { null }
    }
    fun selectedDay(days: List<RecentMusicDay>, selected: LocalDate?): LocalDate? =
        selected?.takeIf { date -> days.any { it.date == date } } ?: days.firstNotNullOfOrNull { it.date }
}
