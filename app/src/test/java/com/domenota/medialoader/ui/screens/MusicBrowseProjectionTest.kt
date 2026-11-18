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
    @Test fun visibilityFilterKeepsQueueAndFolderCountsWithoutRevivingRemovedLocators() {
        val rows=(0 until 2500).map(::row)
        val folders=rows.associate { it.savedUri!! to "folder" }
        val catalog=MusicBrowseCatalog.build(rows,rows,folders)
        val readModel=MusicBrowseRows.build(catalog,null,0,"",MusicLibrarySort.TITLE,false,emptySet(),emptySet(),emptySet(),folders)
        assertSame(catalog,catalog.retainAllowed { true })
        val hidden=catalog.retainAllowed { it.id!="id0" }
        val filtered=readModel.retainAllowed(MusicLibrarySort.TITLE) { it.id in hidden.available }
        assertEquals(2499,hidden.tracks.size)
        assertEquals(2499,filtered.queue.size)
        assertFalse("id0" in hidden.byId);assertFalse("id0" in filtered.queue)
        assertEquals(filtered.queue,filtered.sections.values.flatten().map { it.id })
        assertTrue(hidden.folders.values.flatten().none { it.id=="id0" })
        assertSame(hidden,hidden.retainAllowed { true })
    }

}
