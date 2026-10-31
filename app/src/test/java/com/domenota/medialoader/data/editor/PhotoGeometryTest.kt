package com.domenota.medialoader.data.editor

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotoGeometryTest {
    @Test fun defaultGeometryIsIdentityAndNeverCopiesTheSource() {
        val b = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888)
        try {
            assertTrue(PhotoGeometry().isNeutral())
            assertTrue(PhotoGeometryMath.isValidQuad(PhotoGeometry.identityCorners()))
            assertSame(b, PhotoGeometryMath.apply(b, PhotoGeometry()))
            val xy = PhotoGeometryMath.destination(PhotoGeometry(), 40, 20)
            assertArrayEquals(floatArrayOf(0f, 0f, 40f, 0f, 40f, 20f, 0f, 20f), xy, .00001f)
        } finally { b.recycle() }
    }

    @Test fun perspectiveAndHorizonStayResolutionIndependent() {
        val geometry = PhotoGeometry(straighten = 7.5f, horizontalPerspective = 22f,
            verticalPerspective = -15f)
        val a = PhotoGeometryMath.destination(geometry, 100, 50)
        val b = PhotoGeometryMath.destination(geometry, 500, 250)
        for (i in a.indices) assertEquals(a[i] * 5f, b[i], .01f)
        assertTrue(PhotoGeometryMath.transformMatrix(geometry, 100, 50).isFinite)
    }

    @Test fun movingCornerChangesOnlyOneCornerAndDoesNotAlterOriginal() {
        val g = PhotoGeometry()
        val moved = PhotoGeometryMath.moveCorner(g, 0, PhotoGeometryPoint(.1f, .13f))
        assertFalse(moved.isNeutral())
        assertEquals(PhotoGeometryPoint(0f, 0f), g.corners.first())
        assertEquals(PhotoGeometryPoint(.1f, .13f), moved.corners.first())
        assertEquals(g.corners.drop(1), moved.corners.drop(1))
    }

    @Test fun rejectSelfIntersectingAndCollapsedQuads() {
        val original = PhotoGeometry()
        val crossed = listOf(PhotoGeometryPoint(0f, 0f), PhotoGeometryPoint(1f, 1f),
            PhotoGeometryPoint(1f, 0f), PhotoGeometryPoint(0f, 1f))
        assertFalse(PhotoGeometryMath.isValidQuad(crossed))
        assertEquals(original, PhotoGeometryMath.moveCorner(original, 0,
            PhotoGeometryPoint(.98f, .99f)))
    }

    @Test fun nonNeutralGeometryDoesNotOverwriteOriginalAndPreservesDimensions() {
        val src = Bitmap.createBitmap(96, 48, Bitmap.Config.ARGB_8888)
        try {
            src.eraseColor(Color.rgb(40, 80, 120))
            val expected = src.getPixel(45, 24)
            val out = PhotoGeometryMath.apply(src, PhotoGeometry(straighten = 12f))
            try {
                assertNotSame(src, out)
                assertEquals(src.width, out.width)
                assertEquals(src.height, out.height)
                assertEquals(expected, src.getPixel(45, 24))
            } finally { out.recycle() }
        } finally { src.recycle() }
    }

    @Test fun numericalBoundsAreStrict() {
        assertThrows(IllegalArgumentException::class.java) {
            PhotoGeometry(straighten = 50f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PhotoGeometry(horizontalPerspective = Float.NaN)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PhotoGeometryPoint(-.01f, .5f)
        }
    }
}
