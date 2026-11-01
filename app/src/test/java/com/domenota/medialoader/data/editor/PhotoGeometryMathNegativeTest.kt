package com.domenota.medialoader.data.editor

import org.junit.Assert.*
import org.junit.Test

class PhotoGeometryMathNegativeTest {
    @Test fun horizontalPerspectiveWorksInBothDirections() {
        val identity = PhotoGeometryMath.destination(PhotoGeometry(), 1000, 1000)
        val plus = PhotoGeometryMath.destination(PhotoGeometry(horizontalPerspective = 50f), 1000, 1000)
        val minus = PhotoGeometryMath.destination(PhotoGeometry(horizontalPerspective = -50f), 1000, 1000)
        assertArrayEquals(floatArrayOf(0f,0f,1000f,0f,1000f,1000f,0f,1000f), identity, .1f)
        assertTrue(plus[0] > 0f && plus[2] < 1000f)
        assertEquals(0f, plus[6], .1f)
        assertTrue(minus[4] < 1000f && minus[6] > 0f)
        assertEquals(0f, minus[0], .1f)
        assertTrue(PhotoGeometryMath.isValidQuad(listOf(
            PhotoGeometryPoint(minus[0]/1000,minus[1]/1000),
            PhotoGeometryPoint(minus[2]/1000,minus[3]/1000),
            PhotoGeometryPoint(minus[4]/1000,minus[5]/1000),
            PhotoGeometryPoint(minus[6]/1000,minus[7]/1000))))
    }

    @Test fun verticalPerspectiveWorksInBothDirections() {
        val plus = PhotoGeometryMath.destination(PhotoGeometry(verticalPerspective = 50f), 1000, 1000)
        val minus = PhotoGeometryMath.destination(PhotoGeometry(verticalPerspective = -50f), 1000, 1000)
        assertTrue(plus[1] > 0f && plus[7] < 1000f)
        assertEquals(0f, plus[3], .1f)
        assertTrue(minus[3] > 0f && minus[5] < 1000f)
        assertEquals(0f, minus[1], .1f)
    }

    @Test fun bothSignsMayBeCombinedWithoutInvalidGeometry() {
        for (h in listOf(-50f, -25f, 0f, 25f, 50f))
            for (v in listOf(-50f, -25f, 0f, 25f, 50f)) {
                val dest = PhotoGeometryMath.destination(PhotoGeometry(
                    horizontalPerspective = h, verticalPerspective = v), 640, 960)
                assertEquals(8, dest.size)
                assertTrue(dest.all { it.isFinite() })
                assertTrue(dest.filterIndexed { i, _ -> i % 2 == 0 }.all { it in 0f..640f })
                assertTrue(dest.filterIndexed { i, _ -> i % 2 == 1 }.all { it in 0f..960f })
            }
    }
}
