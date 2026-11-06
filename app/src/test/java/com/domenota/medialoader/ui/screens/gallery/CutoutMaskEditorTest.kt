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
    @Test fun secondFingerCancelsPartialBrushStrokeWithoutTouchingUndoHistory() {
        val source = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
        val mask = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.RED)
        mask.eraseColor(Color.RED)
        val editor = CutoutMaskEditor(source, mask)
        try {
            editor.startStroke(12f, 12f, 7f, restore = false)
            assertEquals(0, Color.alpha(editor.preview.getPixel(12, 12)))
            editor.cancelStroke()
            assertEquals(255, Color.alpha(editor.preview.getPixel(12, 12)))
            assertFalse(editor.canUndo)
        } finally {
            editor.release()
            source.recycle()
            mask.recycle()
        }
    }

    @Test fun ghostOriginalIsNeverIncludedInSnapshot() {
        val source = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
        val empty = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.BLUE)
        empty.eraseColor(Color.TRANSPARENT)
        val editor = CutoutMaskEditor(source, empty)
        try {
            val snapshot = editor.snapshot()
            try {
                assertEquals(255, Color.alpha(editor.sourceReference.getPixel(12, 12)))
                assertEquals(0, Color.alpha(snapshot.getPixel(12, 12)))
                editor.startStroke(12f, 12f, 6f, restore = true)
                editor.endStroke()
                assertEquals(255, Color.alpha(editor.snapshot().getPixel(12, 12)))
            } finally { snapshot.recycle() }
        } finally {
            editor.release()
            source.recycle()
            empty.recycle()
        }
    }

}
