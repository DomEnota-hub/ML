package com.domenota.medialoader.ui.screens
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.*
import org.junit.Assert.*
import org.junit.Test
class MusicLibrarySortTest {
    @Test fun dateOrderReversesWithoutLosingEqualDateRowsOrTheirHeadings() {
        val a=DownloadEntity("a","device","a.mp3",MediaType.AUDIO,DownloadState.COMPLETED,"file:///a",1,1000)
        val b=a.copy(id="b",originalName="b.mp3",createdAtEpochMillis=2000)
        val c=b.copy(id="c",originalName="c.mp3")
        val rows=listOf(c,a,b)
        assertEquals(listOf("b","c","a"),sortMusicRows(rows,MusicLibrarySort.DATE).map{it.id})
        assertEquals(listOf("a","b","c"),sortMusicRows(rows,MusicLibrarySort.DATE_OLDEST).map{it.id})
        assertEquals(musicGroupHeading(a,MusicLibrarySort.DATE),musicGroupHeading(a,MusicLibrarySort.DATE_OLDEST))
    }
}
