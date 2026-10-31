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
class PhotoAdvancedColorTest {
    @Test fun defaultCurvesAreIdentityAndTheNeutralColorProfileDoesNotAllocate() {
        val curves = PhotoCurves()
        assertTrue(curves.isNeutral())
        val lut = PhotoCurveMath.lut(curves.master)
        (0..255).forEach { assertEquals(it, lut[it]) }
        val source = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        try {
            assertSame(source, PhotoColorMath.apply(source, PhotoColorAdjustments()))
            assertSame(source, PhotoDetailMath.apply(source, 0f, 0f))
        } finally { source.recycle() }
    }

    @Test fun curvesAdjustMasterAndSelectedColorChannelIndependently() {
        val redCurve = PhotoCurve().insert(.5f, .8f)
        val all = PhotoCurves().update(PhotoCurveChannel.RED, redCurve)
        val lookup = PhotoCurveMath.prepare(all)
        assertTrue(lookup.mapRed(128) > 128)
        assertEquals(128, lookup.mapGreen(128))
        assertEquals(128, lookup.mapBlue(128))
        val master = PhotoCurve().insert(.5f, .3f)
        val allWithMaster = all.update(PhotoCurveChannel.RGB, master)
        assertTrue(PhotoCurveMath.prepare(allWithMaster).mapGreen(128) < 128)
    }

    @Test fun draggablePointsStayOrderedAndEndpointsStayFixedOnXAxis() {
        var curve = PhotoCurve().insert(.4f, .45f).insert(.7f, .75f)
        curve = curve.withPoint(1, 1f, .3f)
        assertTrue(curve.points[1].x < curve.points[2].x)
        curve = curve.withPoint(0, .5f, .22f)
        assertEquals(0f, curve.points[0].x, 0f)
        curve = curve.withPoint(curve.points.lastIndex, .2f, .89f)
        assertEquals(1f, curve.points.last().x, 0f)
        curve = curve.remove(1)
        assertEquals(3, curve.points.size)
        assertEquals(curve, curve.remove(0))
    }

    @Test fun monotoneCurveNeverOvershootsAndPreservesEndpoints() {
        val curve = PhotoCurve(listOf(PhotoCurvePoint(0f, 0f),
            PhotoCurvePoint(.25f, .1f), PhotoCurvePoint(.5f, .8f),
            PhotoCurvePoint(1f, 1f)))
        val lut = PhotoCurveMath.lut(curve)
        assertEquals(0, lut[0])
        assertEquals(255, lut[255])
        assertTrue((1 until lut.size).all { i -> lut[i] >= lut[i - 1] })
        assertTrue(lut.all { it in 0..255 })
    }

    @Test fun vibranceRetainsNeutralGrayButSaturatesMutedColors() {
        val bright = PhotoColorAdjustments(vibrance = 85f)
        val neutral = PhotoToneMath.tonePixel(Color.rgb(130, 130, 130), bright)
        assertEquals(Color.rgb(130, 130, 130), neutral)
        val muted = Color.rgb(170, 130, 110)
        val processed = PhotoToneMath.tonePixel(muted, bright)
        assertTrue(Color.red(processed) - Color.blue(processed) >
            Color.red(muted) - Color.blue(muted))
    }

    @Test fun tintedMatrixMovesGreenAndMagentaSeparatelyFromWarmth() {
        val original = PhotoColorMath.matrix(PhotoColorAdjustments()).array
        val warm = PhotoColorMath.matrix(PhotoColorAdjustments(warmth = 60f)).array
        val magenta = PhotoColorMath.matrix(PhotoColorAdjustments(tint = 60f)).array
        assertEquals(original[6], warm[6], .0001f)
        assertTrue(magenta[6] < original[6])
        assertTrue(magenta[0] > original[0])
    }

    @Test fun curvesPreserveAlphaAndKeepUnmodifiedInputPixelIntact() {
        val red = PhotoCurve().insert(.5f, .8f)
        val profile = PhotoColorAdjustments(rgbCurves = PhotoCurves(red = red))
        val pixel = Color.argb(132, 128, 128, 128)
        val result = PhotoToneMath.tonePixel(pixel, profile)
        assertEquals(132, Color.alpha(result))
        assertEquals(128, Color.green(result))
        assertTrue(Color.red(result) > Color.green(result))
        assertEquals(128, Color.red(pixel))
    }

    @Test fun sharpnessAndClarityOperateWithoutMutatingSourceOrChangingAlpha() {
        val source = Bitmap.createBitmap(18, 18, Bitmap.Config.ARGB_8888)
        try {
            source.eraseColor(Color.argb(156, 100, 110, 120))
            source.setPixel(9, 9, Color.argb(156, 230, 210, 190))
            val reference = source.getPixel(9, 9)
            val output = PhotoDetailMath.apply(source, 70f, 55f)
            try {
                assertNotSame(source, output)
                assertEquals(18, output.width)
                assertEquals(18, output.height)
                assertEquals(reference, source.getPixel(9, 9))
                assertEquals(156, Color.alpha(output.getPixel(9, 9)))
            } finally { output.recycle() }
        } finally { source.recycle() }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectInvalidCurvePoint() { PhotoCurvePoint(Float.NaN, .2f) }

    @Test(expected = IllegalArgumentException::class)
    fun rejectInvalidColorStrength() { PhotoColorAdjustments(clarity = 110f) }
}
