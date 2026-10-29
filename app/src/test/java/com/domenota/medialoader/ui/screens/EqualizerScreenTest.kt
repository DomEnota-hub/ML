package com.domenota.medialoader.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.domenota.medialoader.data.playback.EqualizerSettings
import com.domenota.medialoader.ui.screens.music.EqualizerScreen
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h800dp-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EqualizerScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun presetsManualGainBypassAndResetReachRealSettings() {
        var settings by mutableStateOf(EqualizerSettings())
        compose.setContent { MaterialTheme { EqualizerScreen(settings, 48000, { settings = it }, {}) } }
        compose.onNodeWithText("Бас").performClick()
        assertEquals(listOf(6f, 3f, 0f, -1f, 0f), settings.gainsDb)
        compose.onNodeWithContentDescription("60 Гц").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(-4f) }
        assertEquals("Свой", settings.preset)
        assertEquals(-4f, settings.gainsDb[0])
        compose.onNodeWithContentDescription("Включить эквалайзер").performClick()
        assertFalse(settings.enabled)
        compose.onNodeWithContentDescription("60 Гц").assertIsNotEnabled()
        compose.onNodeWithText("Сбросить настройки").performScrollTo().performClick()
        assertEquals(List(5) { 0f }, settings.gainsDb)
        assertFalse(settings.enabled)
    }
    @Test fun lowRateClearlyDisablesUnsupportedBand() {
        compose.setContent { MaterialTheme { EqualizerScreen(EqualizerSettings(), 8000, {}, {}) } }
        compose.onNodeWithContentDescription("14 кГц").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Недоступна при частоте текущего трека 8000 Гц").assertExists()
    }
}
