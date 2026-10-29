package com.domenota.medialoader.data.instagramprofile

import java.time.LocalDate
import java.time.ZoneId
import com.domenota.medialoader.core.model.MediaItem
import com.domenota.medialoader.core.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstagramProfileScanPolicyTest {
    @Test fun stopsAtTheStartDateWhenThePageIsNewestFirst() {
        val zone = ZoneId.of("UTC")
        val start = LocalDate.of(2026, 10, 10)
        val page = listOf(InstagramProfilePost("Today123", start.plusDays(1).atStartOfDay(zone).toEpochSecond(), false, null, 1),
            InstagramProfilePost("Before1", start.minusDays(1).atStartOfDay(zone).toEpochSecond(), false, null, 1))
        val decision = InstagramProfileScanPolicy.filter(page, start, start.plusDays(2), zone)
        assertEquals(listOf("Today123"), decision.posts.map { it.code })
        assertTrue(decision.reachedStart)
    }

    @Test fun noDateStopsAtThreeHundredButRemembersMorePages() {
        assertFalse(InstagramProfileScanPolicy.reachedBatchLimit(299, 300))
        assertTrue(InstagramProfileScanPolicy.reachedBatchLimit(300, 300))
    }

    @Test fun samePostFromFeedAndReelsHasOneIdentity() {
        val old = InstagramProfilePost("ABCDE1", 10, false, null, 1)
        val reel = old.copy(video = true, previewUrl = "https://cdninstagram.com/x.jpg")
        assertEquals(listOf(reel), InstagramProfileScanPolicy.merge(listOf(old), listOf(reel)))
    }

    @Test fun individualDeselectionFiltersOnlyThatVisual() {
        val a = MediaItem("POST_1", "instagram", MediaType.PHOTO, "a.jpg", "https://cdninstagram.com/a.jpg")
        val b = a.copy(id = "POST_2", originalName = "b.mp4", type = MediaType.VIDEO)
        assertEquals(listOf(b), InstagramProfileScanPolicy.selectedVisuals(listOf(a, b), setOf(a.id)))
    }
}
