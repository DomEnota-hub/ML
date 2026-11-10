package com.domenota.medialoader.data.storage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoChronologyTrialPolicyTest {
    private val old = 1_600_000_000_000L
    private val new = old + 55L * 86_400_000L
    private fun allowed(owner: String? = "com.domenota.medialoader",
        path: String? = "Pictures/MediaLoader/", mediaName: String? = "old.mp4",
        expected: String = "old.mp4", mime: String? = "video/mp4", bytes: Long = 40_000_000L,
        added: Long = new, original: Long = old): Boolean =
        VideoChronologyTrialPolicy.eligible(owner, "com.domenota.medialoader",
            path, mediaName, expected, mime, bytes, added, original)

    @Test fun onlyUniqueOwnedHistoryVideoMayBeChosenForOneCopyTest() {
        assertTrue(allowed())
        assertTrue(allowed(mediaName = "A.MP4", expected = "A.MP4"))
        assertFalse(allowed(owner = "com.other.app"))
        assertFalse(allowed(owner = null))
        assertFalse(allowed(path = "Download/MediaLoader/"))
        assertFalse(allowed(path = "Pictures/MediaLoader_QA_VIDEO_Date_Test/"))
        assertFalse(allowed(mediaName = null))
        assertFalse(allowed(mediaName = "other.mp4"))
        assertFalse(allowed(mime = "video/quicktime"))
        assertFalse(allowed(mediaName = "old.mov", expected = "old.mov"))
        assertFalse(allowed(bytes = 0))
        assertFalse(allowed(bytes = VideoChronologyTrialPolicy.MAX_TEST_VIDEO_BYTES + 1))
        assertFalse(allowed(added = old + 30_000L))
        assertFalse(allowed(original = 0L))
    }
}
