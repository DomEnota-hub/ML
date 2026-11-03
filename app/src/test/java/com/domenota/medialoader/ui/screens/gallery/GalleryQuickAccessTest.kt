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
    @Test fun draggingHiddenAlbumToQuickAccessRestoresItsAlbumVisibility() {
        val pinned=dropGalleryAlbum(GalleryAlbumSettings(hiddenAlbums=setOf("device:a")), albums, "device:a", null, true)
        assertFalse("device:a" in pinned.hiddenAlbums)
        assertTrue("device:a" in pinned.quickAlbums)
        assertFalse("device:a" in removeGalleryAlbumSettings(pinned,"device:a").quickAlbums)
    }
}
