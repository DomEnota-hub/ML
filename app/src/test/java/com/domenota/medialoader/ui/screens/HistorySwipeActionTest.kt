package com.domenota.medialoader.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class HistorySwipeActionTest {
    @Test fun rightSwipeRequestsFavoriteToggle() {
        assertEquals(HistorySwipeAction.FAVORITE, historySwipeAction(100f, 88f))
        assertEquals(HistorySwipeAction.FAVORITE, historySwipeAction(88f, 88f))
    }

    @Test fun groupTargetAddsMixedAndRemovesOnlyWhenAllAreFavorites() {
        val ids = listOf("a", "b", "c")
        assertTrue(historySwipeFavoriteTarget(ids) { it == "a" })
        assertTrue(historySwipeFavoriteTarget(ids) { false })
        assertFalse(historySwipeFavoriteTarget(ids) { true })
        assertFalse(historySwipeFavoriteTarget(emptyList()) { false })
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
