package com.domenota.medialoader.ui.screens

import com.domenota.medialoader.core.database.*
import com.domenota.medialoader.core.model.*
import com.domenota.medialoader.data.library.*
import com.domenota.medialoader.ui.screens.gallery.*
import org.junit.Assert.*
import org.junit.Test

class GalleryBrowseInventoryTest {
    private fun row(id:String, type:MediaType=MediaType.PHOTO) = DownloadEntity(id,"device",id,type,
        DownloadState.COMPLETED,"content://media/$id",1,0)
    @Test fun preparedSnapshotMatchesAllSelectionsAndNeverPublishesHiddenMembers() {
        val public = listOf(row("public"),row("video",MediaType.VIDEO))
        val secret = row("secret")
        val ref = LibraryMediaRef(secret.id,setOf(secret.id),secret,null)
        val folders = listOf(CatalogFolderEntity("hidden","Hidden","hidden",0))
        val assignments = listOf(CatalogAssignmentEntity(secret.id,"hidden",true),CatalogAssignmentEntity("public",null,true))
        val hidden = setOf("hidden")
        assertSame(public,GalleryBrowseInventory.withScopedItems(public,listOf(ref),emptyList(),hidden){"hidden"})
        assertSame(public,GalleryBrowseInventory.withScopedItems(public,listOf(ref),assignments,hidden){"wrong"})
        val items = GalleryBrowseInventory.withScopedItems(public,listOf(ref),assignments,hidden){"hidden"}
        val buckets = items.associate { it.id to DeviceAlbum("camera","Camera") }
        val inventory = GalleryBrowseInventory.build(items,folders,assignments,LibrarySource.PHONE,buckets,hidden)
        for (selection in listOf("all","videos","favorites","device:camera","folder:hidden")) {
            for (query in listOf("","secret","public","  "))
                assertEquals(GalleryLibraryState.filter(items,assignments,selection,query,buckets,hidden),inventory.select(selection,query))
        }
        assertSame(inventory.media,inventory.select("all",""))
        assertSame(inventory.videos,inventory.select("videos",""))
        assertSame(inventory.favorites,inventory.select("favorites",""))
        assertEquals(listOf("secret"),inventory.select("folder:hidden","").map { it.id })
        assertNull(inventory.albums.single { it.key == "folder:hidden" }.cover)
        assertEquals(2,inventory.albums.single { it.key == "device:camera" }.count)
    }
    @Test fun appSourceDoesNotInventDeviceAlbumAndPhoneFallbackRemainsEquivalent() {
        val row = row("a").copy(groupId="device-folder")
        val app = GalleryBrowseInventory.build(listOf(row),emptyList(),emptyList(),LibrarySource.APP,emptyMap(),emptySet())
        assertTrue(app.buckets.isEmpty());assertTrue(app.albums.isEmpty())
        val phone = GalleryBrowseInventory.build(listOf(row),emptyList(),emptyList(),LibrarySource.PHONE,emptyMap(),emptySet())
        assertEquals(DeviceAlbum("device-folder","device-folder"),phone.buckets.getValue("a"))
        assertEquals("device:device-folder",phone.albums.single().key)
    }
}
