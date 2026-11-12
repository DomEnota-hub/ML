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

    @Test fun largePersonFallbackUsesSameExportMaskAndLeavesManualRemovalUnchanged() = runBlocking {
        val bitmap = Bitmap.createBitmap(100, 120, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(205, 211, 216))
        val strokes = (25..95 step 2).map { y ->
            MaskStroke(listOf(MaskPoint(.22f, y / 120f), MaskPoint(.78f, y / 120f)), .025f)
        }
        val coverage = LaMaInpaintingEngine.preparedCoverage(strokes, 100, 120).data
        for (i in coverage.indices) if (coverage[i] > 0) bitmap.setPixel(i % 100, i / 100, Color.rgb(35, 40, 45))
        val engine = LaMaInpaintingEngine(InpaintingModelRunner { image, _ ->
            FloatArray(image.size) { 72f }
        })
        val manual = engine.inpaint(bitmap, strokes)
        val recovered = engine.inpaint(bitmap, strokes,
            InpaintingSettings(recoverLargePersonOnUniformBackground = true))
        try {
            assertEquals(Color.rgb(72, 72, 72), manual.getPixel(50, 60))
            assertEquals(Color.rgb(205, 211, 216), recovered.getPixel(50, 60))
            assertEquals(bitmap.getPixel(0, 0), recovered.getPixel(0, 0))
            val overlay = LaMaInpaintingEngine.makeOverlay(recovered, listOf(strokes))
            val exported = bitmap.copy(Bitmap.Config.ARGB_8888, true)
            try {
                Canvas(exported).drawBitmap(overlay, 0f, 0f, null)
                for (y in 0 until 120) for (x in 0 until 100)
                    assertEquals("Export mismatch at $x,$y", recovered.getPixel(x, y), exported.getPixel(x, y))
            } finally { overlay.recycle(); exported.recycle() }
        } finally { manual.recycle(); recovered.recycle(); bitmap.recycle() }
    }

    @Test fun completelyErasedMaskDoesNotCallTheModel() = runBlocking {
        val original = source()
        val edited = LaMaInpaintingEngine(InpaintingModelRunner { _, _ -> error("No selection") })
            .inpaint(original, listOf(stroke, stroke.copy(erase = true)), InpaintingSettings())
        assertEquals(Color.BLUE, edited.getPixel(50, 100))
        original.recycle(); edited.recycle()
    }
    @Test fun bboxCoverageAndCompositeRemainPixelIdenticalToWholeFrame() {
        val sets = listOf(
            listOf(MaskStroke(listOf(MaskPoint(.5f, .5f)), .08f)),
            listOf(MaskStroke(listOf(MaskPoint(.01f, .01f),
                MaskPoint(.99f, .99f)), .012f)),
            listOf(MaskStroke(listOf(MaskPoint(0f, .98f),
                MaskPoint(.22f, 1f)), .16f)),
            listOf(
                MaskStroke(listOf(MaskPoint(.03f, .04f),
                    MaskPoint(.20f, .10f)), .03f),
                MaskStroke(listOf(MaskPoint(.92f, .85f),
                    MaskPoint(.99f, .99f)), .02f),
            ),
            listOf(
                MaskStroke(listOf(MaskPoint(.4f, .4f),
                    MaskPoint(.8f, .8f)), .095f),
                MaskStroke(listOf(MaskPoint(.5f, .5f)), .07f, erase = true),
            ),
            listOf(
                MaskStroke(listOf(MaskPoint(.5f, .5f)), .13f),
                MaskStroke(listOf(MaskPoint(.5f, .5f)), .15f, erase = true),
            ),
        )
        val dimensions = listOf(32 to 32, 128 to 250, 320 to 180, 700 to 510)
        for ((w, h) in dimensions) for ((caseIndex, marks) in sets.withIndex()) {
            val fullMask = LaMaInpaintingEngine.rasterizeMask(marks, w, h)
            val image = IntArray(w * h)
            try {
                fullMask.getPixels(image, 0, w, 0, 0, w, h)
            } finally {
                fullMask.recycle()
            }
            val expected = InpaintingPreparation.coverage(
                IntArray(image.size) { Color.alpha(image[it]) }, w, h)
            val actual = LaMaInpaintingEngine.preparedCoverage(marks, w, h)
            assertArrayEquals("mask case $caseIndex ${w}x$h",
                expected, actual.data)
            val wholeRegion = InpaintingPreparation.region(expected, w, h, 1024)
            val limitedRegion = InpaintingPreparation.region(
                actual.data, w, h, 1024, actual.bounds)
            assertEquals("ROI case $caseIndex ${w}x$h", wholeRegion, limitedRegion)
            if (wholeRegion != null) {
                val plane = 16 * 16
                val prediction = FloatArray(3 * plane) { (it * 17 % 256).toFloat() }
                val source = IntArray(w * h) { i ->
                    0x88000000.toInt() or ((i % 255) shl 16) or (((i / w) % 255) shl 8) or 21
                }
                val legacy = InpaintingPreparation.composite(
                    source, expected, w, h, wholeRegion, prediction, side = 16)
                val cropped = InpaintingPreparation.composite(
                    source, actual.data, w, h, limitedRegion!!, prediction, side = 16,
                    bounds = actual.bounds)
                assertArrayEquals("composite case $caseIndex ${w}x$h",
                    legacy, cropped)
            }
        }
    }

    @Test fun bboxValidationRejectsOutOfFrameAndSupportsEmpty() {
        assertFalse(PixelBounds(-1, 0, 2, 2).validWithin(10, 10))
        assertFalse(PixelBounds(1, 1, 11, 5).validWithin(10, 10))
        assertTrue(PixelBounds(0, 0, 0, 0).validWithin(10, 10))
        val empty = LaMaInpaintingEngine.preparedCoverage(emptyList(), 20, 12)
        assertTrue(empty.data.all { it == 0 })
        assertEquals(PixelBounds(0, 0, 0, 0), empty.bounds)
    }

}
