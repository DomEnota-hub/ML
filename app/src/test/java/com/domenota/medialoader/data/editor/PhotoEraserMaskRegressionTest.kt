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
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoEraserMaskRegressionTest {
    @Test fun clearsOnlyTheSweptCircleAndPreservesLaterMarks() {
        val source = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.WHITE)
        val line = PhotoMark(PhotoMarkKind.PEN, listOf(PhotoPoint(.1f,.5f),PhotoPoint(.9f,.5f)),
            Color.RED,.10f)
        val erase = PhotoMark(PhotoMarkKind.ERASER, listOf(PhotoPoint(.5f,.35f),PhotoPoint(.5f,.65f)),
            thickness=.15f)
        val output = PhotoDrawing.overlay(source,listOf(line,erase))
        try {
            assertEquals(Color.WHITE,output.getPixel(200,100))
            assertEquals(Color.RED,output.getPixel(175,100))
            assertEquals(Color.RED,output.getPixel(225,100))
            assertEquals(Color.WHITE,source.getPixel(175,100))
            val later = PhotoDrawing.overlay(source,listOf(line,erase,line.copy(color=Color.BLUE)))
            try { assertEquals(Color.BLUE,later.getPixel(200,100)) } finally { later.recycle() }
        } finally { output.recycle();source.recycle() }
    }
    @Test fun maximumEraserRemainsThreeTimesBrushAndClearsExactDiameter() {
        val source=Bitmap.createBitmap(400,200,Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.WHITE)
        val points=listOf(PhotoPoint(.1f,.5f),PhotoPoint(.9f,.5f))
        val line=PhotoMark(PhotoMarkKind.PEN,points,Color.RED,.10f)
        val erase=PhotoMark(PhotoMarkKind.ERASER,listOf(PhotoPoint(.5f,.5f)),
            thickness=PhotoDrawing.MAX_ERASER_THICKNESS)
        val result=PhotoDrawing.overlay(source,listOf(line,erase))
        try {
            assertEquals(.30f,PhotoDrawing.MAX_ERASER_THICKNESS,.00001f)
            assertEquals(3f*PhotoDrawing.MAX_BRUSH_THICKNESS,erase.thickness,.00001f)
            assertEquals(Color.WHITE,result.getPixel(200,100))
            assertEquals(Color.WHITE,result.getPixel(175,100))
            assertEquals(Color.WHITE,result.getPixel(225,100))
            assertEquals(Color.RED,result.getPixel(165,100))
            assertEquals(Color.RED,result.getPixel(235,100))
        } finally {result.recycle();source.recycle()}
    }
    @Test fun widthRangeDoubledAndStillValid() {
        assertTrue(PhotoDrawing.MAX_BRUSH_THICKNESS >= .10f)
        assertTrue(PhotoDrawing.MAX_BRUSH_THICKNESS <= PhotoDrawing.MAX_MARK_THICKNESS)
    }
}
