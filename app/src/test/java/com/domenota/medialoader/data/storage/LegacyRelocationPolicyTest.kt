package com.domenota.medialoader.data.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyRelocationPolicyTest {
    private fun destination(
        oldPath: String? = "Download/MediaLoader/",
        owner: String? = "com.domenota.medialoader",
        name: String? = "old.jpg",
        expectedName: String = "old.jpg",
        mime: String? = "image/jpeg",
        expectedMime: String = "image/jpeg",
        targetNameTaken: Boolean = false,
    ): String? = LegacyRelocationPolicy.targetPath(
        oldPath, owner, "com.domenota.medialoader", name,
        expectedName, mime, expectedMime, "Pictures/MediaLoader/", targetNameTaken)

    @Test fun onlyAppOwnedMediaStoreItemsHaveInPlaceDestination() {
        assertEquals("Pictures/MediaLoader/", destination())
        assertEquals("Music/MediaLoader/", destination(name = "old.mp3",
            expectedName = "old.mp3", mime = "audio/mpeg", expectedMime = "audio/mpeg"))
        assertNull(destination(oldPath = "Pictures/MediaLoader/"))
        assertNull(destination(oldPath = "Download/../Other/"))
        assertNull(destination(owner = "com.other.app"))
        assertNull(destination(owner = null))
        assertNull(destination(name = "renamed.jpg"))
        assertNull(destination(mime = "video/mp4"))
        assertNull(destination(targetNameTaken = true))
    }

    @Test fun anyDateIdOrByteChangeMeansMoveIsNotVerified() {
        fun unchanged(name: String? = "file.jpg", size: Long? = 123L,
                      added: Long? = 444L, modified: Long? = 222L) =
            LegacyRelocationPolicy.unchangedMetadata("file.jpg", name, 123L, size,
                444L, added, 222L, modified)
        assertTrue(unchanged())
        assertFalse(unchanged(name = "file (1).jpg"))
        assertFalse(unchanged(size = 124L))
        assertFalse(unchanged(added = 445L))
        assertFalse(unchanged(modified = 223L))
        assertFalse(LegacyRelocationPolicy.unchangedMetadata(
            "file.jpg", "file.jpg", 0L, 0L, 444L, 444L, 222L, 222L))
    }
}
