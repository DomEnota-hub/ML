package com.domenota.medialoader.data.editor

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotoStageFiveEffectsTest {
    @Test fun catalogContainsAllPresetsAndUsefulCategories() {
        val all = PhotoEffectCatalog.forCategory(PhotoEffectCategory.ALL)
        assertEquals(PhotoEffect.entries, all)
        assertTrue(all.size >= 18)
        assertTrue(PhotoEffectCatalog.forCategory(PhotoEffectCategory.FILM)
            .contains(PhotoEffect.FILM_FADED))
        assertTrue(PhotoEffectCatalog.forCategory(PhotoEffectCategory.POPULAR)
            .contains(PhotoEffect.VIVID))
        assertTrue(PhotoEffectCatalog.forCategory(PhotoEffectCategory.NIGHT)
            .contains(PhotoEffect.NIGHT_CYAN))
        assertTrue(PhotoEffectCatalog.forCategory(PhotoEffectCategory.TEXTURE)
            .contains(PhotoEffect.TEXTURE_GRAIN))
        assertTrue(PhotoEffectCategory.entries.filter {
            it != PhotoEffectCategory.FAVORITES
        }.all { PhotoEffectCatalog.forCategory(it).isNotEmpty() })
        assertTrue(PhotoEffectCatalog.forCategory(PhotoEffectCategory.FAVORITES).isEmpty())
        assertEquals(listOf(PhotoEffect.WARM),
            PhotoEffectCatalog.forCategory(PhotoEffectCategory.FAVORITES,
                setOf(PhotoEffect.WARM)))
    }

    @Test fun legacyPresetNamesRetainMeaningfulSaturationAndWarmth() {
        val neutral = PhotoColorMath.matrix(PhotoColorAdjustments()).array
        val mono = PhotoColorMath.matrix(PhotoColorAdjustments(effect = PhotoEffect.MONO)).array
        assertEquals(mono[0], mono[5], .0001f)
        assertNotEquals(neutral[0], mono[0], .0001f)
        val warm = PhotoColorMath.matrix(PhotoColorAdjustments(effect = PhotoEffect.WARM)).array
        assertTrue(warm[0] > neutral[0])
        assertTrue(warm[12] < neutral[12])
    }

    @Test fun zeroStrengthMakesExistingPresetsMatrixNeutral() {
        val original = PhotoColorMath.matrix(PhotoColorAdjustments()).array
        for (preset in PhotoEffect.entries) {
            val off = PhotoColorAdjustments(effect = preset, effectStrength = 0f)
            assertArrayEquals(preset.label, original, PhotoColorMath.matrix(off).array, .00001f)
            assertFalse(PhotoStylizeMath.needsWork(off))
        }
    }

    @Test fun spatialAdjustmentsCanBeUsedIndependentlyAndNeverModifyOriginal() {
        val input = Bitmap.createBitmap(96, 72, Bitmap.Config.ARGB_8888)
        try {
            input.eraseColor(Color.argb(140, 40, 70, 110))
            for (y in 0 until input.height) for (x in 0 until input.width) {
                if (x % 9 == 0 || y % 11 == 0) input.setPixel(x, y,
                    Color.argb(140, 190, 215, 230))
            }
            val oldPixel = input.getPixel(30, 23)
            for (v in listOf(PhotoColorAdjustments(vignette = 90f),
                PhotoColorAdjustments(glow = 100f),
                PhotoColorAdjustments(dispersion = 90f),
                PhotoColorAdjustments(effect = PhotoEffect.FILM_FADED))) {
                val out = PhotoStylizeMath.apply(input, v)
                try {
                    assertNotSame(input, out)
                    assertEquals(input.width, out.width)
                    assertEquals(input.height, out.height)
                    assertEquals(oldPixel, input.getPixel(30, 23))
                    assertEquals(140, Color.alpha(out.getPixel(30, 23)))
                } finally { out.recycle() }
            }
        } finally { input.recycle() }
    }

    @Test fun allControlsWithZeroIntensityReturnOriginalWithoutCopy() {
        val image = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        try {
            val settings = PhotoColorAdjustments()
            assertFalse(PhotoStylizeMath.needsWork(settings))
            assertSame(image, PhotoStylizeMath.apply(image, settings))
            assertSame(image, PhotoColorMath.apply(image, settings))
        } finally { image.recycle() }
    }

    @Test fun intensityAndSizeAreValidatedToPreventUnboundedWork() {
        assertThrows(IllegalArgumentException::class.java) {
            PhotoColorAdjustments(effectStrength = -1f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PhotoColorAdjustments(glowSize = 150f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PhotoColorAdjustments(dispersion = Float.NaN)
        }
    }

    @Test fun presetsRemainIndependentOfManualPhotoAdjustments() {
        val settings = PhotoColorAdjustments(brightness = 17f, curve = 18f,
            effect = PhotoEffect.WARM, effectStrength = 42f)
        val swapped = settings.copy(effect = PhotoEffect.NIGHT_CYAN)
        assertEquals(17f, swapped.brightness, 0f)
        assertEquals(18f, swapped.curve, 0f)
        assertEquals(42f, swapped.effectStrength, 0f)
    }
}
