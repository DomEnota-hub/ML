package com.domenota.medialoader.data.editor

import org.junit.Assert.*
import org.junit.Test

class PreviewZoomTest {
    @Test fun zoomKeepsPointUnderFingersAndInverseMappingAgrees() {
        val zoom = PreviewZoom().transform(2f, 0f, 0f, 150f, 80f, 400f, 200f)
        val point = zoom.toContent(150f, 80f, 400f, 200f)
        assertEquals(150f, point.first, 0.001f)
        assertEquals(80f, point.second, 0.001f)
    }
    @Test fun panIsBoundedAndReturningToOneResetsOffsets() {
        val zoom = PreviewZoom().transform(2f, 500f, -500f, 200f, 100f, 400f, 200f)
        assertEquals(200f, zoom.x, 0f)
        assertEquals(-100f, zoom.y, 0f)
        assertEquals(PreviewZoom(), zoom.transform(0.5f, 0f, 0f, 200f, 100f, 400f, 200f))
    }
    @Test fun colorPickerTargetsSameSourcePixelAfterZoomAndPan() {
        val zoom = PreviewZoom().transform(3f, 30f, -10f, 200f, 100f, 400f, 200f)
        val point = zoom.toContent(230f, 90f, 400f, 200f)
        assertEquals(ImagePixel(50, 25), ImageColorSample.pixel(100, 50, 400f, 200f, point.first, point.second))
    }
    @Test fun scaleLimitsAndInvalidViewportDoNotProduceInvalidTransform() {
        assertEquals(6f, PreviewZoom().transform(100f, 0f, 0f, 200f, 100f, 400f, 200f).scale, 0f)
        assertEquals(PreviewZoom(), PreviewZoom().transform(2f, 0f, 0f, 0f, 0f, 0f, 0f))
    }
    @Test fun portraitPanCannotMoveImageCompletelyOutsideViewport() {
        val zoom = PreviewZoom().transform(6f, 1000f, 0f, 200f, 100f, 400f, 200f, 100, 200)
        assertEquals(100f, zoom.x, 0f)
        val small = PreviewZoom().transform(2f, 1000f, 0f, 200f, 100f, 400f, 200f, 100, 200)
        assertEquals(0f, small.x, 0f)
    }
    @Test fun selectionHandleStaysInVisibleContentWindowAfterZoomAndPan() {
        val zoom = PreviewZoom(scale = 2f, x = 100f)
        val handle = zoom.handle(200f, 100f, 360f, 150f, 400f, 200f, 7f, 19f)
        assertTrue(handle.first in 57f..243f)
        assertTrue(handle.second in 57f..143f)
    }
}
