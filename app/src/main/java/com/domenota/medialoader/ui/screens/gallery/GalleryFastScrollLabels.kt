package com.domenota.medialoader.ui.screens.gallery

import com.domenota.medialoader.core.database.DownloadEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Labels must match LazyVerticalGrid indices, not just date-section indices. */
internal fun galleryFastScrollLabels(rows: List<DownloadEntity>, grouped: Boolean,
    zone: ZoneId = ZoneId.systemDefault()): List<String> {
    if (rows.isEmpty()) return listOf("")
    val format = DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.ROOT)
    fun label(row: DownloadEntity) = Instant.ofEpochMilli(row.createdAtEpochMillis).atZone(zone).toLocalDate().format(format)
    val dates = rows.map(::label)
    return listOf(dates.first()) + if (grouped) dates.groupBy { it }.flatMap { (date, items) -> listOf(date) + items }
        else dates
}
