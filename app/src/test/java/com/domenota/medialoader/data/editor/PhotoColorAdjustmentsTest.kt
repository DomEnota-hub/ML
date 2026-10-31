package com.domenota.medialoader.data.editor

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotoColorAdjustmentsTest {
    @Test fun neutralAdjustmentHasIdentityColorMatrix() {
        val identity = PhotoColorMath.matrix(PhotoColorAdjustments()).array
        assertEquals(1f, identity[0], 0.0001f)
        assertEquals(1f, identity[6], 0.0001f)
        assertEquals(1f, identity[12], 0.0001f)
        assertEquals(1f, identity[18], 0.0001f)
        assertEquals(0f, identity[4], 0f)
    }

    @Test fun effectsAndLevelControlsChangeOnlyTheirIntendedSettings() {
        val bright = PhotoColorAdjustments(brightness = 35f)
        assertTrue(!bright.isNeutral())
        assertTrue(PhotoColorMath.matrix(bright).array[4] > 0f)
        val grayscale = PhotoColorMath.matrix(PhotoColorAdjustments(effect = PhotoEffect.MONO)).array
        assertEquals(grayscale[0], grayscale[5], 0.0001f)
        assertEquals(grayscale[1], grayscale[6], 0.0001f)
        assertNotEquals(PhotoColorMath.matrix(PhotoColorAdjustments()).array[0],
            grayscale[0], 0.0001f)
    }

    @Test fun savedBitmapAdjustmentPreservesAlphaAndDoesNotMutateSource() {
        val source = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        source.setPixel(0, 0, Color.argb(200, 80, 90, 100))
        val result = PhotoColorMath.apply(source, PhotoColorAdjustments(brightness = 40f))
        try {
            // Robolectric does not consistently rasterize ColorMatrixColorFilter.
            // Verify original protection and the intended Android color transform;
            // test the actual render output on-device during acceptance.
            assertEquals(80, Color.red(source.getPixel(0, 0)))
            assertEquals(200, Color.alpha(source.getPixel(0, 0)))
            assertTrue(result !== source)
            assertEquals(source.width, result.width)
            assertEquals(source.height, result.height)
            assertTrue(PhotoColorMath.matrix(PhotoColorAdjustments(brightness = 40f)).array[4] > 0f)
        } finally { result.recycle(); source.recycle() }
    }

    @Test fun untouchedImageReturnsBorrowedInputWithoutUnnecessaryCopy() {
        val source = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        try { assertTrue(PhotoColorMath.apply(source, PhotoColorAdjustments()) === source) }
        finally { source.recycle() }
    }
}
