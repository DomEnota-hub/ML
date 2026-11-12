package com.domenota.medialoader.data.editor

import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.Assert.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
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
    @Test fun phoneAliasResolvesOriginalHistoryWithoutCreatingDuplicate() {
        val phone = mp3().copy(id = "phone:audio:123")
        val history = mp3("content://media/external_primary/audio/media/123").copy(id = "download-1", providerId = "youtube")
        assertEquals(history, AudioReplacePolicy.resolve(phone, listOf(history))?.historyRow)
        assertEquals(phone.savedUri, AudioReplacePolicy.resolve(phone, listOf(history))?.row?.savedUri)
        assertNull(AudioReplacePolicy.resolve(phone, listOf(history, history.copy(id = "duplicate"))))
        assertNull(AudioReplacePolicy.resolve(phone, listOf(history.copy(hidden = true))))
    }
    @Test fun independentPhoneTrackDoesNotInventDownloadHistory() {
        val phone = mp3().copy(id = "phone:audio:123")
        val target = requireNotNull(AudioReplacePolicy.resolve(phone, emptyList()))
        assertNull(target.historyRow)
        assertEquals(phone, target.row)
        assertNull(AudioReplacePolicy.resolve(phone.copy(providerId = "youtube"), emptyList()))
    }
}
