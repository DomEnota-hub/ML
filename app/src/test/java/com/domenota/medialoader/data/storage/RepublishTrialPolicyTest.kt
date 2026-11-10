package com.domenota.medialoader.data.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RepublishTrialPolicyTest {
    @Test fun candidatesMustBeOwnedPublicCopiedPicturesOnly() {
        val historic = 1_600_000_000_000L
        val nowAddedSeconds = (historic + 80L * 86_400_000L) / 1000L
        fun check(owner: String? = "com.domenota.medialoader",
                  relative: String? = "Pictures/MediaLoader/",
                  name: String? = "old.jpg",
                  expected: String = "old.jpg",
                  mime: String? = "image/jpeg",
                  bytes: Long = 500,
                  addedSeconds: Long = nowAddedSeconds): Boolean =
            RepublishTrialPolicy.eligible(owner, "com.domenota.medialoader",
                relative, name, expected, mime, bytes, addedSeconds, historic)

        assertTrue(check())
        assertFalse(check(owner = "com.other.app"))
        assertFalse(check(owner = null))
        assertFalse(check(relative = "Pictures/Private/"))
        assertFalse(check(relative = "Pictures/MediaLoader_QA_Order_Test/"))
        assertFalse(check(relative = "Download/MediaLoader/"))
        assertFalse(check(name = "someoneElse.jpg"))
        assertFalse(check(mime = "video/mp4"))
        assertFalse(check(bytes = 0L))
        assertFalse(check(bytes = 25L * 1024 * 1024))
        assertFalse(check(addedSeconds = (historic + 30_000L) / 1000L))
    }

    @Test fun samplesAreOldestMiddleNewestWithoutInventingHistory() {
        val chronological = (1..11).toList()
        assertEquals(listOf(1, 6, 11), RepublishTrialPolicy.selectThree(chronological))
        assertEquals(emptyList<Int>(), RepublishTrialPolicy.selectThree(listOf(1, 2)))
        assertEquals(listOf(1, 2, 3), RepublishTrialPolicy.selectThree(listOf(1, 2, 3)))
    }

    @Test fun releaseTimestampsMustDifferByWholeSeconds() {
        assertTrue(RepublishTrialPolicy.strictlyIncreasing(listOf(100L, 101L, 102L)))
        assertFalse(RepublishTrialPolicy.strictlyIncreasing(listOf(100L, 100L, 102L)))
        assertFalse(RepublishTrialPolicy.strictlyIncreasing(listOf(103L, 102L, 101L)))
        assertFalse(RepublishTrialPolicy.strictlyIncreasing(listOf(100L, 101L)))
    }
}
