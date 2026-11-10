package com.domenota.medialoader.data.storage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InPlaceTrialPolicyTest {
    private val testName = "ML_SAME_URI_JPEG_123456789abc.jpg"
    private fun permitted(
        owner: String? = "com.domenota.medialoader",
        path: String? = MediaInPlaceChronologyTrial.TRIAL_PATH,
        name: String? = testName, mime: String? = "image/jpeg", id: Long = 123L,
        expectedId: Long = 123L
    ) = InPlaceTrialPolicy.ownedTrial(owner, "com.domenota.medialoader", path,
        name, testName, mime, "image/jpeg", id, expectedId)

    @Test fun oneCopyIdentityRequiresSameMediaStoreIdAndTestDirectory() {
        assertTrue(permitted())
        assertFalse(permitted(owner = "com.other.app"))
        assertFalse(permitted(owner = null))
        assertFalse(permitted(path = "Pictures/MediaLoader/"))
        assertFalse(permitted(path = "Pictures/MediaLoader_QA_EXIF_Test/"))
        assertFalse(permitted(name = "original.jpg"))
        assertFalse(permitted(mime = "image/png"))
        assertFalse(permitted(id = 124L))
        assertFalse(permitted(expectedId = 0L))
    }
}
