package com.domenota.medialoader.ui.navigation

import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.*
import com.domenota.medialoader.data.storage.SavedFileStatus
import org.junit.Assert.*
import org.junit.Test

class ViewerQueueInventoryTest {
    private fun row(id:String,type:MediaType)=DownloadEntity(id,"device",id,type,DownloadState.COMPLETED,"content://media/$id",null,0)
    @Test fun audioNeverBuildsVisualQueuesAndPreservesOrderAndVisibility(){
        val a=row("a",MediaType.AUDIO);val b=row("b",MediaType.AUDIO)
        val result=ViewerQueueInventory.build(listOf("b","p","a","secret"),listOf(a,b,row("p",MediaType.PHOTO),a.copy(id="secret",isPrivate=true)),a,emptyMap())
        assertEquals(listOf(b,a),result.audio);assertTrue(result.grid.isEmpty());assertTrue(result.photos.isEmpty())
    }
    @Test fun visualQueueDoesNotExpandOrIncludeUnavailableFiles(){
        val a=row("a",MediaType.PHOTO);val b=row("b",MediaType.PHOTO);val c=row("c",MediaType.VIDEO)
        val result=ViewerQueueInventory.build(listOf("c","a","b"),listOf(a,b,c),a,mapOf("b" to SavedFileStatus.MISSING))
        assertEquals(listOf(c,a),result.grid);assertTrue(result.audio.isEmpty());assertTrue(result.photos.isEmpty())
    }
}
