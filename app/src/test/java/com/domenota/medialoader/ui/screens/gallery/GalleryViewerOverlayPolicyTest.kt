package com.domenota.medialoader.ui.screens.gallery

import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class GalleryViewerOverlayPolicyTest {
    @Test fun headerAndBottomToolbarRemainTranslucentOnBothPhotoAndVideo() {
        // Shared v10 Chrome components are used on every photo/video pager page.
        assertEquals(viewerOverlayColor, viewerHeaderOverlayColor)
        assertEquals(viewerOverlayColor, viewerBottomOverlayColor)
        assertEquals(viewerHeaderOverlayColor, viewerBottomOverlayColor)
        assertTrue(viewerHeaderOverlayColor.alpha > .25f)
        assertTrue(viewerHeaderOverlayColor.alpha < 1f)
        assertTrue(viewerBottomOverlayColor.alpha > .25f)
        assertTrue(viewerBottomOverlayColor.alpha < 1f)
    }
}
