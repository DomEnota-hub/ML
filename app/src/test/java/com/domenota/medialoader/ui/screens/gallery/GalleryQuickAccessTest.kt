package com.domenota.medialoader.ui.screens.gallery
import org.junit.Assert.*
import org.junit.Test
class GalleryQuickAccessTest {
    private val albums = listOf(GalleryAlbum("device:a", "A", "Телефон", 1, null), GalleryAlbum("device:b", "B", "Телефон", 1, null))
    @Test fun crossSectionDropPinsAndUnpinsWithoutChangingMediaExclusions() {
        val before = GalleryAlbumSettings(excludedFromMedia=setOf("device:a"))
        val pinned = dropGalleryAlbum(before, albums, "device:a", null, true)
        assertEquals(setOf("device:a"), pinned.quickAlbums)
        assertEquals(before.excludedFromMedia, pinned.excludedFromMedia)
        assertTrue(dropGalleryAlbum(pinned, albums, "device:a", "device:b", false).quickAlbums.isEmpty())
    }
    @Test fun systemShortcutsCanNeverBecomeDraggableAlbums() {
        val before = GalleryAlbumSettings()
        for (key in listOf("all", "favorites", "videos")) assertEquals(before, dropGalleryAlbum(before, albums, key, "device:a", true))
    }
    @Test fun fixedCategoriesAreOrderedBeforeCustomPinnedAlbums() {
        val device = listOf(
            GalleryAlbum("device:screenshots", "Screenshots", "Телефон", 7, null),
            GalleryAlbum("folder:camera", "Camera", "Приложение", 3, null),
            GalleryAlbum("device:camera", "Camera", "Телефон", 9, null),
        )
        val builtIns = galleryDefaultQuickItems(device, emptyList(), emptyList(), emptyList())
        assertEquals(listOf(GalleryQuickKind.ALL, GalleryQuickKind.CAMERA,
            GalleryQuickKind.SCREENSHOTS, GalleryQuickKind.VIDEO, GalleryQuickKind.FAVORITES),
            builtIns.map { it.kind })
        assertEquals(listOf("all", "device:camera", "device:screenshots", "videos", "favorites"),
            builtIns.map { it.key })
    }

    @Test fun russianScreenshotNamesAndCustomAlbumsAreNotConfused() {
        assertEquals(GalleryQuickKind.SCREENSHOTS,
            galleryStandardAlbumKind(GalleryAlbum("device:screen", "Снимки и записи экрана",
                "Телефон", 15, null)))
        assertNull(galleryStandardAlbumKind(GalleryAlbum("folder:mine", "Камера",
            "Приложение", 5, null)))
        assertEquals(GalleryQuickKind.CAMERA,
            galleryStandardAlbumKind(GalleryAlbum("device:cam", "КАМЕРА",
                "Телефон", 19, null)))
    }

    @Test fun missingSystemAlbumsHaveNoUnopenableShortcut() {
        assertEquals(listOf("all", "videos", "favorites"),
            galleryDefaultQuickItems(albums, emptyList(), emptyList(), emptyList()).map { it.key })
    }

    @Test fun draggingHiddenAlbumToQuickAccessRestoresItsAlbumVisibility() {
        val pinned=dropGalleryAlbum(GalleryAlbumSettings(hiddenAlbums=setOf("device:a")), albums, "device:a", null, true)
        assertFalse("device:a" in pinned.hiddenAlbums)
        assertTrue("device:a" in pinned.quickAlbums)
        assertFalse("device:a" in removeGalleryAlbumSettings(pinned,"device:a").quickAlbums)
    }
}
