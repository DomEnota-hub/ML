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
    val labels=HashMap<java.time.LocalDate,String>()
    fun label(row: DownloadEntity):String {
        val date=Instant.ofEpochMilli(row.createdAtEpochMillis).atZone(zone).toLocalDate()
        return labels.getOrPut(date){date.format(format)}
    }
    // The grid groups by date in first-seen order, even for non-contiguous dates.
    // Keep identical labels and indices without creating a list per date or formatting every row.
    val first=label(rows.first())
    return buildList(rows.size+1) {
        add(first)
        if(grouped){
            val counts=LinkedHashMap<String,Int>()
            rows.forEach{val date=label(it);counts[date]=(counts[date]?:0)+1}
            counts.forEach{(date,count)->repeat(count+1){add(date)}}
        }else rows.forEach{add(label(it))}
    }
}
