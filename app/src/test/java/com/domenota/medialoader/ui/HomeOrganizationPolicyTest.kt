package com.domenota.medialoader.ui

import com.domenota.medialoader.data.library.AppMode

import org.junit.Assert.*
import org.junit.Test

class HomeOrganizationPolicyTest {
    @Test fun hiddenDownloaderHomeHasNoCardSubscriptionsButGalleryAndRoutesRemainAvailable() {
        assertFalse(HomeOrganizationPolicy.DEFAULT_VISIBLE)
        assertFalse(HomeOrganizationPolicy.observeCollections("home", AppMode.DOWNLOADER, false))
        assertFalse(HomeOrganizationPolicy.observeReview("home", false))
        assertTrue(HomeOrganizationPolicy.observeCollections("home", AppMode.GALLERY, false))
        assertTrue(HomeOrganizationPolicy.observeCollections("collections", AppMode.MUSIC, false))
        assertTrue(HomeOrganizationPolicy.observeReview("media-review", false))
    }
    @Test fun localSwitchReenablesBothCardsWithoutChangingDataOrRoutes() {
        assertTrue(HomeOrganizationPolicy.observeCollections("home", AppMode.DOWNLOADER, true))
        assertTrue(HomeOrganizationPolicy.observeReview("home", true))
        assertFalse(HomeOrganizationPolicy.observeReview("settings", true))
        assertFalse(HomeOrganizationPolicy.observeCollections("settings", AppMode.GALLERY, true))
    }
}
