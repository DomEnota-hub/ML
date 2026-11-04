package com.domenota.medialoader.data.editor

import android.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SharedVideoFilterCatalogTest {
    @Test fun videoAndPhotoReadTheExactSameCatalogAndCategories() {
        PhotoEffectCategory.entries.forEach { category ->
            val photo = PhotoEffectCatalog.forCategory(category)
            val video = SharedVideoFilterCatalog.forCategory(category)
            assertEquals("Diverging filter category: ${category.name}", photo, video)
        }
        assertEquals(PhotoEffect.entries,
            SharedVideoFilterCatalog.forCategory(PhotoEffectCategory.ALL))
    }

    @Test fun everyPhotoFilterResolvesToFiniteMedia3Parameters() {
        PhotoEffect.entries.forEach { effect ->
            val clipGrade = VideoColorGrade(photoEffect = effect)
            val grade = clipGrade.resolved()
            assertEquals(effect.name, grade, grade)
            assertTrue(grade.brightness.isFinite())
            assertTrue(grade.saturation.isFinite())
            assertTrue(grade.contrast.isFinite())
            assertTrue(grade.red.isFinite() && grade.blue.isFinite())
        }
    }

    @Test fun selectionKeepsIndependentManualSettingsAndCanResetToOriginal() {
        val manual = VideoColorAdjustments(brightness = .2f)
        val chosen = VideoColorGrade(photoEffect = PhotoEffect.MONO, manual = manual)
        assertEquals(-100f, chosen.resolved().saturation, .001f)
        assertEquals(.2f, chosen.resolved().brightness, .001f)
        val reset = chosen.copy(photoEffect = PhotoEffect.NONE)
        assertEquals(.2f, reset.resolved().brightness, .001f)
    }

    @Test fun legacyVideoFilterProjectIsStillReadableWithoutPhotoEffect() {
        val legacy = VideoColorGrade(preset = VideoFilterPreset.CINEMATIC)
        assertEquals(null, legacy.photoEffect)
        assertFalse(legacy.isNeutral)
    }

    @Test fun filmGrainOverlayIsNonemptyAndDeterministic() {
        val a = VideoPhotoGrainOverlay.makePixels(96, 64, 45f, 1f)
        val b = VideoPhotoGrainOverlay.makePixels(96, 64, 45f, 1f)
        val clear = VideoPhotoGrainOverlay.makePixels(96, 64, 45f, 0f)
        assertArrayEquals(a, b)
        assertTrue(a.any { Color.alpha(it) > 0 })
        assertTrue(clear.all { Color.alpha(it) == 0 })
    }
}
