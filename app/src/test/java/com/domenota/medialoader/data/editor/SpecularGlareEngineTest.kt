package com.domenota.medialoader.data.editor

import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SpecularGlareEngineTest {
    private val pixels = SpecularGlareEngine.PIXELS

    private fun image(width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLUE)
        }

    private fun fakePrediction(mask: FloatArray, diffuseBlue: Float): SpecularPrediction =
        SpecularPrediction(mask, FloatArray(pixels * 3) { index ->
            when {
                index < pixels -> 1f // R
                index < pixels * 2 -> 0f // G
                else -> diffuseBlue // B
            }
        })

    @Test fun portraitAndLandscapePreserveAspectAndPadding() {
        assertEquals(SpecularFrame(128, 256, 64, 0), SpecularFrame.forImage(500, 1000))
        assertEquals(SpecularFrame(256, 128, 0, 64), SpecularFrame.forImage(1000, 500))
        assertEquals(SpecularFrame(256, 256, 0, 0), SpecularFrame.forImage(1000, 1000))
        assertTrue(SpecularFrame.forImage(3000, 1).height >= 1)
        assertTrue(SpecularFrame.forImage(1, 3000).width >= 1)
    }

    @Test fun realProductionCompositionChangesOnlyPredictedHighlightPixels() = runBlocking {
        val original = image(128, 128)
        val mask = FloatArray(pixels)
        for (y in 100 until 156) for (x in 100 until 156)
            mask[y * 256 + x] = 1f
        var called = 0
        val runner = SpecularModelRunner { image ->
            called++
            assertEquals(3 * pixels, image.size)
            assertEquals(0f, image[0], .0001f) // Blue input has no red
            assertEquals(1f, image[2 * pixels], .0001f)
            fakePrediction(mask, .0f)
        }
        val result = SpecularGlareEngine(runner).correct(original)
        try {
            assertEquals(1, called)
            assertEquals(Color.BLUE, result.getPixel(4, 4))
            assertEquals(Color.BLUE, result.getPixel(122, 122))
            assertEquals(Color.RED, result.getPixel(64, 64))
            assertEquals(Color.BLUE, original.getPixel(64, 64))
            assertEquals(original.width, result.width)
            assertEquals(original.height, result.height)
        } finally {
            result.recycle()
            original.recycle()
        }
    }

    @Test fun letterboxedPortraitStillMapsPredictedCenterToSourceCenter() = runBlocking {
        val original = image(64, 128)
        val mask = FloatArray(pixels)
        for (y in 102 until 154) for (x in 102 until 154)
            mask[y * 256 + x] = 1f
        val output = SpecularGlareEngine(SpecularModelRunner { fakePrediction(mask, 0f) })
            .correct(original)
        try {
            assertEquals(Color.RED, output.getPixel(32, 64))
            assertEquals(Color.BLUE, output.getPixel(1, 1))
        } finally {
            output.recycle()
            original.recycle()
        }
    }

    @Test fun missingGlareFailsClosedAndDoesNotAlterOriginal() = runBlocking {
        val original = image(64, 64)
        val runner = SpecularModelRunner { fakePrediction(FloatArray(pixels), 0f) }
        try {
            SpecularGlareEngine(runner).correct(original)
            fail("Empty predicted highlight must not count as a successful edit")
        } catch (_: IOException) {
            assertEquals(Color.BLUE, original.getPixel(32, 32))
        } finally {
            original.recycle()
        }
    }

    @Test fun tooBroadMaskAndNonfiniteOutputAreRejected() = runBlocking {
        val original = image(64, 64)
        for (mask in listOf(
            FloatArray(pixels) { 1f },
            FloatArray(pixels) { if (it == pixels / 2) Float.NaN else 0f }
        )) {
            try {
                SpecularGlareEngine(SpecularModelRunner { fakePrediction(mask, 0f) })
                    .correct(original)
                fail("Unsafe SRNet mask must fail closed")
            } catch (_: IOException) { /* required */ }
        }
        original.recycle()
    }

    @Test fun dimensionsAndNonfiniteColorsAreRejectedBeforePainting() = runBlocking {
        val original = image(64, 64)
        try {
            SpecularGlareEngine(SpecularModelRunner {
                SpecularPrediction(FloatArray(12) { 1f }, FloatArray(3 * pixels))
            }).correct(original)
            fail("Wrong ONNX output shape accepted")
        } catch (_: IllegalArgumentException) { /* required */ }
        val mask = FloatArray(pixels)
        for (y in 100..145) for (x in 100..145) mask[y * 256 + x] = 1f
        try {
            SpecularGlareEngine(SpecularModelRunner {
                fakePrediction(mask, 0f).also { it.diffuse[100] = Float.NaN }
            }).correct(original)
            fail("Nonfinite RGB accepted")
        } catch (_: IOException) { /* required */ }
        original.recycle()
    }
}
