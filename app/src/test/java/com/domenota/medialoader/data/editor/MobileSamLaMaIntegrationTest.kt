package com.domenota.medialoader.data.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Integration contract for the REAL MobileSAM mask adapter -> local LaMa pipeline.
 * Fake prediction replaces only downloadable ONNX weights; mask geometry, rasterization,
 * ROI, image composition, undo grouping and export use production implementations.
 * A successful test is NOT proof of model quality on a physical device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MobileSamLaMaIntegrationTest {
    @Test fun dragPromptProducesSelectedIslandOnlyAndPreservesOriginalAndExport() = runBlocking {
        val width = 64
        val height = 64
        val logits = FloatArray(width * height) { -3f }
        // Two disconnected foreground objects. Only the first was touched.
        for (y in 16..32) for (x in 16..32) logits[y * width + x] = 4f
        for (y in 44..56) for (x in 44..56) logits[y * width + x] = 4f
        val prompt = requireNotNull(AutoDragSelectionPolicy.releasePrompt(
            listOf(MaskPoint(.18f, .18f), MaskPoint(.4f, .4f)), 46f, 14f))
        val strokes = MobileSamGeometry.strokesFromLogits(logits, width, height, prompt)
        assertTrue(strokes.isNotEmpty())
        assertTrue(strokes.all { stroke -> stroke.points.all { it.x < .6f && it.y < .6f } })

        val history = MaskActionGrouping()
        history.record(strokes.size)
        assertEquals(strokes.size, history.undoCount())
        assertEquals(strokes.size, history.redoCount())

        val original = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        original.eraseColor(Color.BLUE)
        var inferenceCalled = false
        val runner = InpaintingModelRunner { image, mask ->
            inferenceCalled = true
            assertEquals(3 * 512 * 512, image.size)
            assertTrue(mask.any { it > 0f })
            assertTrue(mask.any { it == 0f })
            FloatArray(image.size).also { prediction ->
                for (i in 0 until 512 * 512) prediction[i] = 255f // red
            }
        }
        val edited = LaMaInpaintingEngine(runner)
            .inpaint(original, strokes, InpaintingSettings())
        try {
            assertTrue(inferenceCalled)
            assertEquals(Color.RED, edited.getPixel(52, 52))
            assertEquals(Color.BLUE, edited.getPixel(100, 100))
            assertEquals(Color.BLUE, edited.getPixel(2, 2))
            assertEquals(Color.BLUE, original.getPixel(52, 52))

            val overlay = LaMaInpaintingEngine.makeOverlay(edited, listOf(strokes))
            try {
                val recomposed = original.copy(Bitmap.Config.ARGB_8888, true)
                try {
                    Canvas(recomposed).drawBitmap(overlay, 0f, 0f, null)
                    for (y in 0 until 128) for (x in 0 until 128) {
                        assertEquals("Pixel mismatch at $x,$y",
                            edited.getPixel(x, y), recomposed.getPixel(x, y))
                    }
                } finally {
                    recomposed.recycle()
                }
            } finally {
                overlay.recycle()
            }
        } finally {
            original.recycle()
            edited.recycle()
        }
    }

    @Test fun unsafeWholeImageMaskMustNeverReachLaMaRunner() = runBlocking {
        val logits = FloatArray(64 * 64) { 2f }
        var called = false
        try {
            val strokes = MobileSamGeometry.strokesFromLogits(
                logits, 64, 64, MaskPoint(.5f, .5f))
            LaMaInpaintingEngine(InpaintingModelRunner { _, _ ->
                called = true
                error("Unsafe mask reached inference")
            }).inpaint(Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888),
                strokes, InpaintingSettings())
            throw AssertionError("Expected fail-closed segmentation")
        } catch (_: java.io.IOException) {
            assertTrue(!called)
        }
    }
}
