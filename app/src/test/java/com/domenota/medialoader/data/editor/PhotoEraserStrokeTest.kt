package com.domenota.medialoader.data.editor

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class PhotoEraserStrokeTest {
    private val line = PhotoMark(
        PhotoMarkKind.PEN, listOf(PhotoPoint(.1f, .5f), PhotoPoint(.9f, .5f)),
        color = Color.RED, thickness = .06f)
    private val erase = PhotoMark(
        PhotoMarkKind.ERASER, listOf(PhotoPoint(.5f, .35f), PhotoPoint(.5f, .65f)),
        thickness = .07f)

    /** Native/Robolectric rasterizers may differ by a few antialiased channel values.
     * Verify an actual opaque red stroke rather than demanding exact 0xFFFF0000.
     */
    private fun assertStrokeRed(location: String, pixel: Int) {
        assertTrue("$location: expected red stroke, got 0x${Integer.toHexString(pixel)}",
            Color.alpha(pixel) >= 245 && Color.red(pixel) >= 220 &&
                Color.green(pixel) <= 35 && Color.blue(pixel) <= 35)
    }

    @Test fun eraserCutsOnlyTouchedPartOfStrokeAndNeverThePhoto() {
        val source = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.WHITE)
        val withoutErase = PhotoDrawing.overlay(source, listOf(line))
        val erased = PhotoDrawing.overlay(source, listOf(line, erase))
        try {
            assertStrokeRed("center before erase", withoutErase.getPixel(100, 100))
            assertEquals(Color.WHITE, erased.getPixel(100, 100))
            assertStrokeRed("untouched left segment", erased.getPixel(40, 100))
            assertEquals(Color.WHITE, source.getPixel(100, 100))
            assertEquals(Color.WHITE, erased.getPixel(100, 30))
        } finally {
            erased.recycle()
            withoutErase.recycle()
            source.recycle()
        }
    }

    @Test fun eraserStrokeIsUndoableAndSkipsSelection() {
        val source = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.WHITE)
        val without = listOf(line)
        val with = without + erase
        assertEquals(0, PhotoMarkEdits.pick(with, PhotoPoint(.2f, .5f)))
        assertEquals(-1, PhotoMarkEdits.pick(listOf(erase), PhotoPoint(.5f, .5f)))
        val altered = PhotoDrawing.overlay(source, with)
        val restored = PhotoDrawing.overlay(source, without)
        try {
            assertEquals(Color.WHITE, altered.getPixel(100, 100))
            assertStrokeRed("center after undo", restored.getPixel(100, 100))
        } finally {
            altered.recycle()
            restored.recycle()
            source.recycle()
        }
    }
}
