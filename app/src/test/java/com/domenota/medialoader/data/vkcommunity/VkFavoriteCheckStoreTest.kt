package com.domenota.medialoader.data.vkcommunity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VkFavoriteCheckStoreTest {
    private val favorite = VkFavorite("domain:test", "Test", "https://vk.com/test",
        sinceDate = "2026-10-20", reviewedThroughEpoch = 100L)

    @Test fun batchCodecRetainsJobsAndReviewState() {
        val entry = VkFavoriteCheckEntry(favorite.key, favorite.label, favorite.link, "job-1",
            favorite.sinceDate, favorite.reviewedThroughEpoch, 200L)
        val batch = VkFavoriteCheckBatch("batch-1", listOf(entry.copy(reviewed = true)))
        assertEquals(batch, VkFavoriteCheckStore.decode(VkFavoriteCheckStore.encode(batch)))
    }

    @Test fun staleFavoriteOrIncompleteScanCannotAdvanceCheckpoint() {
        val entry = VkFavoriteCheckEntry(favorite.key, favorite.label, favorite.link, "job-1",
            favorite.sinceDate, favorite.reviewedThroughEpoch, 200L)
        assertFalse(VkFavoriteCheckStore.canReview(entry,
            favorite.copy(reviewedThroughEpoch = 199L), scanComplete = true))
        assertFalse(VkFavoriteCheckStore.canReview(entry,
            favorite.copy(sinceDate = "2026-10-21"), scanComplete = true))
        assertFalse(VkFavoriteCheckStore.canReview(entry, favorite, scanComplete = false))
        assertTrue(VkFavoriteCheckStore.canReview(entry, favorite, scanComplete = true))
    }

    @Test fun lowerBoundUsesChosenDateUntilFirstCompletedReview() {
        assertEquals(100L, VkFavoriteCheckStore.afterEpoch(favorite, java.time.ZoneId.of("UTC")))
        assertEquals(1_792_454_399L, VkFavoriteCheckStore.afterEpoch(
            favorite.copy(reviewedThroughEpoch = null), java.time.ZoneId.of("UTC")))
    }
}
