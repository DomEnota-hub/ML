package com.domenota.medialoader.ui.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BottomTabPolicyTest {
    @Test fun repeatedDownloadsTapRequestsScrollToTop() {
        assertTrue(BottomTabPolicy.shouldScrollDownloadsToTop(
            currentRoute = AppDestination.Downloads.route,
            destination = AppDestination.Downloads,
        ))
        assertFalse(BottomTabPolicy.shouldScrollDownloadsToTop(
            currentRoute = AppDestination.Home.route,
            destination = AppDestination.Downloads,
        ))
        assertFalse(BottomTabPolicy.shouldScrollDownloadsToTop(
            currentRoute = AppDestination.Downloads.route,
            destination = AppDestination.Home,
        ))
    }
}
