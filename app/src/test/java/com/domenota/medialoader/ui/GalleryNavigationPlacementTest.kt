package com.domenota.medialoader.ui

import com.domenota.medialoader.data.library.AppMode
import com.domenota.medialoader.ui.navigation.AppDestination
import org.junit.Assert.*
import org.junit.Test

/** Regression: Scaffold bottomBar must not reserve a dark band below gallery media. */
class GalleryNavigationPlacementTest {
    @Test fun galleryHomeUsesFloatingOverlayInsteadOfScaffoldBottomBar() {
        assertTrue(galleryBottomSwitcherIsOverlay(AppMode.GALLERY, AppDestination.Home.route))
    }

    @Test fun musicAndDownloaderKeepTheirExistingBottomBars() {
        assertFalse(galleryBottomSwitcherIsOverlay(AppMode.MUSIC, AppDestination.Home.route))
        assertFalse(galleryBottomSwitcherIsOverlay(AppMode.DOWNLOADER, AppDestination.Home.route))
    }

    @Test fun gallerySettingsAndOtherDestinationsKeepNormalScaffoldBars() {
        assertFalse(galleryBottomSwitcherIsOverlay(AppMode.GALLERY, AppDestination.Settings.route))
        assertFalse(galleryBottomSwitcherIsOverlay(AppMode.GALLERY, "trash"))
        assertFalse(galleryBottomSwitcherIsOverlay(AppMode.GALLERY, null))
    }
}
