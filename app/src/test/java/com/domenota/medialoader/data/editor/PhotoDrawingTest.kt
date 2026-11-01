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

    @Test fun brushSliderRangeCannotProduceInvalidPhotoMark() {
        assertTrue(PhotoDrawing.MIN_BRUSH_THICKNESS >= PhotoDrawing.MIN_MARK_THICKNESS)
        assertTrue(PhotoDrawing.MAX_BRUSH_THICKNESS <= PhotoDrawing.MAX_MARK_THICKNESS)
        val points = listOf(PhotoPoint(.15f, .2f), PhotoPoint(.85f, .8f))
        for (kind in PhotoMarkKind.entries) {
            for (thickness in listOf(
                PhotoDrawing.MIN_BRUSH_THICKNESS, .008f, .025f,
                PhotoDrawing.MAX_BRUSH_THICKNESS,
            )) {
                val original = PhotoMark(kind, points, thickness = thickness)
                val edited = original.copy(thickness = PhotoDrawing.MAX_BRUSH_THICKNESS)
                assertEquals(thickness, original.thickness, 0f)
                assertEquals(PhotoDrawing.MAX_BRUSH_THICKNESS, edited.thickness, 0f)
            }
        }
        // The previous UI mistakenly used .002f..05f (upper bound 5.0).
        val badThickness = runCatching {
            PhotoMark(PhotoMarkKind.NEON, points, thickness = 5f)
        }.exceptionOrNull()
        assertTrue(badThickness is IllegalArgumentException)
    }

    @Test fun everyDrawingKindRendersAtBothSliderLimits() {
        val source = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.WHITE)
        val points = listOf(PhotoPoint(.15f, .2f), PhotoPoint(.85f, .8f))
        val marks = PhotoMarkKind.entries.flatMap { kind ->
            listOf(PhotoDrawing.MIN_BRUSH_THICKNESS, PhotoDrawing.MAX_BRUSH_THICKNESS)
                .map { width -> PhotoMark(kind, points, Color.BLUE, width) }
        }
        val output = PhotoDrawing.overlay(source, marks)
        try {
            assertEquals(160, output.width)
            assertEquals(120, output.height)
            assertEquals(Color.WHITE, source.getPixel(0, 0))
        } finally {
            output.recycle()
            source.recycle()
        }
    }
}
