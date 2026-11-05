package com.domenota.medialoader.ui.screens.gallery

import android.app.WallpaperManager
import org.junit.Assert.assertEquals
import org.junit.Test

class GalleryWallpaperChoiceTest {
    @Test fun lockHomeAndBothAreDistinctSystemFlags() {
        assertEquals(WallpaperManager.FLAG_SYSTEM, galleryWallpaperTarget(WallpaperManager.FLAG_SYSTEM))
        assertEquals(WallpaperManager.FLAG_LOCK, galleryWallpaperTarget(WallpaperManager.FLAG_LOCK))
        assertEquals(WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK,
            galleryWallpaperTarget(WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK))
    }

    @Test(expected = IllegalArgumentException::class)
    fun unsupportedWallpaperTargetIsRejected() {
        galleryWallpaperTarget(0)
    }
}
