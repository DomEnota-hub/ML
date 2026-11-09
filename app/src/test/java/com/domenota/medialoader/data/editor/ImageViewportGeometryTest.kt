package com.domenota.medialoader.data.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class ImageViewportGeometryTest {
    @Test fun inverseMappingRespectsZoomAndPan() {
        val viewportWidth = 360f
        val viewportHeight = 640f
        val point = MaskPoint(.23f, .77f)
        for (zoom in listOf(1f, 1.5f, 3f, 5f)) {
            for (panX in listOf(-35f, 0f, 40f)) {
                val viewport = requireNotNull(ImageViewportGeometry.imageToViewport(
                    point, viewportWidth, viewportHeight, zoom, panX, -18f))
                val restored = requireNotNull(ImageViewportGeometry.viewportToImage(
                    viewport.x, viewport.y, viewportWidth, viewportHeight, zoom, panX, -18f))
                assertEquals(point.x, restored.x, .00001f)
                assertEquals(point.y, restored.y, .00001f)
            }
        }
    }

    @Test fun letterboxEdgesRejectTouchesOutsideImage() {
        assertNull(ImageViewportGeometry.viewportToImage(-1f, 40f, 200f, 300f, 1f, 0f, 0f))
        assertNull(ImageViewportGeometry.viewportToImage(201f, 40f, 200f, 300f, 1f, 0f, 0f))
        assertNull(ImageViewportGeometry.viewportToImage(10f, 20f, 200f, 300f, 0f, 0f, 0f))
        assertNull(ImageViewportGeometry.viewportToImage(Float.NaN, 20f, 200f, 300f, 1f, 0f, 0f))
        assertEquals(MaskPoint(0f, 0f),
            ImageViewportGeometry.viewportToImage(0f, 0f, 200f, 300f, 1f, 0f, 0f))
        assertEquals(MaskPoint(1f, 1f),
            ImageViewportGeometry.viewportToImage(200f, 300f, 200f, 300f, 1f, 0f, 0f))
    }

    @Test fun portraitMagnifierStaysLeftAndAwayFromFinger() {
        val nearTop = requireNotNull(ImageViewportGeometry.placeMagnifier(
            PreviewPoint(120f, 40f), 360f, 680f, 152f, 8f, 28f))
        val nearBottom = requireNotNull(ImageViewportGeometry.placeMagnifier(
            PreviewPoint(120f, 640f), 360f, 680f, 152f, 8f, 28f))
        assertEquals(8f, nearTop.left, .01f)
        assertEquals(8f, nearBottom.left, .01f)
        assertTrue(nearTop.top > nearBottom.top)
        assertBounds(nearTop, 360f, 680f)
        assertBounds(nearBottom, 360f, 680f)
    }

    @Test fun landscapeFingerAtLeftFallsBackRightWithoutCoveringTouch() {
        val finger = PreviewPoint(30f, 70f)
        val rect = requireNotNull(ImageViewportGeometry.placeMagnifier(
            finger, 320f, 140f, 152f, 8f, 24f))
        assertTrue(rect.left > 150f)
        assertTrue(distance(finger, rect) >= 24f)
        assertBounds(rect, 320f, 140f)
    }

    @Test fun smallViewportNeverClipsLensOrCoversFinger() {
        for ((width, height) in listOf(320f to 140f, 180f to 120f, 400f to 900f)) {
            val finger = PreviewPoint(width * .5f, height * .5f)
            val rect = ImageViewportGeometry.placeMagnifier(
                finger, width, height, 152f, 8f, 20f)
            if (rect != null) {
                assertBounds(rect, width, height)
                assertTrue(distance(finger, rect) >= 20f)
            }
        }
        assertNull(ImageViewportGeometry.placeMagnifier(
            PreviewPoint(5f, 5f), 12f, 12f, 152f, 8f, 20f))
    }

    @Test fun nonFiniteCoordinatesAreRejected() {
        assertNull(ImageViewportGeometry.placeMagnifier(
            PreviewPoint(Float.NaN, 20f), 360f, 640f, 152f, 8f, 24f))
        assertNull(ImageViewportGeometry.imageToViewport(
            MaskPoint(.5f, .5f), 360f, 640f, Float.POSITIVE_INFINITY, 0f, 0f))
    }

    private fun assertBounds(rect: MagnifierBounds, width: Float, height: Float) {
        assertTrue(rect.left >= 0f)
        assertTrue(rect.top >= 0f)
        assertTrue(rect.right <= width + .001f)
        assertTrue(rect.bottom <= height + .001f)
    }

    private fun distance(p: PreviewPoint, r: MagnifierBounds): Float {
        val dx = maxOf(r.left - p.x, p.x - r.right, 0f)
        val dy = maxOf(r.top - p.y, p.y - r.bottom, 0f)
        return hypot(dx, dy)
    }
}
