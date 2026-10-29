package com.domenota.medialoader.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbnailPrefetchPolicyTest {
    @Test fun workStaysBoundedForLargeLibrariesAndExcludesVisibleCards() {
        listOf(100, 1000, 5000).forEach { count ->
            val result = ThumbnailPrefetchPolicy.indices(20, 25, 19, count)
            assertEquals(12, result.size)
            assertEquals(26, result.first())
            assertTrue(result.none { it in 20..25 })
        }
    }
    @Test fun reversingDirectionPreparesEarlierCardsFirstWithoutCrossingBounds() {
        assertEquals(19, ThumbnailPrefetchPolicy.indices(20, 25, 21, 100).first())
        assertEquals((0..7).toList(), ThumbnailPrefetchPolicy.indices(0, -1, 0, 100))
        assertTrue(ThumbnailPrefetchPolicy.indices(98, 99, 97, 100).all { it in 0..99 })
    }
}
