package com.domenota.medialoader.data.editor

import android.graphics.Bitmap
import android.graphics.Color
import java.io.IOException
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
class ReflectionRemovalEngineTest {
    private fun blue(w: Int = 128, h: Int = 128) =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLUE)
        }

    private val imageSize = ReflectionRemovalEngine.PIXELS

    private fun trainedLikeTransmission(input: FloatArray): FloatArray =
        input.copyOf().apply {
            // Real production code consumes an actual neural output, not a
            // rectangular painted mask. Synthetic tensors only test its math.
            for (i in 0 until imageSize) {
                this[i] = 1f   // red
                this[2 * imageSize + i] = 0f // no blue
            }
        }

    @Test fun fullFrameNeuralTransmissionChangesRgbWithoutDestroyingSource() = runBlocking {
        val source = blue()
        var called = 0
        val result = ReflectionRemovalEngine(ReflectionModelRunner { image ->
            called++
            assertEquals(3 * imageSize, image.size)
            assertEquals(0f, image[0], 0.001f)
            assertEquals(1f, image[2 * imageSize], 0.001f)
            trainedLikeTransmission(image)
        }).remove(source, strength = 1f)
        try {
            assertEquals(1, called)
            assertEquals(Color.RED, result.getPixel(64, 64))
            assertEquals(Color.BLUE, source.getPixel(64, 64))
        } finally {
            result.recycle()
            source.recycle()
        }
    }

    @Test fun defaultStrengthIsGentlerThanMaximum() = runBlocking {
        val source = blue()
        val runner = ReflectionModelRunner { input -> trainedLikeTransmission(input) }
        val middle = ReflectionRemovalEngine(runner).remove(source, strength = .5f)
        val maximum = ReflectionRemovalEngine(runner).remove(source, strength = 1f)
        try {
            val m = middle.getPixel(64, 64)
            val x = maximum.getPixel(64, 64)
            assertTrue(Color.red(m) < Color.red(x))
            assertTrue(Color.blue(m) > Color.blue(x))
            assertEquals(Color.BLUE, source.getPixel(64, 64))
        } finally {
            middle.recycle()
            maximum.recycle()
            source.recycle()
        }
    }

    @Test fun portraitLetterboxAndLandscapePreserveOutputDimensions() = runBlocking {
        val runner = ReflectionModelRunner { image -> trainedLikeTransmission(image) }
        for ((w, h) in listOf(64 to 128, 128 to 64, 240 to 480)) {
            val source = blue(w, h)
            val corrected = ReflectionRemovalEngine(runner).remove(source)
            try {
                assertEquals(w, corrected.width)
                assertEquals(h, corrected.height)
                assertEquals(Color.BLUE, source.getPixel(w / 2, h / 2))
                assertTrue(Color.red(corrected.getPixel(w / 2, h / 2)) > 0)
            } finally {
                corrected.recycle()
                source.recycle()
            }
        }
    }

    @Test fun identityOrMalformedTensorMustNeverBeCalledSuccessfulRemoval() = runBlocking {
        val source = blue()
        try {
            for (runner in listOf(
                ReflectionModelRunner { it.copyOf() },
                ReflectionModelRunner { FloatArray(12) },
                ReflectionModelRunner { FloatArray(3 * imageSize) { Float.NaN } },
            )) {
                try {
                    ReflectionRemovalEngine(runner).remove(source)
                    fail("Expected invalid neural result to be rejected")
                } catch (_: IOException) { /* required */ }
            }
        } finally {
            source.recycle()
        }
    }

    @Test fun impossibleStrengthIsRejectedBeforeInference() = runBlocking {
        val source = blue()
        var called = false
        try {
            ReflectionRemovalEngine(ReflectionModelRunner {
                called = true
                trainedLikeTransmission(it)
            }).remove(source, Float.NaN)
            fail("Should reject nonfinite strength")
        } catch (_: IllegalArgumentException) {
            assertFalse(called)
        } finally {
            source.recycle()
        }
    }
}
