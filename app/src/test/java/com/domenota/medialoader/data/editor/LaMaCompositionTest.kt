package com.domenota.medialoader.data.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LaMaCompositionTest {
    private val stroke = MaskStroke(listOf(MaskPoint(0.5f, 0.5f)), 0.1f)
    private fun source() = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }

    @Test fun localInferencePreservesOutsidePixelsAndExportMatchesFeatheredPreview() = runBlocking {
        val original = source()
        val runner = InpaintingModelRunner { image, mask ->
            assertEquals(3 * 512 * 512, image.size)
            assertTrue(mask.count { it > 0 } in 1 until 512 * 512 / 4)
            FloatArray(image.size).also { for (i in 0 until 512 * 512) it[i] = 255f }
        }
        val edited = LaMaInpaintingEngine(runner).inpaint(original, listOf(stroke), InpaintingSettings())
        assertEquals(Color.RED, edited.getPixel(50, 100))
        assertEquals(Color.BLUE, edited.getPixel(0, 0))
        assertEquals(Color.BLUE, original.getPixel(50, 100))
        val overlay = LaMaInpaintingEngine.makeOverlay(edited, listOf(listOf(stroke)))
        val exported = original.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(exported).drawBitmap(overlay, 0f, 0f, null)
        for (y in 0 until 200) for (x in 0 until 100) {
            assertEquals("Export mismatch at $x,$y", edited.getPixel(x, y), exported.getPixel(x, y))
        }
        assertEquals(0, Color.alpha(overlay.getPixel(0, 0)))
        original.recycle(); edited.recycle(); overlay.recycle(); exported.recycle()
    }

    @Test fun completelyErasedMaskDoesNotCallTheModel() = runBlocking {
        val original = source()
        val edited = LaMaInpaintingEngine(InpaintingModelRunner { _, _ -> error("No selection") })
            .inpaint(original, listOf(stroke, stroke.copy(erase = true)), InpaintingSettings())
        assertEquals(Color.BLUE, edited.getPixel(50, 100))
        original.recycle(); edited.recycle()
    } 
    @Test fun croppedMaskIsBitExactToLegacyFullFrameForEdgesEraserAndDisjointMarks() {
        val cases = listOf(
            listOf(MaskStroke(listOf(MaskPoint(0f, 0f), MaskPoint(.18f, .13f)), .09f)),
            listOf(MaskStroke(listOf(MaskPoint(1f, 1f)), .2f)),
            listOf(
                MaskStroke(listOf(MaskPoint(.02f, .94f), MaskPoint(.13f, .97f)), .06f),
                MaskStroke(listOf(MaskPoint(.9f, .05f), MaskPoint(.98f, .15f)), .07f)
            ),
            listOf(
                MaskStroke(listOf(MaskPoint(.15f, .2f), MaskPoint(.75f, .79f)), .11f),
                MaskStroke(listOf(MaskPoint(.5f, .5f), MaskPoint(.55f, .55f)), .055f, erase = true)
            )
        )
        for ((width, height) in listOf(72 to 124, 300 to 180)) {
            for ((index, strokes) in cases.withIndex()) {
                val originalMask = LaMaInpaintingEngine.rasterizeMask(strokes, width, height)
                try {
                    val pixels = IntArray(width * height)
                    originalMask.getPixels(pixels, 0, width, 0, 0, width, height)
                    val legacy = InpaintingPreparation.coverage(
                        IntArray(pixels.size) { Color.alpha(pixels[it]) }, width, height)
                    val bounded = LaMaInpaintingEngine.preparedCoverage(strokes, width, height)
                    assertArrayEquals("Coverage differs, case $index / ${width}x$height",
                        legacy, bounded.data)
                    assertEquals(
                        InpaintingPreparation.region(legacy, width, height, 1024),
                        InpaintingPreparation.region(bounded.data, width, height, 1024, bounded.bounds))
                } finally {
                    originalMask.recycle()
                }
            }
        }
    }
}
