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
}
