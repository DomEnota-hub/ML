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
class PhotoDrawingTest {
    @Test fun normalizedCoordinatesStayOnTheSourceImage() {
        assertEquals(PhotoPoint(0f, 1f), PhotoDrawing.clamp(-14f, 9f))
        val marks = listOf(PhotoMark(PhotoMarkKind.LINE,
            listOf(PhotoPoint(.25f, .25f), PhotoPoint(.75f, .75f))))
        assertEquals(listOf(PhotoPoint(.25f, .25f), PhotoPoint(.75f, .75f)),
            marks[0].points)
    }

    @Test fun drawingExportLeavesOriginalBitmapUnchanged() {
        val source = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.WHITE)
        val overlay = PhotoDrawing.overlay(source, listOf(PhotoMark(
            PhotoMarkKind.RECTANGLE,
            listOf(PhotoPoint(.2f, .2f), PhotoPoint(.8f, .8f)),
            Color.RED, .04f,
        )))
        try {
            assertTrue(overlay !== source)
            assertEquals(Color.WHITE, source.getPixel(30, 30))
            assertEquals(source.width, overlay.width)
            assertEquals(source.height, overlay.height)
        } finally {
            overlay.recycle()
            source.recycle()
        }
    }

    @Test fun allShapeKindsCanBeRendered() {
        val source = Bitmap.createBitmap(100, 70, Bitmap.Config.ARGB_8888)
        val kinds = PhotoMarkKind.entries.map { kind ->
            PhotoMark(kind, listOf(PhotoPoint(.1f, .15f), PhotoPoint(.85f, .8f)), Color.BLUE)
        }
        val result = PhotoDrawing.overlay(source, kinds)
        try {
            assertEquals(100, result.width)
            assertEquals(70, result.height)
        } finally {
            result.recycle()
            source.recycle()
        }
    }
}
