package com.domenota.medialoader.data.library

import org.junit.Assert.*
import org.junit.Test

class MusicMetadataCheckpointPolicyTest {
    private val track = DuplicateTrack("a", "Track", null, "Track.mp3", "content://test/1",
        100L, 200L, "whole-file", "encoded-audio", 5000L, null, "Music",
        acoustic = AcousticFingerprint(listOf(AcousticLandmark(12, 3))), privacyEpoch = 7L)

    private fun reuse(candidate: DuplicateTrack = track, uri: String = track.uri,
                      bytes: Long = track.bytes, modified: Long? = track.modified,
                      folder: String? = track.folder, epoch: Long = track.privacyEpoch,
                      filename: String = track.filename) =
        MusicMetadataCheckpointPolicy.forOtherMode(candidate, uri, bytes, modified, folder, epoch, filename)

    @Test fun reusesExactMetadataButNeverOtherModeAcousticFingerprint() {
        val reused = requireNotNull(reuse())
        assertEquals(track.copy(acoustic = null), reused)
        assertEquals("encoded-audio", reused.payloadSha)
        assertEquals("whole-file", reused.sha)
    }

    @Test fun rejectsChangedOrUnknownFileStamp() {
        assertNull(reuse(bytes = 101))
        assertNull(reuse(modified = 201))
        assertNull(reuse(modified = null))
        assertNull(reuse(folder = "Other"))
    }

    @Test fun rejectsDifferentUriFilenameOrPrivacyEpoch() {
        assertNull(reuse(uri = "content://test/2"))
        assertNull(reuse(filename = "Renamed.mp3"))
        assertNull(reuse(epoch = 8))
    }

    @Test fun missingEncodedEvidenceRequiresFreshExtraction() {
        assertNull(reuse(track.copy(payloadSha = null)))
        assertNull(reuse(track.copy(payloadSha = "")))
        assertNull(reuse(track.copy(sha = "")))
    }
}
