package com.domenota.medialoader.data.editor

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GlyphContentMaskTest {
    @Test fun onlyGlyphInkNotItsBoundingRectangleIsPassedToLaMa() {
        val bitmap = Bitmap.createBitmap(100, 70, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        try {
            for (y in 21..40) for (x in 37..47) {
                bitmap.setPixel(x, y, if (x in 40..44 && y in 24..37)
                    Color.YELLOW else Color.BLACK)
            }
            val glyph = DetectedGlyph(0, "Ж", .32f, .24f, .54f, .66f)
            val mask = GlyphContentMask.fromBitmap(bitmap, listOf(glyph))
            assertTrue("Visible ink must yield real strokes", mask.isNotEmpty())
            assertTrue("No rectangle-shaped mask or distant background", mask.all { stroke ->
                stroke.points.all { point ->
                    point.x in .35f..49f && point.y in .27f..60f
                }
            })
            assertTrue(mask.any { stroke ->
                stroke.points.any { it.x in .40f..45f && it.y in .33f..53f }
            })
        } finally { bitmap.recycle() }
    }

    @Test fun paleOutlineOnSmoothNoisyFloorProducesRealInkWithoutSelectingBackground() {
        val bitmap = Bitmap.createBitmap(120, 90, Bitmap.Config.ARGB_8888)
        try {
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                val v = 208 + (x * 3 + y * 7) % 5
                bitmap.setPixel(x, y, Color.rgb(v, v, v))
            }
            // Thin grey outline, less than the old fixed 43-level threshold.
            for (y in 31..52) for (x in 47..60) {
                if (x == 47 || x == 60 || y == 31 || y == 52)
                    bitmap.setPixel(x, y, Color.rgb(184, 184, 184))
            }
            val glyph = DetectedGlyph(0, "O", 43f/120, 27f/90, 64f/120, 57f/90, 91f)
            val strokes = GlyphContentMask.fromBitmap(bitmap, listOf(glyph))
            assertTrue(strokes.isNotEmpty())
            val points = strokes.flatMap { it.points }
            assertTrue(points.all { it.x in 46f/120..62f/120 && it.y in 30f/90..54f/90 })
            assertTrue(points.none { it.x in 50f/120..57f/120 && it.y in 34f/90..49f/90 })
        } finally { bitmap.recycle() }
    }

    @Test fun blankPhotoNeverProducesASyntheticRectangularMask() {
        val bitmap = Bitmap.createBitmap(100, 70, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        try {
            val glyph = DetectedGlyph(0, "X", .30f, .20f, .55f, .65f)
            assertEquals(emptyList<MaskStroke>(),
                GlyphContentMask.fromBitmap(bitmap, listOf(glyph)))
        } finally { bitmap.recycle() }
    }

    @Test fun texturedBackgroundIsNotGuessedToBeText() {
        val bitmap = Bitmap.createBitmap(100, 70, Bitmap.Config.ARGB_8888)
        try {
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                bitmap.setPixel(x, y,
                    if ((x + y) % 2 == 0) Color.BLACK else Color.WHITE)
            }
            val glyph = DetectedGlyph(0, "X", .30f, .20f, .55f, .65f)
            assertEquals(emptyList<MaskStroke>(),
                GlyphContentMask.fromBitmap(bitmap, listOf(glyph)))
        } finally { bitmap.recycle() }
    }

    @Test fun selectedGlyphsAreAllOrNothingWhenAnyCandidateIsUnsafe() {
        val bitmap = Bitmap.createBitmap(100, 70, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        try {
            for (y in 20..30) for (x in 14..20) bitmap.setPixel(x, y, Color.BLACK)
            val valid = DetectedGlyph(0, "A", .10f, .20f, .26f, .50f)
            val blank = DetectedGlyph(1, "B", .65f, .20f, .85f, .50f)
            assertTrue(GlyphContentMask.fromBitmap(bitmap, listOf(valid)).isNotEmpty())
            assertEquals(emptyList<MaskStroke>(),
                GlyphContentMask.fromBitmap(bitmap, listOf(valid, blank)))
        } finally { bitmap.recycle() }
    }
}
