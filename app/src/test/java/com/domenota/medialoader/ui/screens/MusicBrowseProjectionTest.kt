package com.domenota.medialoader.ui.screens

import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.*
import com.domenota.medialoader.data.library.MediaPlaylist
import org.junit.Test
import org.junit.Assert.*

class MusicBrowseProjectionTest {
    private fun row(i:Int)=DownloadEntity("id$i","device","track$i.mp3",MediaType.AUDIO,DownloadState.COMPLETED,
        "content://media/external/audio/media/$i",100L,1000L,durationMs=60_000L)
    @Test fun largeBrowseKeepsWholeQueueAndPlaylistMissingPositions(){
        val rows=(0 until 2500).map(::row)
        val catalog=MusicBrowseCatalog.build(rows,rows,emptyMap())
        val playlist=MediaPlaylist("p","test",listOf("id20","missing","id2"))
        val all=MusicBrowseRows.build(catalog,null,0,"",MusicLibrarySort.TITLE,false,emptySet(),emptySet(),emptySet(),emptyMap())
        assertEquals(2500,all.queue.size);assertEquals(rows.map{it.id}.toSet(),all.queue.toSet())
        assertEquals(all.queue,all.sections.values.flatten().map{it.id})
        val selected=MusicBrowseRows.build(catalog,playlist,1,"",MusicLibrarySort.TITLE,false,emptySet(),emptySet(),emptySet(),emptyMap())
        assertEquals(listOf("id20","missing","id2"),selected.displayedIds)
        assertEquals(listOf("id20","id2"),selected.queue);assertEquals(2L,selected.playlistMinutes)
        assertEquals(1,selected.playlistMissing);assertEquals(2,selected.ordinals["id2"])
    }
    @Test fun favoriteProjectionUsesBothSourcesAndNeverIncludesPrivateRows(){
        val a=row(1);val b=row(2);val secret=row(3).copy(isPrivate=true)
        val catalog=MusicBrowseCatalog.build(listOf(a),listOf(a,b,secret),emptyMap())
        val result=MusicBrowseRows.build(catalog,null,0,"",MusicLibrarySort.TITLE,true,setOf(a.id,b.id,secret.id),emptySet(),emptySet(),emptyMap())
        assertEquals(setOf(a.id,b.id),result.queue.toSet())
        assertFalse(secret.id in catalog.available)
    }
}
