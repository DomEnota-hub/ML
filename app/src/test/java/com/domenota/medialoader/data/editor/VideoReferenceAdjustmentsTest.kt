package com.domenota.medialoader.data.editor

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VideoReferenceAdjustmentsTest {
    @Test fun referenceControlsAreIndependentAndPreserveLegacyProjectColor() {
        assertEquals(listOf("Выдержка", "Светлота", "Контраст", "Светлые участки", "Тени",
            "Насыщенность", "Красочность", "Теплота", "Оттенок", "Точка черного",
            "Резкость", "Зернистость", "Виньетка"), VideoReferenceAdjustment.entries.map { it.label })
        for (control in VideoReferenceAdjustment.entries) {
            val value = VideoReferenceAdjustments().changed(control, 60f)
            assertEquals(60f, value.value(control), .0001f)
            assertTrue(value.changed(control, 0f).isNeutral)
            assertEquals(0f, value.value(VideoReferenceAdjustment.entries.first { it != control }), .0001f)
        }
        val old = VideoColorGrade(manual = VideoColorAdjustments(red = 1.2f, hue = 20f), photoEffect = PhotoEffect.WARM)
        val updated = old.copy(reference = VideoReferenceAdjustments(exposure = 40f, grain = 50f))
        assertEquals(old.manual, updated.manual)
        assertEquals(old.photoEffect, updated.photoEffect)
        assertFalse(updated.isNeutral)
        assertThrows(IllegalArgumentException::class.java) { VideoReferenceAdjustments(exposure = Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { VideoReferenceAdjustments(grain = -1f) }
    }
    @Test fun cancellingLivePanelRestoresHistoryIncludingThePreviousRedoBranch() {
        val history = VideoEditHistory<String>()
        history.record("original"); history.record("earlier edit")
        assertEquals("original", history.undo())
        val checkpoint = history.checkpoint()
        history.record("cancelled slider 1"); history.record("cancelled slider 2")
        history.restore(checkpoint)
        assertEquals("earlier edit", history.redo())
        assertEquals("original", history.undo())
        assertNull(history.undo())
    }
    @Test fun toneLutActuallyUsesPhotoAlgorithmsAndHasBoundedDimensions() {
        val settings = VideoReferenceAdjustments(exposure = -40f, highlights = -50f, warmth = 30f)
        val lut = VideoReferenceAdjustmentLut.bitmap(settings)
        try {
            assertEquals(33, lut.width)
            assertEquals(1089, lut.height)
            assertTrue(lut.byteCount < 150_000)
            val source = android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
            source.setPixel(0, 0, android.graphics.Color.rgb(128, 64, 191))
            val expected = PhotoColorMath.apply(source, settings.toPhoto())
            try { assertEquals(expected.getPixel(0, 0), lut.getPixel(24, 33 * 16 + 8)) }
            finally { if (expected !== source) expected.recycle(); source.recycle() }
        } finally { lut.recycle() }
    }
}
