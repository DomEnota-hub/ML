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

    @Test fun gridCacheReusesExactGenerationAndNeverExpandsExcludedOrScopedRows(){
        val a=row("a").copy(createdAtEpochMillis=1000);val b=row("b").copy(createdAtEpochMillis=2000)
        val folder=CatalogFolderEntity("hidden","Hidden","hidden",0)
        val assignment=CatalogAssignmentEntity("b","hidden",true)
        val browse=GalleryBrowseInventory.build(listOf(a,b),listOf(folder),listOf(assignment),LibrarySource.PHONE,mapOf("a" to DeviceAlbum("camera","Camera")),setOf("hidden"))
        val key=GalleryGridKey("all","",false,0,GalleryAlbumFileSort.entries.first(),emptySet(),java.time.LocalDate.of(2026,11,16),java.time.ZoneId.of("UTC"))
        val first=browse.prepareGrid(key)
        assertEquals(listOf("a"),first.queue);assertSame(first,browse.prepareGrid(key))
        val scoped=browse.prepareGrid(key.copy(selection="folder:hidden",detail=true))
        assertEquals(listOf("b"),scoped.queue);assertSame(first,browse.cachedGrid(key))
        val other=GalleryBrowseInventory.build(listOf(b),listOf(folder),listOf(assignment),LibrarySource.PHONE,emptyMap(),setOf("hidden"))
        assertNull(other.cachedGrid(key));assertTrue(other.prepareGrid(key).queue.isEmpty())
        val excluded=browse.prepareGrid(key.copy(excluded=setOf("device:camera")))
        assertTrue(excluded.queue.isEmpty())
    }
    @Test fun hideFiltersOnlyAffectedRowsAndCarriesWarmGridWithMatchingCountsAndCover() {
        val moving=row("moving");val safe=row("safe")
        val folders=listOf(CatalogFolderEntity("f","Folder","f",0))
        val assignments=listOf(CatalogAssignmentEntity(moving.id,"f",true),CatalogAssignmentEntity(safe.id,"f",true))
        for(source in LibrarySource.entries) {
            val browse=GalleryBrowseInventory.build(listOf(moving,safe),folders,assignments,source,
                mapOf("moving" to DeviceAlbum("camera","Camera"),"safe" to DeviceAlbum("camera","Camera")),emptySet())
            val key=GalleryGridKey("all","",false,0,GalleryAlbumFileSort.entries.first(),emptySet(),java.time.LocalDate.of(2026,11,18),java.time.ZoneId.of("UTC"))
            browse.prepareGrid(key)
            assertSame(browse,browse.retainAllowed { true })
            val hidden=browse.retainAllowed { it.id!="moving" }
            assertEquals(listOf("safe"),hidden.media.map { it.id })
            assertEquals(listOf("safe"),hidden.favorites.map { it.id })
            assertEquals(1,hidden.albums.single { it.key=="folder:f" }.count)
            assertEquals("safe",hidden.albums.single { it.key=="folder:f" }.cover?.id)
            val warm=requireNotNull(hidden.cachedGrid(key))
            assertEquals(listOf("safe"),warm.queue)
            assertEquals(listOf("safe"),warm.sections.values.flatten().map { it.id })
            assertEquals(hidden.prepareGrid(key).labels,warm.labels)
            // SHOW cannot recreate an unverified URI out of an older read model.
            assertSame(hidden,hidden.retainAllowed { true })
            assertEquals(listOf("safe"),hidden.items.map { it.id })
        }
    }

}
