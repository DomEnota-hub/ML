package com.domenota.medialoader.data.editor

import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioReplacePolicyTest {
    private fun mp3(uri: String = "content://media/external/audio/media/123") =
        DownloadEntity(
            id = "audio-123", providerId = "device", originalName = "Старая песня.mp3",
            mediaType = MediaType.AUDIO, state = DownloadState.COMPLETED,
            savedUri = uri, sizeBytes = 1024, createdAtEpochMillis = 1000L,
        )

    @Test fun publicCompletedMediaStoreMp3CanReplaceStableSource() {
        assertTrue(AudioReplacePolicy.eligible(mp3()))
        assertTrue(AudioReplacePolicy.eligible(
            mp3("content://media/external_primary/audio/media/100")))
    }

    @Test fun alwaysFailsClosedForPrivateHiddenAndNonMp3AndUnsupportedUris() {
        assertFalse(AudioReplacePolicy.eligible(mp3().copy(isPrivate = true)))
        assertFalse(AudioReplacePolicy.eligible(mp3().copy(hidden = true)))
        assertFalse(AudioReplacePolicy.eligible(mp3().copy(state = DownloadState.FAILED)))
        assertFalse(AudioReplacePolicy.eligible(mp3().copy(originalName = "song.m4a")))
        assertFalse(AudioReplacePolicy.eligible(mp3().copy(mediaType = MediaType.VIDEO)))
        assertFalse(AudioReplacePolicy.eligible(mp3("file:///storage/music/song.mp3")))
        assertFalse(AudioReplacePolicy.eligible(mp3("content://other.app/music/123")))
        assertFalse(AudioReplacePolicy.eligible(mp3().copy(savedUri = null)))
    }

    @Test fun ordinaryAudioEditAlwaysDefaultsToNonDestructiveCopy() {
        assertFalse(AudioEditSpec().replaceOriginal)
        assertTrue(AudioEditSpec(replaceOriginal = true).replaceOriginal)
    }
}
