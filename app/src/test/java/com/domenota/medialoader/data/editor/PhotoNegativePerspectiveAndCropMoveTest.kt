package com.domenota.medialoader.data.editor

import org.junit.Assert.*
import org.junit.Test

class PhotoNegativePerspectiveAndCropMoveTest {
    @Test fun negativeHorizontalPerspectiveMustNotCollapseToIdentity() {
        val neutral = PhotoGeometryMath.destination(PhotoGeometry(), 1000, 600)
        val negative = PhotoGeometryMath.destination(
            PhotoGeometry(horizontalPerspective = -50f), 1000, 600)
        val positive = PhotoGeometryMath.destination(
            PhotoGeometry(horizontalPerspective = 50f), 1000, 600)
        assertFalse(neutral.contentEquals(negative))
        assertFalse(negative.contentEquals(positive))
        assertEquals(0f, negative[0], 0f)
        assertTrue(negative[4] < 1000f) // bottom-right inset
        assertTrue(negative[6] > 0f) // bottom-left inset
        assertTrue(positive[0] > 0f) // positive top-left inset
        assertTrue(positive[2] < 1000f) // positive top-right inset
    }

    @Test fun negativeVerticalPerspectiveMustNotCollapseToIdentity() {
        val neutral = PhotoGeometryMath.destination(PhotoGeometry(), 1000, 600)
        val negative = PhotoGeometryMath.destination(
            PhotoGeometry(verticalPerspective = -50f), 1000, 600)
        val positive = PhotoGeometryMath.destination(
            PhotoGeometry(verticalPerspective = 50f), 1000, 600)
        assertFalse(neutral.contentEquals(negative))
        assertFalse(negative.contentEquals(positive))
        assertTrue(negative[3] > 0f) // right upper corner
        assertTrue(negative[5] < 600f) // right lower corner
        assertTrue(positive[1] > 0f)
        assertTrue(positive[7] < 600f)
    }

    @Test fun draggingCropInteriorPreservesWidthHeightAndAspect() {
        val original = CropFractions(left = .2f, right = .3f, top = .1f, bottom = .4f)
        val result = VideoCropMath.moveCrop(original, .15f, -.07f)
        assertEquals(original.keptWidth, result.keptWidth, .000001f)
        assertEquals(original.keptHeight, result.keptHeight, .000001f)
        assertEquals(.35f, result.left, .000001f)
        assertEquals(.15f, result.right, .000001f)
        assertEquals(.03f, result.top, .000001f)
        assertEquals(.47f, result.bottom, .000001f)
    }

    @Test fun movingCropAtImageBoundaryIsClamped() {
        val original = CropFractions(left = .2f, right = .3f, top = .1f, bottom = .4f)
        val shifted = VideoCropMath.moveCrop(original, 50f, -50f)
        assertEquals(0f, shifted.right, .000001f)
        assertEquals(0f, shifted.top, .000001f)
        assertEquals(original.keptWidth, shifted.keptWidth, .000001f)
        assertEquals(original.keptHeight, shifted.keptHeight, .000001f)
    }
}
