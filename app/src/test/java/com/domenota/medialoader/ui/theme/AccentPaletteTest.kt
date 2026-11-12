package com.domenota.medialoader.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.*
import org.junit.Test

class AccentPaletteTest {
    @Test fun musicAndEditorAccentsAreIndependentOfLightTheme() {
        assertEquals(Color(0xFFFFBD19), musicAccent(true))
        assertEquals(Color(0xFF7036CB), musicAccent(false))
        assertEquals(LightAppAccent, LightColors.primary)
        for (scheme in listOf(MediaLoaderColors, editorColorScheme())) {
            assertEquals(AppGold, scheme.primary)
            assertEquals(AppGold, scheme.secondary)
            assertEquals(AppGold, scheme.tertiary)
            assertEquals(AppGold, scheme.surfaceTint)
            assertTrue(scheme.onPrimary.luminance() < .1f)
            assertNotEquals(AccentPurple, scheme.inversePrimary)
        }
    }
    @Test fun shazamKeepsEachOriginalSurfaceColor() {
        assertEquals(Color(0xFF7A4DFF), originalShazamColor(true))
        assertEquals(Color(0xFF762DFF), originalShazamColor(false))
        assertEquals(Color(0xFFBB9CFF), originalShazamColor(true, true))
        assertEquals(Color(0xFF7036CB), originalShazamColor(false, true))
        for (tone in listOf(SettingIconTone.ACCOUNTS, SettingIconTone.PLAYER, SettingIconTone.THEME))
            assertEquals(AppGold, tone.swatch(true).foreground)
    }
}
