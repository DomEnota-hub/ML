package com.domenota.medialoader.data.editor

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Integrated photo editing path smoke test: geometry, creative tones, vectors.
 * Actual preview/export visual correspondence still needs on-device QA.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotoEditorIntegratedRegressionTest {
    @Test fun combinedGeometryColorAndDrawingPreserveOriginalAndOutputSize() {
        val source = Bitmap.createBitmap(80, 64, Bitmap.Config.ARGB_8888)
        for (y in 0 until source.height) for (x in 0 until source.width) {
            source.setPixel(x, y, Color.argb(255, (x * 3 + 30) % 256,
                (y * 4 + 40) % 256, (x + y + 20) % 256))
        }
        val untouched = source.getPixel(20, 20)
        val geometry = PhotoGeometry(straighten = 7f,
            horizontalPerspective = 14f, verticalPerspective = -8f)
        val levels = PhotoColorAdjustments(brightness = 13f, posterization = 30f,
            grain = 12f, denoise = 25f, noiseDegree = 15f,
            noiseHighlights = 25f, noiseSize = 25f, noiseRoughness = 50f,
            glow = 10f, vignette = 17f)
        val marks = PhotoMarkKind.entries.map { kind ->
            PhotoMark(kind, listOf(PhotoPoint(.12f, .15f), PhotoPoint(.85f, .77f)),
                thickness = PhotoDrawing.MAX_BRUSH_THICKNESS)
        }
        var transformed: Bitmap? = null
        var adjusted: Bitmap? = null
        var composite: Bitmap? = null
        try {
            transformed = PhotoGeometryMath.apply(source, geometry)
            adjusted = PhotoColorMath.apply(transformed, levels)
            composite = PhotoDrawing.overlay(adjusted, marks)
            assertEquals(80, composite.width)
            assertEquals(64, composite.height)
            assertEquals(untouched, source.getPixel(20, 20))
            assertEquals(255, Color.alpha(source.getPixel(20, 20)))
            assertNotSame(source, transformed)
            assertNotSame(adjusted, composite)
            assertEquals(PhotoMarkKind.entries.size, marks.size)
        } finally {
            if (composite != null && !composite.isRecycled) composite.recycle()
            if (adjusted != null && adjusted !== composite && !adjusted.isRecycled)
                adjusted.recycle()
            if (transformed != null && transformed !== adjusted && !transformed.isRecycled)
                transformed.recycle()
            source.recycle()
        }
    }
}
