package com.domenota.medialoader.data.playback

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EqualizerSettingsRepositoryTest {
    @Test fun restoresDisabledCustomSettingsAndPresetAfterRestart() = runBlocking {
        val context: Context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("audio_equalizer", 0).edit().clear().commit()
        val repository = EqualizerSettingsRepository(context)
        repository.awaitLoaded()
        val settings = EqualizerSettings(false, listOf(-12f, 3f, 2f, 0f, 12f), "Свой")
        repository.setSettings(settings)
        val restored = EqualizerSettingsRepository(context)
        restored.awaitLoaded()
        assertEquals(settings, restored.settings.value)
        restored.setSettings(settings.withPreset("Бас"))
        assertEquals(listOf(6f, 3f, 0f, -1f, 0f), restored.settings.value.gainsDb)
        assertFalse(restored.settings.value.enabled)
    }
    @Test fun independentProfilesRestoreAndRebindWithoutLeakingPreviousTrack() = runBlocking {
        val context: Context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("audio_equalizer", 0).edit().clear().commit()
        val repository = EqualizerSettingsRepository(context)
        repository.awaitLoaded()
        val a = EqualizerTrack(setOf("id:download-a", "media:external:42", "uri:content://old/a"), "Same title")
        val b = EqualizerTrack(setOf("id:download-b", "media:external:43"), "Same title")
        val neutral = EqualizerSettings().withPreset("Вокал")
        val bass = EqualizerSettings().withPreset("Бас")
        val disabled = EqualizerSettings(enabled = false).withPreset("Рок")
        repository.setSettings(neutral)
        repository.setTrackSettings(a, bass)
        repository.setTrackSettings(b, disabled)
        val restored = EqualizerSettingsRepository(context)
        restored.awaitLoaded()
        val rebound = EqualizerTrack(setOf("id:device-new-a", "media:external:42", "uri:content://new/a"), "Renamed")
        assertEquals(bass, restored.state.value.effective(rebound))
        assertEquals(disabled, restored.state.value.effective(b))
        assertEquals(neutral, restored.state.value.effective(EqualizerTrack(setOf("id:unknown"), "Same title")))
        assertEquals(neutral, restored.state.value.effective(null))
        restored.setTrackSettings(rebound, null)
        assertEquals(neutral, restored.state.value.effective(a))
        assertEquals(disabled, restored.state.value.effective(b))
        val third = EqualizerSettingsRepository(context)
        third.awaitLoaded()
        assertEquals(neutral, third.state.value.effective(a))
        assertEquals(disabled, third.state.value.effective(b))
    }

    @Test fun profileChangesDoNotChangeOtherTracksOrGlobalAndAmbiguityFailsClosed() {
        val a = EqualizerTrack(setOf("id:a"), "A")
        val b = EqualizerTrack(setOf("id:b"), "B")
        val bass = EqualizerSettings().withPreset("Бас")
        val rock = EqualizerSettings().withPreset("Рок")
        val state = EqualizerProfileState().changed(a, bass).changed(b, rock)
        val ambiguous = EqualizerTrack(setOf("id:a", "id:b"), "Ambiguous")
        assertEquals(state.global, state.effective(ambiguous))
        assertTrue(runCatching { state.changed(ambiguous, bass) }.isFailure)
        val updated = state.changed(null, EqualizerSettings(enabled = false))
        assertEquals(bass, updated.effective(a))
        assertEquals(rock, updated.effective(b))
        assertFalse(updated.effective(null).enabled)
    }

    @Test fun mediaItemIdentityUsesAliasesAndRejectsPrivateOrUnaddressableMedia() {
        val row = com.domenota.medialoader.core.database.DownloadEntity(
            "download-a", "device", "song.mp3",
            com.domenota.medialoader.core.model.MediaType.AUDIO,
            com.domenota.medialoader.core.model.DownloadState.COMPLETED,
            "content://media/external/audio/media/42", 123L, 0L)
        val media = AudioPlaybackMetadata.item(row, setOf("media:external:42", "file-stat:example"))
        val track = requireNotNull(EqualizerTrack.fromMediaItem(media))
        assertTrue("id:download-a" in track.keys)
        assertTrue("media:external:42" in track.keys)
        assertTrue("file-stat:example" in track.keys)
        assertNull(EqualizerTrack.fromMediaItem(AudioPlaybackMetadata.item(row.copy(isPrivate = true), emptySet())))
        assertNull(EqualizerTrack.fromMediaItem(androidx.media3.common.MediaItem.Builder().setMediaId("missing").build()))
    }
}
