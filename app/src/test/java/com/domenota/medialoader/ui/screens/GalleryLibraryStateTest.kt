package com.domenota.medialoader.ui.screens

import com.domenota.medialoader.core.database.*
import com.domenota.medialoader.core.model.*
import com.domenota.medialoader.ui.screens.gallery.*
import org.junit.Assert.*
import org.junit.Test

class GalleryLibraryStateTest {
    private fun file(id: String, type: MediaType = MediaType.PHOTO) = DownloadEntity(
        id, "device", "$id.jpg", type, DownloadState.COMPLETED, "content://media/$id", 1, 0)
    @Test fun albumAndSearchFiltersDefineTheExactMixedViewerQueue() {
        val items = listOf(file("a"), file("b", MediaType.VIDEO), file("c"), file("private").copy(isPrivate = true))
        val assignments = listOf(CatalogAssignmentEntity("a", "folder", true), CatalogAssignmentEntity("b", "folder", false))
        assertEquals(listOf("a", "b"), GalleryLibraryState.filter(items, assignments, "folder:folder", "", emptyMap()).map { it.id })
        assertEquals(listOf("b"), GalleryLibraryState.filter(items, assignments, "videos", "", emptyMap()).map { it.id })
        assertEquals(listOf("a"), GalleryLibraryState.filter(items, assignments, "favorites", "a", emptyMap()).map { it.id })
        assertEquals(listOf("b", "c"), GalleryLibraryState.filter(items, assignments, "device:bucket", "", mapOf(
            "b" to DeviceAlbum("bucket", "Camera"), "c" to DeviceAlbum("bucket", "Camera"))).map { it.id })
    }
    @Test fun sameNamedDeviceAndApplicationAlbumsKeepDistinctIdentityAndCoverCounts() {
        val items = listOf(file("a"), file("b"))
        val albums = GalleryLibraryState.albums(items, listOf(CatalogFolderEntity("f", "Camera", "camera", 0)),
            listOf(CatalogAssignmentEntity("a", "f", false)),
            mapOf("a" to DeviceAlbum("bucket1", "Camera"), "b" to DeviceAlbum("bucket2", "Camera")))
        assertEquals(setOf("folder:f", "device:bucket1", "device:bucket2"), albums.map { it.key }.toSet())
        assertEquals(listOf(1, 1, 1), albums.map { it.count })
        assertEquals("a", albums.first { it.key == "folder:f" }.cover?.id)
        assertEquals("b", albums.first { it.key == "device:bucket2" }.cover?.id)
    }
    @Test fun infoLinksResolveRealMembershipAndPreferredOrigin() {
        val photo = file("a")
        val folders = listOf(CatalogFolderEntity("f", "Мультфильмы", "мультфильмы", 0))
        val assignments = listOf(CatalogAssignmentEntity("a", "f", false))
        val device = mapOf("a" to DeviceAlbum("camera", "Camera"))
        val links = GalleryLibraryState.albumLinks(photo, folders, assignments, device, "device:camera")
        assertEquals(listOf("device:camera", "folder:f"), links.map { it.key })
        assertTrue(GalleryLibraryState.albumLinks(photo.copy(isPrivate=true), folders, assignments, device).isEmpty())
        assertTrue(GalleryLibraryState.albumLinks(photo.copy(hidden=true), folders, assignments, device).isEmpty())
        assertTrue(GalleryLibraryState.albumLinks(photo, emptyList(), assignments, emptyMap()).isEmpty())
    }
    @Test fun galleryLinkScrollAccountsForHeaders() {
        assertEquals(1, GalleryLibraryState.gridIndex(listOf(listOf("a","b")), "a", false))
        assertEquals(5, GalleryLibraryState.gridIndex(listOf(listOf("a","b"), listOf("c","d")), "c", true))
        assertNull(GalleryLibraryState.gridIndex(listOf(listOf("a")), "absent", true))
    }
}
