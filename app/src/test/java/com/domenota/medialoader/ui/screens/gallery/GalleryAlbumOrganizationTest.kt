package com.domenota.medialoader.ui.screens.gallery

import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryAlbumOrganizationTest {
    private val albums = listOf(
        GalleryAlbum("device:1", "Camera", "Телефон", 2, null),
        GalleryAlbum("device:2", "Screenshots", "Телефон", 3, null),
        GalleryAlbum("folder:custom", "Favorites", "Приложение", 1, null),
    )

    @Test fun movingAnAlbumChangesOnlyDisplayOrdering() {
        val settings = moveGalleryAlbum(GalleryAlbumSettings(), albums, "device:2", "device:1")
        assertEquals(listOf("device:2", "device:1", "folder:custom"),
            orderedGalleryAlbums(albums, settings).map { it.key })
        assertEquals(3, albums.size)
        assertEquals(listOf("device:1", "device:2", "folder:custom"), albums.map { it.key })
    }

    @Test fun movingAnAlbumDownwardsPlacesItAfterTheDropTarget() {
        val settings = moveGalleryAlbum(GalleryAlbumSettings(), albums, "device:1", "device:2")
        assertEquals(listOf("device:2", "device:1", "folder:custom"),
            orderedGalleryAlbums(albums, settings).map { it.key })
    }

    @Test fun unknownAlbumCannotAccidentallyChangeOrder() {
        val settings = GalleryAlbumSettings(order = listOf("device:1"))
        assertEquals(settings, moveGalleryAlbum(settings, albums, "missing", "device:2"))
    }

    @Test fun albumVisibilityAndMediaExclusionAreIndependent() {
        val settings = GalleryAlbumSettings(
            hiddenAlbums = setOf("device:1"),
            excludedFromMedia = setOf("device:2"),
        )
        assertTrue("device:1" in settings.hiddenAlbums)
        assertFalse("device:1" in settings.excludedFromMedia)
        assertTrue("device:2" in settings.excludedFromMedia)
        assertFalse("device:2" in settings.hiddenAlbums)
    }

    @Test fun hiddenDeviceAlbumMediaIsNotDisplayedButSourceIsUntouched() {
        val item = DownloadEntity("phone:1", "device", "clip.jpg", MediaType.PHOTO,
            DownloadState.COMPLETED, "content://media/external/images/media/1", 600L, 10L)
        val deviceAlbum = DeviceAlbum("camera", "Camera")
        assertFalse(visibleInGalleryMedia(item, null, deviceAlbum, setOf("device:camera")))
        assertTrue(visibleInGalleryMedia(item, null, deviceAlbum, emptySet()))
        assertEquals("clip.jpg", item.originalName)
    }

    @Test fun dragNearGridEdgesScrollsInCorrectDirection() {
        assertTrue(galleryAlbumEdgeScrollDelta(12f, 800f, 100f) < 0f)
        assertTrue(galleryAlbumEdgeScrollDelta(788f, 800f, 100f) > 0f)
        assertEquals(0f, galleryAlbumEdgeScrollDelta(400f, 800f, 100f), 0f)
        assertEquals(0f, galleryAlbumEdgeScrollDelta(10f, 0f, 100f), 0f)
        assertTrue(galleryAlbumEdgeScrollDelta(2f, 800f, 100f) < galleryAlbumEdgeScrollDelta(70f, 800f, 100f))
    }

    @Test fun deletingFolderClearsOnlyPresentationSettings() {
        val settings = GalleryAlbumSettings(
            hiddenAlbums = setOf("folder:custom"),
            excludedFromMedia = setOf("folder:custom", "device:2"),
            order = listOf("folder:custom", "device:2", "device:1"),
        )
        assertEquals(
            GalleryAlbumSettings(
                hiddenAlbums = emptySet(),
                excludedFromMedia = setOf("device:2"),
                order = listOf("device:2", "device:1"),
            ),
            removeGalleryAlbumSettings(settings, "folder:custom")
        )
    }
}
