package com.domenota.medialoader.data.storage

import com.domenota.medialoader.core.model.MediaType
import org.junit.Assert.*
import org.junit.Test

class MediaDateRepairPolicyTest {
    private val now = System.currentTimeMillis()
    private val past = now - 10L * 86400000L
    private fun candidate(owner: String? = "com.domenota.medialoader",
        name: String? = "old.jpg", originalName: String = "old.jpg",
        path: String? = "Pictures/MediaLoader/", mime: String? = "image/jpeg",
        kind: MediaType = MediaType.PHOTO, added: Long = now,
        original: Long = past): Boolean = MediaDateRepairPolicy.isCandidate(
            owner, "com.domenota.medialoader", name, originalName, path,
            mime, kind, added, original)

    @Test fun onlyVerifiedMediaLoaderCopiesCanBeTrialed() {
        assertTrue(candidate())
        assertFalse(candidate(owner = "other.app"))
        assertFalse(candidate(owner = null))
        assertFalse(candidate(name = "another.jpg"))
        assertFalse(candidate(path = "Download/MediaLoader/"))
        assertFalse(candidate(path = "Pictures/Private/"))
        assertFalse(candidate(path = "Pictures/MediaLoader/../Private/"))
        assertFalse(candidate(mime = "video/mp4"))
        assertFalse(candidate(kind = MediaType.VIDEO))
        assertFalse(candidate(added = past + 1000L))
        assertFalse(candidate(original = now + 100000L))
        assertFalse(candidate(original = 0L))
        assertTrue(candidate(name = "song.mp3", originalName = "song.mp3",
            path = "Music/MediaLoader/", mime = "audio/mpeg", kind = MediaType.AUDIO))
    }

    @Test fun oldFilesAreIdentifiedByHistoricalDownloadTimeNotFilenameHeuristics() {
        assertTrue(MediaDateRepairPolicy.validTime(past, now))
        assertFalse(MediaDateRepairPolicy.validTime(now + 86400000L, now))
        assertFalse(MediaDateRepairPolicy.validTime(1234L, now))
    }
}
