package com.domenota.medialoader.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HistorySwipeActionTest {
    @Test fun rightSwipeAlwaysAddsToFavorites() {
        assertEquals(HistorySwipeAction.FAVORITE, historySwipeAction(100f, 88f))
        assertEquals(HistorySwipeAction.FAVORITE, historySwipeAction(88f, 88f))
    }

    @Test fun leftSwipeAlwaysMovesToPrivate() {
        assertEquals(HistorySwipeAction.PRIVATE, historySwipeAction(-120f, 88f))
        assertEquals(HistorySwipeAction.PRIVATE, historySwipeAction(-88f, 88f))
    }

    @Test fun shortOrVerticalGesturesDoNothing() {
        assertNull(historySwipeAction(0f, 88f))
        assertNull(historySwipeAction(30f, 88f))
        assertNull(historySwipeAction(-87f, 88f))
    }
}
