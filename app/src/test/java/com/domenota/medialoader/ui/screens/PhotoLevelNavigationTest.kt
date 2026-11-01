package com.domenota.medialoader.ui.screens

import com.domenota.medialoader.data.editor.HslCorrection
import com.domenota.medialoader.data.editor.HslRange
import com.domenota.medialoader.data.editor.PhotoColorAdjustments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoLevelNavigationTest {
    @Test fun allLevelToolsBelongToExactlyOneVisibleGroup() {
        val grouped = PhotoLevelGroup.entries.flatMap { group ->
            PhotoLevelControl.entries.filter { photoLevelGroup(it) == group }
        }
        assertEquals(PhotoLevelControl.entries.toSet(), grouped.toSet())
        assertEquals(PhotoLevelControl.entries.size, grouped.size)
        assertTrue(PhotoLevelGroup.entries.all { group ->
            PhotoLevelControl.entries.any { photoLevelGroup(it) == group }
        })
    }

    @Test fun hslAndPreviouslyExistingEffectsAreNotDuplicated() {
        assertEquals(PhotoLevelGroup.COLORS, photoLevelGroup(PhotoLevelControl.HSL))
        assertEquals(PhotoLevelGroup.DETAILS, photoLevelGroup(PhotoLevelControl.GLOW))
        assertEquals(PhotoLevelGroup.DETAILS, photoLevelGroup(PhotoLevelControl.VIGNETTE))
        assertEquals(PhotoLevelGroup.DETAILS, photoLevelGroup(PhotoLevelControl.DISPERSION))
        assertEquals(1, PhotoLevelControl.entries.count { it == PhotoLevelControl.HSL })
        assertEquals(1, PhotoLevelControl.entries.count { it == PhotoLevelControl.DISPERSION })
    }

    @Test fun allLevelsHaveDistinctRecognizableIcons() {
        val images = PhotoLevelControl.entries.map(::photoLevelIcon)
        assertEquals(PhotoLevelControl.entries.size, images.size)
        assertEquals(images.size, images.distinct().size)
    }

    @Test fun hslEightChannelsPreserveTheirIndependentValues() {
        assertEquals(8, HslRange.entries.size)
        assertEquals(HslRange.entries.size,
            HslRange.entries.map(::photoHslChannelColor).distinct().size)
        val initial = PhotoColorAdjustments(hsl = mapOf(
            HslRange.RED to HslCorrection(hue = 17f),
            HslRange.BLUE to HslCorrection(lightness = -20f)))
        val changed = initial.copy(hsl = initial.hsl + (
            HslRange.GREEN to HslCorrection(saturation = 44f)))
        assertEquals(17f, changed.hsl[HslRange.RED]!!.hue, 0f)
        assertEquals(-20f, changed.hsl[HslRange.BLUE]!!.lightness, 0f)
        assertEquals(44f, changed.hsl[HslRange.GREEN]!!.saturation, 0f)
        assertNotEquals(initial, changed)
    }

    @Test fun movingFineTuningControlsDoesNotChangeTheirExportValues() {
        val original = PhotoColorAdjustments(
            glow = 33f, glowSize = 44f, vignette = 12f, vignetteSize = 70f,
            dispersion = 19f, dispersionSize = 57f)
        assertEquals(33f, original.glow, 0f)
        assertEquals(44f, original.glowSize, 0f)
        assertEquals(12f, original.vignette, 0f)
        assertEquals(70f, original.vignetteSize, 0f)
        assertEquals(19f, original.dispersion, 0f)
        assertEquals(57f, original.dispersionSize, 0f)
    }
}
