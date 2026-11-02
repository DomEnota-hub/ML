package com.domenota.medialoader.ui.screens.gallery

import org.junit.Assert.*
import org.junit.Test

class GalleryScrollRequestTrackerTest {
    @Test fun restoredGalleryNeverJumpsToTopOnInitialComposition() {
        val tracker = GalleryScrollRequestTracker(5)
        assertFalse(tracker.shouldScrollToTop(5, true))
        assertFalse(tracker.shouldScrollToTop(5, false))
        assertFalse(tracker.shouldScrollToTop(5, true))
    }

    @Test fun explicitNewTopRequestStillWorks() {
        val tracker = GalleryScrollRequestTracker(0)
        assertFalse(tracker.shouldScrollToTop(0, true))
        assertTrue(tracker.shouldScrollToTop(1, true))
        assertFalse(tracker.shouldScrollToTop(1, true))
        assertFalse(tracker.shouldScrollToTop(2, false))
        assertFalse(tracker.shouldScrollToTop(2, true))
        assertTrue(tracker.shouldScrollToTop(3, true))
    }
}
