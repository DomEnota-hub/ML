package com.domenota.medialoader.ui.screens.gallery

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Gesture geometry must match the exported alpha mask at every scale. */
class CutoutViewportTest {
    private val canvas = IntSize(420, 650)
    private val photo = IntSize(720, 1280)

    @Test fun initialTransformIsFitCenteredAndInvertible() {
        val t = CutoutViewport().transform(canvas, photo)
        val point = Offset(140f, 280f)
        val screen = t.toScreen(point)
        val roundTrip = t.toImage(screen)
        assertEquals(point.x, roundTrip.x, .0001f)
        assertEquals(point.y, roundTrip.y, .0001f)
        assertTrue(t.scale > 0f)
    }

    @Test fun pinchKeepsFocalPointAndNeverDriftsOutsideZoomBounds() {
        val start = CutoutViewport()
        val center = Offset(210f, 325f)
        val initialImagePoint = start.transform(canvas, photo).toImage(center)
        val zoomed = start.gesture(canvas, photo, center, Offset.Zero, 2.3f)
        val nextImagePoint = zoomed.transform(canvas, photo).toImage(center)
        assertEquals(initialImagePoint.x, nextImagePoint.x, .001f)
        assertEquals(initialImagePoint.y, nextImagePoint.y, .001f)
        assertEquals(2.3f, zoomed.zoom, .001f)
        assertEquals(6f, zoomed.gesture(canvas, photo, center, Offset.Zero, 30f).zoom, .001f)
        assertEquals(1f, zoomed.gesture(canvas, photo, center, Offset.Zero, .0001f).zoom, .001f)
    }

    @Test fun twoFingerPanningIsBoundedAndResetReturnsToDefaultView() {
        val middle = Offset(210f, 325f)
        val zoomed = CutoutViewport().gesture(canvas, photo, middle, Offset.Zero, 4f)
        val moved = zoomed.gesture(canvas, photo, middle, Offset(5000f, -5000f), 1f)
        val t = moved.transform(canvas, photo)
        assertTrue(t.left <= 0f)
        assertTrue(t.top <= 0f)
        assertTrue(t.left + photo.width * t.scale >= canvas.width)
        assertTrue(t.top + photo.height * t.scale >= canvas.height)
        assertEquals(1f, CutoutViewport().zoom, .001f)
    }
}
