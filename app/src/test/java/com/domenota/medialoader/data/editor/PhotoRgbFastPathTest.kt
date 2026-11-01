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
class PhotoRgbFastPathTest {
    @Test fun rgbOnlyFastPathMatchesOriginalTonePixelAndPreservesAlpha() {
        val source = Bitmap.createBitmap(32, 12, Bitmap.Config.ARGB_8888)
        try {
            for (y in 0 until source.height) for (x in 0 until source.width) {
                source.setPixel(x, y, Color.argb((x * 7 + y * 13) % 256,
                    x * 8 % 256, y * 21 % 256, (x * 17 + y * 9) % 256))
            }
            val curves = PhotoCurves(
                master = PhotoCurve(listOf(PhotoCurvePoint(0f, 0f),
                    PhotoCurvePoint(.42f, .68f), PhotoCurvePoint(1f, 1f))),
                red = PhotoCurve(listOf(PhotoCurvePoint(0f, .04f),
                    PhotoCurvePoint(.55f, .8f), PhotoCurvePoint(1f, 1f))),
                blue = PhotoCurve(listOf(PhotoCurvePoint(0f, 0f),
                    PhotoCurvePoint(.35f, .2f), PhotoCurvePoint(1f, .96f)))
            )
            val values = PhotoColorAdjustments(rgbCurves = curves)
            val original = source.getPixel(13, 8)
            val output = PhotoColorMath.apply(source, values)
            try {
                assertNotSame(source, output)
                val prepared = PhotoToneMath.prepare(values)
                for (y in 0 until source.height) for (x in 0 until source.width) {
                    val expected = PhotoToneMath.tonePixel(source.getPixel(x, y), prepared, FloatArray(3))
                    assertEquals("mismatch at x=$x y=$y", expected, output.getPixel(x, y))
                }
                assertEquals(original, source.getPixel(13, 8))
            } finally { output.recycle() }
        } finally { source.recycle() }
    }

    @Test fun neutralRgbStillReturnsOriginalBitmapWithoutAllocation() {
        val source = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        try { assertSame(source, PhotoColorMath.apply(source,
            PhotoColorAdjustments(rgbCurves = PhotoCurves()))) }
        finally { source.recycle() }
    }
}
