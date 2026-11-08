package com.domenota.medialoader.ui.screens.gallery

import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class GalleryLandscapeOrientationTest {
    @Test fun landscapeRotateAllowsBothPhysicalLandscapeSides() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            galleryViewerRequestedOrientation(isLandscape = false),
        )
        assertNotEquals(
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            galleryViewerRequestedOrientation(isLandscape = false),
        )
        assertNotEquals(
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
            galleryViewerRequestedOrientation(isLandscape = false),
        )
    }

    @Test fun rotationBackToPortraitIsExplicit() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            galleryViewerRequestedOrientation(isLandscape = true),
        )
    }
}
