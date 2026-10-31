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
class PhotoLocalEffectsTest {
    private fun stroke(kind: PhotoLocalEffectKind,
        selection: PhotoLocalSelection = PhotoLocalSelection.RECTANGLE) =
        PhotoLocalStroke(kind, selection,
            listOf(PhotoPoint(.2f, .2f), PhotoPoint(.8f, .8f)),
            brushFraction = .09f, intensity = .06f)

    @Test fun geometryUsesNormalizedCoordinatesAtAnyResolution() {
        val rectangle = stroke(PhotoLocalEffectKind.SQUARE)
        val p1 = PhotoLocalEffects.maskPath(rectangle, 100, 200)
        val p2 = PhotoLocalEffects.maskPath(rectangle, 500, 1000)
        val b1 = android.graphics.RectF()
        val b2 = android.graphics.RectF()
        p1.computeBounds(b1, true)
        p2.computeBounds(b2, true)
        assertEquals(20f, b1.left, .01f)
        assertEquals(40f, b1.top, .01f)
        assertEquals(b1.left * 5f, b2.left, .01f)
        assertEquals(b1.bottom * 5f, b2.bottom, .01f)
    }

    @Test fun brushAndOvalProduceNonEmptyRegions() {
        val brush = PhotoLocalStroke(PhotoLocalEffectKind.SQUARE, PhotoLocalSelection.BRUSH,
            listOf(PhotoPoint(.5f, .5f)))
        val oval = stroke(PhotoLocalEffectKind.BLUR, PhotoLocalSelection.OVAL)
        val rect1 = android.graphics.RectF()
        PhotoLocalEffects.maskPath(brush, 120, 80).computeBounds(rect1, true)
        val rect2 = android.graphics.RectF()
        PhotoLocalEffects.maskPath(oval, 120, 80).computeBounds(rect2, true)
        assertTrue(rect1.width() > 0f && rect1.height() > 0f)
        assertTrue(rect2.width() > rect1.width())
    }

    @Test fun everyEffectLeavesOriginalBitmapUntouched() {
        val source = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888)
        try {
            for (y in 0 until source.height) for (x in 0 until source.width)
                source.setPixel(x, y, Color.rgb(x, y, (x + y) % 256))
            val before = source.getPixel(80, 60)
            val outside = source.getPixel(2, 2)
            PhotoLocalEffectKind.entries.forEach { kind ->
                val result = PhotoLocalEffects.apply(source, listOf(stroke(kind)))
                try {
                    assertNotSame(source, result)
                    assertEquals(source.width, result.width)
                    assertEquals(source.height, result.height)
                    assertEquals(before, source.getPixel(80, 60))
                    assertEquals(outside, source.getPixel(2, 2))
                } finally { result.recycle() }
            }
        } finally { source.recycle() }
    }

    @Test fun overlappingEffectsRemainOrderedAndCapped() {
        val marks = listOf(
            stroke(PhotoLocalEffectKind.SQUARE),
            stroke(PhotoLocalEffectKind.STAGGERED),
            stroke(PhotoLocalEffectKind.BLUR)
        )
        assertEquals(PhotoLocalEffectKind.SQUARE, marks.first().kind)
        assertEquals(PhotoLocalEffectKind.BLUR, marks.last().kind)
        assertTrue(marks.size <= PhotoLocalEffects.MAX_STROKES)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidOutOfBoundsStrokeIsRejected() {
        PhotoLocalStroke(PhotoLocalEffectKind.SQUARE, PhotoLocalSelection.BRUSH,
            listOf(PhotoPoint(1.1f, .5f)))
    }
}
