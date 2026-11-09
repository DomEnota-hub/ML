package com.domenota.medialoader.data.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import java.io.IOException

/**
 * Export a neural correction as a TRANSPARENT overlay, not a scaled opaque
 * replacement of the whole source photograph.
 *
 * Normal export draws this overlay on the original, full-resolution photo.
 * Untouched areas keep their original pixels, sharpness and metadata. Previous
 * edits are retained across separate AI-eraser sessions by inheriting the
 * previous app-owned overlay.
 */
internal object SpecularOverlayComposer {
    fun combine(
        sessionOriginal: Bitmap,
        edited: Bitmap,
        previousOverlay: Bitmap? = null,
    ): Bitmap {
        require(sessionOriginal.width == edited.width &&
            sessionOriginal.height == edited.height &&
            sessionOriginal.width > 0 && sessionOriginal.height > 0)
        val width = edited.width
        val height = edited.height
        val overlay = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            if (previousOverlay != null) {
                if (previousOverlay.isRecycled || previousOverlay.width < 1 ||
                    previousOverlay.height < 1) throw IOException("Предыдущая маска повреждена.")
                Canvas(overlay).drawBitmap(
                    previousOverlay,
                    Rect(0, 0, previousOverlay.width, previousOverlay.height),
                    Rect(0, 0, width, height),
                    Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
                )
            }
            val originalRow = IntArray(width)
            val editedRow = IntArray(width)
            val outputRow = IntArray(width)
            for (y in 0 until height) {
                sessionOriginal.getPixels(originalRow, 0, width, 0, y, width, 1)
                edited.getPixels(editedRow, 0, width, 0, y, width, 1)
                overlay.getPixels(outputRow, 0, width, 0, y, width, 1)
                for (x in 0 until width) {
                    if (originalRow[x] != editedRow[x]) {
                        // Matte is fully opaque ONLY at actually modified pixels.
                        // Underlying alpha should not disappear if input source
                        // contains transparency.
                        outputRow[x] = editedRow[x]
                    }
                }
                overlay.setPixels(outputRow, 0, width, 0, y, width, 1)
            }
            return overlay
        } catch (error: Throwable) {
            overlay.recycle()
            throw error
        }
    }
}
