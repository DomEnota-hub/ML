package com.domenota.medialoader.ui.screens.gallery

import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.ui.screens.GalleryDateRange
import com.domenota.medialoader.ui.screens.galleryDateRange
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** All grid data belongs to one browse generation and one selection. Only row
 * references are retained, not duplicated media entities or bitmaps. */
internal data class GalleryGridKey(val selection:String,val search:String,val detail:Boolean,val tab:Int,
    val sort:GalleryAlbumFileSort,val excluded:Set<String>,val today:LocalDate,val zone:ZoneId)
internal data class GalleryGridInventory(val allVisible:List<DownloadEntity> = emptyList(),
    val visible:List<DownloadEntity> = emptyList(),val ordered:List<DownloadEntity> = emptyList(),
    val queue:List<String> = emptyList(),val queueSet:Set<String> = emptySet(),
    val sections:Map<LocalDate,List<DownloadEntity>> = emptyMap(),val labels:List<String> = listOf(""),
    val dateRange:GalleryDateRange?=null) {
    fun retainAllowed(key: GalleryGridKey, allowed: (DownloadEntity) -> Boolean): GalleryGridInventory {
        val kept = ordered.filter(allowed)
        if (kept.size == ordered.size && allVisible.all(allowed)) return this
        val ids = kept.map { it.id }
        val remainingVisible = visible.filter(allowed)
        return copy(allVisible = allVisible.filter(allowed), visible = remainingVisible,
            ordered = kept, queue = ids, queueSet = ids.toHashSet(),
            sections = sections.mapValues { (_, rows) -> rows.filter(allowed) }.filterValues { it.isNotEmpty() },
            labels = galleryFastScrollLabels(kept, !key.detail || GalleryAlbumFileOrder.groupedByDate(key.sort), key.zone),
            dateRange = galleryDateRange(remainingVisible.map { it.createdAtEpochMillis }, key.today, key.zone))
    }
    companion object {
        fun build(browse:GalleryBrowseInventory,key:GalleryGridKey):GalleryGridInventory {
            val all=if(key.excluded.isEmpty())browse.media else browse.media.filter{row->
                visibleInGalleryMedia(row,browse.catalog[row.id],browse.buckets[row.id],key.excluded)
            }
            val selected=browse.select(key.selection,key.search)
            val visible=if(key.tab==0&&key.selection=="all"&&key.excluded.isNotEmpty()) {
                if(key.search.isBlank())all else selected.filter{row->visibleInGalleryMedia(row,browse.catalog[row.id],browse.buckets[row.id],key.excluded)}
            } else selected
            val ordered=if(key.detail)GalleryAlbumFileOrder.sorted(visible,key.sort) else visible
            val queue=ordered.map{it.id}
            val sections=ordered.groupBy{Instant.ofEpochMilli(it.createdAtEpochMillis).atZone(key.zone).toLocalDate()}
            return GalleryGridInventory(all,visible,ordered,queue,queue.toHashSet(),sections,
                galleryFastScrollLabels(ordered,!key.detail||GalleryAlbumFileOrder.groupedByDate(key.sort),key.zone),
                galleryDateRange(visible.map{it.createdAtEpochMillis},key.today,key.zone))
        }
    }
}
