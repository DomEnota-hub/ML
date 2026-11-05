package com.domenota.medialoader.ui.screens.gallery

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class GalleryV10SubmenuPlacementTest {
    @Test fun extractPopupIsNearTheTappedMenuRow() {
        assertEquals(278.dp, galleryV10SubmenuTop(286.dp, 800.dp, 124.dp))
    }

    @Test fun popupIsClampedOnShortScreensAndCannotDisappearUnderNavigationBar() {
        assertEquals(88.dp, galleryV10SubmenuTop(500.dp, 480.dp, 384.dp))
        assertEquals(8.dp, galleryV10SubmenuTop((-30).dp, 480.dp, 124.dp))
    }
}
