package com.domenota.medialoader.data.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** User-visible axis labels must match the coordinate actually deformed. */
class PhotoGeometryCycle2Test {
    @Test fun horizontalControlDeformsYCoordinatesNotX() {
        val corners = PhotoGeometryMath.destination(
            PhotoGeometry(horizontalPerspective = 65f), 1000, 1000)
        assertEquals(0f, corners[0], 0.01f)
        assertEquals(1000f, corners[2], 0.01f)
        assertTrue(corners[1] > 0f)
    }

    @Test fun verticalControlDeformsXCoordinatesNotY() {
        val corners = PhotoGeometryMath.destination(
            PhotoGeometry(verticalPerspective = 65f), 1000, 1000)
        assertEquals(0f, corners[1], 0.01f)
        assertEquals(0f, corners[3], 0.01f)
        assertTrue(corners[0] > 0f)
    }

    @Test fun extremesAreAllowedAndCreateStrongerConvergence() {
        val fifty = PhotoGeometryMath.destination(PhotoGeometry(verticalPerspective = 50f), 1000, 1000)
        val hundred = PhotoGeometryMath.destination(PhotoGeometry(verticalPerspective = 100f), 1000, 1000)
        assertTrue(hundred[0] > fifty[0])
        PhotoGeometry(horizontalPerspective = -100f, verticalPerspective = 100f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOutOfRangePerspective() {
        PhotoGeometry(verticalPerspective = 100.1f)
    }
}
