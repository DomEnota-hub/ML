package com.domenota.medialoader.ui.screens.gallery

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CutoutMaskEditorTest {
    @Test fun eraseRestoreAndUndoDoNotChangeSourceOrExportedSnapshot() {
        val source = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val cutout = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.rgb(120, 45, 10))
        cutout.eraseColor(Color.rgb(120, 45, 10))
        val editor = CutoutMaskEditor(source, cutout)
        try {
            editor.startStroke(30f, 30f, 9f, restore = false)
            editor.dragStroke(30f, 30f, 33f, 33f, 9f, restore = false)
            editor.endStroke()
            assertEquals(0, Color.alpha(editor.preview.getPixel(30, 30)))
            assertEquals(255, Color.alpha(editor.preview.getPixel(2, 2)))
            assertTrue(editor.canUndo)
            val saved = editor.snapshot()
            try {
                editor.startStroke(30f, 30f, 9f, restore = true)
                editor.endStroke()
                assertEquals(255, Color.alpha(editor.preview.getPixel(30, 30)))
                assertEquals(0, Color.alpha(saved.getPixel(30, 30)))
                assertTrue(editor.undo())
                assertEquals(0, Color.alpha(editor.preview.getPixel(30, 30)))
                editor.reset()
                assertEquals(255, Color.alpha(editor.preview.getPixel(30, 30)))
                assertFalse(editor.canUndo)
                assertEquals(255, Color.alpha(source.getPixel(30, 30)))
            } finally { saved.recycle() }
        } finally {
            editor.release()
            source.recycle()
            cutout.recycle()
        }
    }

    @Test fun softBrushSupportsTouchOutsideBitmapWithoutArrayErrors() {
        val source = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val cutout = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.GREEN)
        cutout.eraseColor(Color.GREEN)
        val editor = CutoutMaskEditor(source, cutout)
        try {
            editor.startStroke(-4f, 4f, 8f, restore = false)
            editor.dragStroke(-4f, 4f, 25f, 19f, 8f, restore = false)
            editor.endStroke()
            assertTrue(editor.canUndo)
            editor.undo()
            assertEquals(255, Color.alpha(editor.preview.getPixel(0, 0)))
        } finally {
            editor.release()
            source.recycle()
            cutout.recycle()
        }
    }
}
