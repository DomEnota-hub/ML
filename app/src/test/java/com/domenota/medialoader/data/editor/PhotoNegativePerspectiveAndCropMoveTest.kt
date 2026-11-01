package com.domenota.medialoader.data.editor

import org.junit.Assert.*
import org.junit.Test

class PhotoNegativePerspectiveAndCropMoveTest {
    @Test fun negativeYawReversesNearAndFarSidesWithoutBecomingIdentity() {
        val negative = PhotoGeometryMath.destination(
            PhotoGeometry(horizontalPerspective = -50f), 1000, 600)
        val positive = PhotoGeometryMath.destination(
            PhotoGeometry(horizontalPerspective = 50f), 1000, 600)
        assertFalse(negative.contentEquals(positive))
        val leftHeight = negative[7] - negative[1]
        val rightHeight = negative[5] - negative[3]
        assertTrue("Negative yaw brings the left edge closer", leftHeight > rightHeight)
        assertTrue("Positive yaw brings the right edge closer",
            positive[5] - positive[3] > positive[7] - positive[1])
    }

    @Test fun negativePitchReversesNearAndFarHorizontalEdges() {
        val negative = PhotoGeometryMath.destination(
            PhotoGeometry(verticalPerspective = -50f), 1000, 600)
        val positive = PhotoGeometryMath.destination(
            PhotoGeometry(verticalPerspective = 50f), 1000, 600)
        assertFalse(negative.contentEquals(positive))
        assertTrue("Negative pitch enlarges bottom",
            negative[4] - negative[6] > negative[2] - negative[0])
        assertTrue("Positive pitch enlarges top",
            positive[2] - positive[0] > positive[4] - positive[6])
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
