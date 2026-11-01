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
class PhotoCreativeMathTest {
    private fun colorfulSource(w: Int = 24, h: Int = 20): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bitmap ->
            for (y in 0 until h) for (x in 0 until w) {
                bitmap.setPixel(x, y, Color.argb(
                    if (x == 0) 0 else 200 + (x % 56),
                    (x * 13 + 65) % 256, (y * 17 + 70) % 256, (x * 7 + y * 5) % 256))
            }
        }

    @Test fun defaultsAreNeutralAndInvalidValuesAreRejected() {
        assertTrue(PhotoColorAdjustments().isNeutral())
        assertThrows(IllegalArgumentException::class.java) {
            PhotoColorAdjustments(posterization = 101f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PhotoColorAdjustments(noiseDegree = Float.NaN)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PhotoColorAdjustments(denoise = -1f)
        }
    }

    @Test fun noCreativeAdjustmentsBorrowSourceWithoutAllocating() {
        val src = colorfulSource()
        try {
            assertSame(src, PhotoCreativeMath.denoise(src, 0f))
            assertSame(src, PhotoCreativeMath.apply(src, PhotoColorAdjustments()))
        } finally { src.recycle() }
    }

    @Test fun eachNewEffectChangesPixelsButPreservesSourceAndTransparency() {
        val settings = listOf(
            PhotoColorAdjustments(posterization = 100f),
            PhotoColorAdjustments(grain = 100f),
            PhotoColorAdjustments(noiseDegree = 100f,
                noiseHighlights = 60f, noiseSize = 70f, noiseRoughness = 90f),
        )
        for (values in settings) {
            val src = colorfulSource()
            val before = src.getPixel(5, 5)
            val result = PhotoCreativeMath.apply(src, values)
            try {
                assertNotSame(src, result)
                assertEquals(src.width, result.width)
                assertEquals(src.height, result.height)
                assertEquals(before, src.getPixel(5, 5))
                assertEquals(0, Color.alpha(result.getPixel(0, 4)))
                assertEquals(Color.alpha(src.getPixel(5, 5)),
                    Color.alpha(result.getPixel(5, 5)))
                val changed = (1 until src.width).any { x ->
                    (1 until src.height).any { y ->
                        src.getPixel(x, y) != result.getPixel(x, y)
                    }
                }
                assertTrue("Creative adjustment should actually change image pixels", changed)
            } finally { result.recycle(); src.recycle() }
        }
    }

    @Test fun creativeNoiseAndGrainAreDeterministicForMatchingInputs() {
        val values = PhotoColorAdjustments(grain = 42f, noiseDegree = 33f,
            noiseHighlights = 25f, noiseSize = 60f, noiseRoughness = 15f)
        val src = colorfulSource()
        val first = PhotoCreativeMath.apply(src, values)
        val second = PhotoCreativeMath.apply(src, values)
        try {
            for (y in 0 until src.height) for (x in 0 until src.width)
                assertEquals(first.getPixel(x, y), second.getPixel(x, y))
        } finally { first.recycle(); second.recycle(); src.recycle() }
    }

    @Test fun denoiseSmoothsLocalizedNoiseWithoutDestroyingSharpEdges() {
        val src = Bitmap.createBitmap(9, 9, Bitmap.Config.ARGB_8888)
        for (y in 0 until 9) for (x in 0 until 9)
            src.setPixel(x, y, Color.rgb(if (x < 5) 125 else 220,
                if (x < 5) 125 else 220, if (x < 5) 125 else 220))
        src.setPixel(2, 2, Color.rgb(159, 159, 159))
        val output = PhotoCreativeMath.denoise(src, 100f)
        try {
            assertTrue(Color.red(output.getPixel(2, 2)) < 159)
            assertTrue(Color.red(output.getPixel(2, 2)) >= 125)
            assertTrue(Color.red(output.getPixel(4, 4)) < 150)
            assertTrue(Color.red(output.getPixel(5, 4)) > 195)
            assertEquals(Color.rgb(159, 159, 159), src.getPixel(2, 2))
        } finally { output.recycle(); src.recycle() }
    }

    @Test fun previewExportPipelineAppliesAndPreservesIndependentSettings() {
        val source = colorfulSource()
        val settings = PhotoColorAdjustments(posterization = 75f, denoise = 35f,
            grain = 55f, noiseDegree = 45f, noiseHighlights = 40f)
        val output = PhotoColorMath.apply(source, settings)
        try {
            assertTrue(output !== source)
            assertEquals(source.width, output.width)
            assertEquals(source.height, output.height)
            assertEquals(Color.alpha(source.getPixel(10, 10)),
                Color.alpha(output.getPixel(10, 10)))
        } finally { output.recycle(); source.recycle() }
    }
}
