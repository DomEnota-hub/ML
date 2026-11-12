package com.domenota.medialoader.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsIconPaletteTest {
    private val primarySections = listOf(
        SettingIconTone.ACCOUNTS,
        SettingIconTone.DOWNLOAD,
        SettingIconTone.BULK,
        SettingIconTone.VK_COMMUNITY,
        SettingIconTone.EQUALIZER,
        SettingIconTone.MUSIC_SETTINGS,
        SettingIconTone.APPEARANCE,
    )

    private fun contrast(foreground: Color, background: Color): Float {
        val light = maxOf(foreground.luminance(), background.luminance())
        val dark = minOf(foreground.luminance(), background.luminance())
        return (light + .05f) / (dark + .05f)
    }

    @Test fun allSevenSectionIconsUseDistinctForegroundsAndContainersInBothThemes() {
        for (dark in listOf(false, true)) {
            val swatches = primarySections.map { it.swatch(dark) }
            assertTrue(swatches.groupingBy { it.foreground }.eachCount().filterValues { it > 1 }.keys.all { dark && it == AppGold })
            assertEquals(7, swatches.map { it.container }.toSet().size)
        }
    }

    @Test fun darkAndLightPalettesAreActuallyDifferentForEveryTopLevelSection() {
        primarySections.forEach { section ->
            assertNotEquals(section.name + " foreground", section.swatch(true).foreground,
                section.swatch(false).foreground)
            assertNotEquals(section.name + " container", section.swatch(true).container,
                section.swatch(false).container)
        }
    }

    @Test fun everyPaletteIconMaintainsReadableContrastInBothThemes() {
        for (dark in listOf(false, true)) {
            for (tone in SettingIconTone.entries) {
                val swatch = tone.swatch(dark)
                assertTrue(tone.name + " contrast in " +
                    (if (dark) "dark" else "light") + " theme",
                    contrast(swatch.foreground, swatch.container) >= 4.5f)
            }
        }
    }

    @Test fun nestedSectionSemanticColorsRemainAvailable() {
        for (tone in SettingIconTone.entries) {
            assertTrue(tone.swatch(true).container != Color.Transparent)
            assertTrue(tone.swatch(false).container != Color.Transparent)
        }
    }
    /**
     * All 28 visible settings items have their own accent. This catches the
     * shared PLAYER/FOLDER colours that survived the previous QA releases.
     */
    @Test fun everyVisibleSettingsRowHasItsOwnColourInBothThemes() {
        val rows = listOf(
            SettingIconTone.ACCOUNTS,
            SettingIconTone.BACKUP_AUTH,
            SettingIconTone.DOWNLOAD,
            SettingIconTone.DOWNLOAD_FOLDER,
            SettingIconTone.QUALITY,
            SettingIconTone.NOTIFICATIONS,
            SettingIconTone.INSTAGRAM_MODE,
            SettingIconTone.CLOSE_PICKER,
            SettingIconTone.BULK,
            SettingIconTone.VK_COMMUNITY,
            SettingIconTone.EQUALIZER,
            SettingIconTone.MUSIC_SETTINGS,
            SettingIconTone.MUSIC,
            SettingIconTone.RECOGNITION,
            SettingIconTone.RENAME,
            SettingIconTone.APPEARANCE,
            SettingIconTone.THEME,
            SettingIconTone.VIDEO_THUMBNAIL,
            SettingIconTone.BUILTIN_PLAYER,
            SettingIconTone.FILE_ACCESS,
            SettingIconTone.MEDIA_MANAGEMENT,
            SettingIconTone.TRASH,
            SettingIconTone.CLEAR_HISTORY,
            SettingIconTone.YTDLP,
            SettingIconTone.ABOUT,
            SettingIconTone.DIAGNOSTICS,
            SettingIconTone.LOG,
            SettingIconTone.ADVANCED_LOG,
        )
        assertEquals(28, rows.size)
        for (dark in listOf(false, true)) {
            val samples = rows.map { it.swatch(dark) }
            val duplicateColors = samples.groupingBy { it.foreground }.eachCount().filterValues { it > 1 }.keys
            assertTrue("Only the approved shared gold may repeat in dark mode", duplicateColors.all { dark && it == AppGold })
            assertEquals("Duplicate icon background in " + dark, rows.size,
                samples.map { it.container }.toSet().size)
        }
        assertNotEquals(SettingIconTone.VIDEO_THUMBNAIL.swatch(true),
            SettingIconTone.BUILTIN_PLAYER.swatch(true))
        assertNotEquals(SettingIconTone.FILE_ACCESS.swatch(true),
            SettingIconTone.DOWNLOAD_FOLDER.swatch(true))
    }

}
