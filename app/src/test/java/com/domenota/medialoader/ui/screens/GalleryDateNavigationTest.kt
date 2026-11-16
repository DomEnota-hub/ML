package com.domenota.medialoader.ui.screens

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GalleryDateNavigationTest {
    private fun d(v: String) = LocalDate.parse(v)

    @Test fun findsExactOrNearestMediaDate() {
        val days = listOf(d("2026-10-29"), d("2026-10-21"), d("2026-10-10"))
        assertEquals(d("2026-10-21"), closestGalleryDate(days, d("2026-10-21")))
        assertEquals(d("2026-10-29"), closestGalleryDate(days, d("2026-10-28")))
        assertEquals(d("2026-10-21"), closestGalleryDate(days, d("2026-10-24")))
        assertNull(closestGalleryDate(emptyList(), d("2026-10-24")))
    }

    @Test fun tieGoesToMoreRecentDayAndGridIndexCountsAllEarlierRows() {
        val sections = linkedMapOf(d("2026-10-31") to 2, d("2026-10-29") to 3,
            d("2026-10-15") to 1)
        assertEquals(d("2026-10-31"), closestGalleryDate(sections.keys, d("2026-10-30")))
        assertEquals(1, galleryDateSectionIndex(sections, d("2026-10-30")))
        assertEquals(4, galleryDateSectionIndex(sections, d("2026-10-29")))
        assertEquals(8, galleryDateSectionIndex(sections, d("2026-10-15")))
        assertNull(galleryDateSectionIndex(emptyMap(), d("2026-10-01")))
    }

    @Test fun fastScrollLabelsPreserveNonContiguousDateGroupAndGridOffsets(){
        val zone=java.time.ZoneId.of("UTC")
        fun row(id:String,date:String)=com.domenota.medialoader.core.database.DownloadEntity(id,"device",id,
            com.domenota.medialoader.core.model.MediaType.PHOTO,com.domenota.medialoader.core.model.DownloadState.COMPLETED,
            "content://media/$id",null,d(date).atStartOfDay(zone).toInstant().toEpochMilli())
        val rows=listOf(row("a","2026-11-16"),row("b","2026-11-10"),row("c","2026-11-16"))
        val a="16.11.2026";val b="10.11.2026"
        assertEquals(listOf(a,a,a,a,b,b),com.domenota.medialoader.ui.screens.gallery.galleryFastScrollLabels(rows,true,zone))
        assertEquals(listOf(a,a,b,a),com.domenota.medialoader.ui.screens.gallery.galleryFastScrollLabels(rows,false,zone))
    }
}
