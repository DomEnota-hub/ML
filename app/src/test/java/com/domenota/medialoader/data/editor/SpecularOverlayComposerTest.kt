package com.domenota.medialoader.data.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SpecularOverlayComposerTest {
    @Test fun transparentDeltaLeavesOriginalFullResolutionPixelsUntouched() {
        val highRes = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        for (y in 0 until 128) for (x in 0 until 128) {
            val c = if ((x + y) % 2 == 0) Color.rgb(10, 20, 180)
                else Color.rgb(20, 10, 170)
            highRes.setPixel(x, y, c)
        }
        val before = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLUE)
        }
        val after = before.copy(Bitmap.Config.ARGB_8888, true)
        for (y in 10..19) for (x in 10..19) after.setPixel(x, y, Color.RED)
        val overlay = SpecularOverlayComposer.combine(before, after)
        try {
            assertEquals(0, Color.alpha(overlay.getPixel(4, 4)))
            assertEquals(Color.RED, overlay.getPixel(15, 15))
            val output = highRes.copy(Bitmap.Config.ARGB_8888, true)
            try {
                Canvas(output).drawBitmap(overlay,
                    Rect(0, 0, 32, 32), Rect(0, 0, 128, 128), Paint(Paint.FILTER_BITMAP_FLAG))
                assertEquals(highRes.getPixel(3, 3), output.getPixel(3, 3))
                assertEquals(highRes.getPixel(126, 126), output.getPixel(126, 126))
                assertEquals(Color.RED, output.getPixel(60, 60))
                assertEquals(highRes.getPixel(15, 72), output.getPixel(15, 72))
            } finally {
                output.recycle()
            }
        } finally {
            highRes.recycle()
            before.recycle()
            after.recycle()
            overlay.recycle()
        }
    }

    @Test fun previouslySavedMaskSurvivesReentryAndAnotherCorrection() {
        val before = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLUE)
        }
        val corrected = before.copy(Bitmap.Config.ARGB_8888, true)
        corrected.setPixel(44, 44, Color.RED)
        val previousOverlay = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            setPixel(10, 10, Color.GREEN)
        }
        val combined = SpecularOverlayComposer.combine(before, corrected, previousOverlay)
        try {
            assertEquals(Color.GREEN, combined.getPixel(10, 10))
            assertEquals(Color.RED, combined.getPixel(44, 44))
            assertEquals(0, Color.alpha(combined.getPixel(20, 20)))
            // Re-apply both changes to the original; transparent regions stay intact.
            val result = before.copy(Bitmap.Config.ARGB_8888, true)
            try {
                Canvas(result).drawBitmap(combined, 0f, 0f, null)
                assertEquals(Color.GREEN, result.getPixel(10, 10))
                assertEquals(Color.RED, result.getPixel(44, 44))
                assertEquals(Color.BLUE, result.getPixel(20, 20))
            } finally {
                result.recycle()
            }
        } finally {
            before.recycle()
            corrected.recycle()
            previousOverlay.recycle()
            combined.recycle()
        }
    }

    @Test fun unchangedImageProducesTransparentOverlay() {
        val base = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(100, 50, 20))
        }
        val overlay = SpecularOverlayComposer.combine(base, base)
        try {
            assertEquals(0, Color.alpha(overlay.getPixel(32, 32)))
            assertEquals(0, Color.alpha(overlay.getPixel(0, 0)))
        } finally {
            overlay.recycle()
            base.recycle()
        }
    }
}
