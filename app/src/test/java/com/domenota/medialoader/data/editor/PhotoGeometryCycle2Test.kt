package com.domenota.medialoader.data.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Physical left/right yaw and up/down pitch instead of corner-only skew. */
class PhotoGeometryCycle2Test {
    @Test fun horizontalControlRotatesAroundUprightAxis() {
        val quad = PhotoGeometryMath.destination(
            PhotoGeometry(horizontalPerspective = 65f), 1000, 1000)
        val leftHeight = quad[7] - quad[1]
        val rightHeight = quad[5] - quad[3]
        assertTrue("Right edge should appear nearer on positive yaw", rightHeight > leftHeight)
        assertEquals(quad[0], quad[6], .01f) // vertical left edge
        assertEquals(quad[2], quad[4], .01f) // vertical right edge
    }

    @Test fun verticalControlRotatesAroundHorizontalAxis() {
        val quad = PhotoGeometryMath.destination(
            PhotoGeometry(verticalPerspective = -65f), 1000, 1000)
        val topWidth = quad[2] - quad[0]
        val bottomWidth = quad[4] - quad[6]
        assertTrue("Reference: negative vertical brings the top edge nearer", topWidth > bottomWidth)
        assertEquals(quad[1], quad[3], .01f) // horizontal top edge
        assertEquals(quad[5], quad[7], .01f) // horizontal bottom edge
    }

    @Test fun extremaStayValidAndTurnStrongerThanMidrange() {
        val fifty = PhotoGeometryMath.destination(
            PhotoGeometry(horizontalPerspective = 50f), 1000, 1000)
        val hundred = PhotoGeometryMath.destination(
            PhotoGeometry(horizontalPerspective = 100f), 1000, 1000)
        val contrast50 = (fifty[5] - fifty[3]) - (fifty[7] - fifty[1])
        val contrast100 = (hundred[5] - hundred[3]) - (hundred[7] - hundred[1])
        assertTrue(contrast100 > contrast50)
        for (h in listOf(-100f, -50f, 0f, 50f, 100f)) {
            for (v in listOf(-100f, -50f, 0f, 50f, 100f)) {
                val quad = PhotoGeometryMath.destination(PhotoGeometry(
                    horizontalPerspective = h, verticalPerspective = v), 1000, 1000)
                assertTrue(quad.all { it.isFinite() })
                for (i in 0..3) {
                    val a=i*2; val b=((i+1)%4)*2; val c=((i+2)%4)*2
                    val cross=(quad[b]-quad[a])*(quad[c+1]-quad[b+1]) -
                        (quad[b+1]-quad[a+1])*(quad[c]-quad[b])
                    assertTrue("Folded plane: $h / $v", cross > 0f)
                }
            }
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOutOfRangePerspective() { PhotoGeometry(verticalPerspective = 100.1f) }
    @Test fun nearEdgeGrowsBeyondOriginalFrameInsteadOfFittingBothAxesBack() {
        val q = PhotoGeometryMath.destination(PhotoGeometry(horizontalPerspective=65f), 1000, 1000)
        assertTrue(q[3] < 0f)
        assertTrue(q[5] > 1000f)
        assertTrue(q[7]-q[1] < 1000f)
    }
}
