package com.domenota.medialoader.ui.screens

import androidx.compose.ui.geometry.Offset
import com.domenota.medialoader.data.editor.PhotoGeometryPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoGeometryInteractionTest {
    private val corners = listOf(
        PhotoGeometryPoint(0f, 0f), PhotoGeometryPoint(1f, 0f),
        PhotoGeometryPoint(1f, 1f), PhotoGeometryPoint(0f, 1f),
    )

    @Test fun freeCornerPickingPrefersNearestCornerWhenTouchZonesOverlap() {
        val frame = PhotoCanvasRect(20f, 10f, 100f, 90f)
        assertEquals(3, geometryCornerHitIndex(corners, frame,
            Offset(23f, 67f), 76f)) // bottom-left wins over top-left
        assertEquals(2, geometryCornerHitIndex(corners, frame,
            Offset(119f, 67f), 76f)) // bottom-right wins over top-right
        assertEquals(0, geometryCornerHitIndex(corners, frame,
            Offset(19f, 9f), 76f))
        assertEquals(-1, geometryCornerHitIndex(corners, frame,
            Offset(400f, 400f), 76f))
    }

    @Test fun geometryPreviewIsBoundedWithoutDistortingImageProportions() {
        assertEquals(960 to 640, geometryPreviewSize(6000, 4000))
        assertEquals(540 to 960, geometryPreviewSize(1800, 3200))
        assertEquals(320 to 240, geometryPreviewSize(320, 240))
        val (width, height) = geometryPreviewSize(16_000, 10)
        assertEquals(960, width)
        assertEquals(1, height)
        assertTrue(width > 0 && height > 0)
    }
}
