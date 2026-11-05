package com.domenota.medialoader.ui.screens.gallery

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Tests the safe Android 16 routing and preprocessing without downloading model weights. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class U2NetPhotoCutoutTest {
    @Test fun android16NeverInvokesCrashPronePlayServicesSegmenter() {
        assertFalse(U2NetPhotoCutout.usesOnnx(33))
        assertFalse(U2NetPhotoCutout.usesOnnx(34))
        assertFalse(U2NetPhotoCutout.usesOnnx(35))
        assertTrue(U2NetPhotoCutout.usesOnnx(36))
        assertTrue(U2NetPhotoCutout.usesOnnx(37))
    }

    @Test fun alphaNormalizationHandlesDegenerateAndNonFiniteMasks() {
        assertEquals(0, U2NetPhotoCutout.alphaForValue(0f, 0f, 1f))
        assertEquals(127, U2NetPhotoCutout.alphaForValue(.5f, 0f, 1f))
        assertEquals(255, U2NetPhotoCutout.alphaForValue(1f, 0f, 1f))
        assertEquals(0, U2NetPhotoCutout.alphaForValue(Float.NaN, 0f, 1f))
        assertEquals(0, U2NetPhotoCutout.alphaForValue(.5f, 1f, 1f))
    }

    @Test fun inputTensorUsesThreeNormalizedRgbPlanes() {
        val image = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        try {
            image.eraseColor(Color.rgb(255, 0, 0))
            val tensor = U2NetPhotoCutout.inputTensor(image)
            val area = U2NetPhotoCutout.INPUT_SIZE * U2NetPhotoCutout.INPUT_SIZE
            assertEquals(3 * area, tensor.size)
            assertEquals((1f - .485f) / .229f, tensor[0], .0001f)
            assertEquals((0f - .456f) / .224f, tensor[area], .0001f)
            assertEquals((0f - .406f) / .225f, tensor[2 * area], .0001f)
        } finally {
            image.recycle()
        }
    }

    @Test fun outputKeepsOriginalRgbAndUsesSoftAlpha() {
        val n = U2NetPhotoCutout.INPUT_SIZE
        val source = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val mask = FloatArray(n * n) { i -> if (i % n < n / 2) 0f else 1f }
        source.eraseColor(Color.rgb(255, 60, 10))
        try {
            val cut = U2NetPhotoCutout.composeForeground(source, mask)
            try {
                assertEquals(0, Color.alpha(cut.getPixel(10, 10)))
                assertEquals(255, Color.alpha(cut.getPixel(n - 10, 10)))
                assertEquals(255, Color.red(cut.getPixel(n - 10, 10)))
                assertEquals(60, Color.green(cut.getPixel(n - 10, 10)))
                assertEquals(10, Color.blue(cut.getPixel(n - 10, 10)))
            } finally {
                cut.recycle()
            }
        } finally {
            source.recycle()
        }
    }
}
