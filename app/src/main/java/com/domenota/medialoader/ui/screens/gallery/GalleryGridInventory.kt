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
    val sort:GalleryAlbumFileSort,val excluded:Set<String>,val today:LocalDate,val zone:ZoneId,
    val noteMatches:Set<String> = emptySet())
internal data class GalleryGridInventory(val allVisible:List<DownloadEntity> = emptyList(),
    val visible:List<DownloadEntity> = emptyList(),val ordered:List<DownloadEntity> = emptyList(),
    val queue:List<String> = emptyList(),val queueSet:Set<String> = emptySet(),
    val sections:Map<LocalDate,List<DownloadEntity>> = emptyMap(),val labels:List<String> = listOf(""),
    val dateRange:GalleryDateRange?=null) {
    // Built once per grid generation, including a removal-only copy.
    val dayIds:Map<LocalDate,List<String>> = sections.mapValues { (_,rows)->rows.map{it.id} }
    val daySizes:Map<LocalDate,Int> = dayIds.mapValues{it.value.size}
    private val dateById:Map<String,LocalDate> = buildMap {
        dayIds.forEach { (date,ids)->ids.forEach { put(it,date) } }
    }
    fun selectionCounts(selected:Set<String>):Map<LocalDate,Int> {
        if(selected.isEmpty())return emptyMap()
        val counts=HashMap<LocalDate,Int>()
        selected.forEach { id->dateById[id]?.let { day->counts[day]=(counts[day]?:0)+1 } }
        return counts
    }
    fun retainAllowed(key: GalleryGridKey, allowed: (DownloadEntity) -> Boolean): GalleryGridInventory {
        // No list allocation when the retained addresses are all still allowed.
        if (ordered.all(allowed) && allVisible.all(allowed)) return this
        val kept = ordered.filter(allowed)
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
            val selected=browse.select(key.selection,key.search,key.noteMatches)
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
