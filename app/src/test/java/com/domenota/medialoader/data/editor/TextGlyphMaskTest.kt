package com.domenota.medialoader.data.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextGlyphMaskTest {
    private val a = DetectedGlyph(0, "A", 0.1f, 0.2f, 0.2f, 0.4f)
    private val b = DetectedGlyph(1, "B", 0.3f, 0.2f, 0.4f, 0.4f)

    @Test fun individualSymbolsCanBeSelectedWithoutWholeWord() {
        assertTrue(a.contains(0.15f, 0.25f))
        assertFalse(b.contains(0.15f, 0.25f))
        assertTrue(a.crosses(0.0f, 0.3f, 0.25f, 0.3f))
        assertFalse(b.crosses(0.0f, 0.3f, 0.25f, 0.3f))
        assertFalse(a.crosses(0.0f, 0.55f, 0.4f, 0.55f))
    }

    @Test fun onlySelectedGlyphProducesMask() {
        val masks = TextGlyphMask.toStrokes(listOf(a), 1000, 800)
        assertTrue(masks.isNotEmpty())
        assertTrue(masks.all { it.points.all { p -> p.x < 0.24f } })
        assertEquals(emptyList<MaskStroke>(), TextGlyphMask.toStrokes(emptyList(), 1000, 800))
    }

    @Test fun tallAndWideImagesKeepGlyphCoordinatesNormalized() {
        for ((w, h) in listOf(1920 to 1080, 720 to 1280)) {
            val strokes = TextGlyphMask.toStrokes(listOf(b), w, h)
            assertTrue(strokes.isNotEmpty())
            assertTrue(strokes.all { it.diameter > 0f && it.diameter <= 0.5f })
            assertTrue(strokes.all { line ->
                line.points.all { p -> p.x in 0f..1f && p.y in 0f..1f }
            })
        }
    }
}
