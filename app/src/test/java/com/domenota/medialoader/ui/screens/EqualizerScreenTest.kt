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
    @Test fun lockedEqualizerKeepsSavedGainWhileAllBandsStayDisabled() {
        var settings by mutableStateOf(EqualizerSettings(
            enabled = false,
            gainsDb = listOf(6f, 3f, 1f, -1f, 2f),
            preset = "Свой",
        ))
        compose.setContent {
            MaterialTheme { EqualizerScreen(settings, 48000, { settings = it }, {}) }
        }
        compose.onNodeWithContentDescription("60 Гц").assertIsNotEnabled()
        compose.onNodeWithContentDescription("230 Гц").assertIsNotEnabled()
        assertEquals(listOf(6f, 3f, 1f, -1f, 2f), settings.gainsDb)
        compose.onNodeWithContentDescription("Включить эквалайзер").performClick()
        compose.onNodeWithContentDescription("60 Гц").assertIsEnabled()
        assertEquals("Unlock must preserve the exact per-band gain values",
            listOf(6f, 3f, 1f, -1f, 2f), settings.gainsDb)
    }

    @Test fun trackScopeIsExplicitAndCanReturnToGlobalSettings() {
        var global by mutableStateOf(false)
        var cleared by mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                EqualizerScreen(EqualizerSettings(), 48000, {}, {},
                    trackTitle = "Проверочный трек", editGlobal = global,
                    onEditGlobal = { global = it }, hasTrackProfile = true,
                    onUseGlobal = { cleared = true })
            }
        }
        compose.onNodeWithText("Проверочный трек").assertExists()
        compose.onNodeWithText("Для трека").assertIsSelected()
        compose.onNodeWithText("Использовать общие настройки").performClick()
        assertTrue(cleared)
        compose.onNodeWithText("Общие").performClick()
        compose.onNodeWithText("Общие").assertIsSelected()
        compose.onNodeWithText("Использовать общие настройки").assertDoesNotExist()
    }

    @Test fun lowRateClearlyDisablesUnsupportedBand() {
        compose.setContent { MaterialTheme { EqualizerScreen(EqualizerSettings(), 8000, {}, {}) } }
        compose.onNodeWithContentDescription("14 кГц").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Недоступна при частоте текущего трека 8000 Гц").assertExists()
    }
}
