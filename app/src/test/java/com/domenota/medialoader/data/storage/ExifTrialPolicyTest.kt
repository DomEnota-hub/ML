package com.domenota.medialoader.data.storage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExifTrialPolicyTest {
    private val date = 1_600_000_000_000L
    private val added = date + 75L * 86_400_000L

    private fun allowed(owner: String? = "com.domenota.medialoader",
        path: String? = "Pictures/MediaLoader/",
        name: String? = "historical.jpg",
        expected: String = "historical.jpg",
        mime: String? = "image/jpeg", size: Long = 456_000L,
        addedMillis: Long = added, originalMillis: Long = date): Boolean =
        ExifTrialPolicy.eligible(owner, "com.domenota.medialoader", path,
            name, expected, mime, size, addedMillis, originalMillis)

    @Test fun onlyUniqueOwnedPublicJpegCandidatesArePermitted() {
        assertTrue(allowed())
        assertTrue(allowed(name = "old.JPEG", expected = "old.JPEG"))
        assertFalse(allowed(owner = "other.app"))
        assertFalse(allowed(owner = null))
        assertFalse(allowed(path = "Download/MediaLoader/"))
        assertFalse(allowed(path = "Pictures/MediaLoader_QA_EXIF_Test/"))
        assertFalse(allowed(path = "Pictures/Other/"))
        assertFalse(allowed(name = "different.jpg"))
        assertFalse(allowed(name = null))
        assertFalse(allowed(mime = "video/mp4"))
        assertFalse(allowed(mime = "image/png"))
        assertFalse(allowed(size = 0))
        assertFalse(allowed(size = 15L * 1024 * 1024))
        assertFalse(allowed(addedMillis = date + 30_000L))
        assertFalse(allowed(originalMillis = 0L))
    }

    @Test fun trueShootingMetadataIsNeverOverwritten() {
        assertFalse(ExifTrialPolicy.hasAnyDate(null, null, null))
        assertFalse(ExifTrialPolicy.hasAnyDate("", "", ""))
        assertTrue(ExifTrialPolicy.hasAnyDate("2025:02:03 14:15:16", null, null))
        assertTrue(ExifTrialPolicy.hasAnyDate(null, "2025:02:03 14:15:16", null))
        assertTrue(ExifTrialPolicy.hasAnyDate(null, null, "2025:02:03 14:15:16"))
    }
}