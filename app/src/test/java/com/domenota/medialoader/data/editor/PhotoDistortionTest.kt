package com.domenota.medialoader.data.editor

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoDistortionTest {
    private fun coordinate(amount: Float, width: Int=1000, height: Int=500) = PhotoDistortionMath.vertices(width,height,amount,4)
    @Test fun zeroIsIdentityAndCenterStaysFixedAtBothExtremes() {
        for (n in listOf(-100f,0f,100f)) {
            val p=coordinate(n)
            assertEquals(500f,p[24],.001f);assertEquals(250f,p[25],.001f)
        }
        val zero=coordinate(0f)
        assertEquals(0f,zero[0],.001f);assertEquals(1000f,zero[8],.001f)
        assertTrue(PhotoGeometry(distortion=0f).isNeutral())
        assertFalse(PhotoGeometry(distortion=1f).isNeutral())
        assertThrows(IllegalArgumentException::class.java) { PhotoGeometry(distortion=101f) }
    }
    @Test fun positiveMagnifiesCenterAndNegativeContractsItAsInReference() {
        // Row 2, column 3: point at x=.75, y=.5.
        assertTrue(coordinate(100f)[26] > 750f)
        assertTrue(coordinate(-100f)[26] < 750f)
        val a=coordinate(74f);val b=coordinate(74f,2000,1000)
        for (i in a.indices) assertEquals(a[i]*2f,b[i],.001f)
    }
    @Test fun meshDoesNotFoldAtEitherExtreme() {
        val grid=PhotoDistortionMath.GRID
        for (n in listOf(-100f,-50f,0f,50f,100f)) {
            val p=PhotoDistortionMath.vertices(4000,3000,n)
            assertTrue(p.all { it.isFinite() })
            for (row in 0 until grid) for (col in 0 until grid) {
                val a=(row*(grid+1)+col)*2; val b=a+2;val c=a+(grid+1)*2
                val cross=(p[b]-p[a])*(p[c+1]-p[a+1])-(p[b+1]-p[a+1])*(p[c]-p[a])
                assertTrue("Folded mesh at $n/$row/$col",cross>0f)
            }
        }
    }
    @Test fun canvasRendersWarpWithoutChangingSourceAndKeepsOutputDimensions() {
        val source=Bitmap.createBitmap(128,96,Bitmap.Config.ARGB_8888)
        for(y in 0 until 96) for(x in 0 until 128) source.setPixel(x,y,Color.rgb(x*2,y*2,100))
        val pixel=source.getPixel(90,48)
        val output=PhotoGeometryMath.apply(source,PhotoGeometry(distortion=85f))
        try {
            assertNotSame(source,output);assertEquals(128,output.width);assertEquals(96,output.height)
            assertEquals(pixel,source.getPixel(90,48));assertNotEquals(pixel,output.getPixel(90,48))
            assertEquals(255,Color.alpha(output.getPixel(64,48)))
        } finally { output.recycle();source.recycle() }
    }
}
