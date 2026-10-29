package com.domenota.medialoader.data.editor

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** One OCR symbol, not a line or word. Coordinates are relative to the working image. */
data class DetectedGlyph(
    val id: Int,
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(left >= 0f && top >= 0f && right <= 1f && bottom <= 1f)
        require(left < right && top < bottom)
    }

    fun contains(x: Float, y: Float): Boolean =
        x >= left && x <= right && y >= top && y <= bottom

    /** A crossing stroke selects a glyph even if no sampled pointer hit lies inside it. */
    fun crosses(x0: Float, y0: Float, x1: Float, y1: Float): Boolean {
        if (contains(x0, y0) || contains(x1, y1)) return true
        val dx = x1 - x0
        val dy = y1 - y0
        var enter = 0f
        var leave = 1f
        fun clip(p: Float, q: Float): Boolean {
            if (abs(p) < 1e-8f) return q >= 0f
            val r = q / p
            if (p < 0f) enter = max(enter, r) else leave = min(leave, r)
            return enter <= leave
        }
        return clip(-dx, x0 - left) && clip(dx, right - x0) &&
            clip(-dy, y0 - top) && clip(dy, bottom - y0)
    }
}

object TextGlyphMask {
    /**
     * Render independently recognized symbols as filled narrow parallel strokes.
     * Convert each dimension through real bitmap pixels, not normalized width/height:
     * this keeps character masks tight on portrait AND landscape images.
     */
    fun toStrokes(glyphs: Collection<DetectedGlyph>, imageWidth: Int, imageHeight: Int): List<MaskStroke> {
        require(imageWidth > 0 && imageHeight > 0)
        val shortSide = min(imageWidth, imageHeight).toFloat()
        return buildList {
            for (glyph in glyphs.take(160)) {
                val glyphWidthPx = (glyph.right - glyph.left) * imageWidth
                val glyphHeightPx = (glyph.bottom - glyph.top) * imageHeight
                val padXPx = min(glyphWidthPx * 0.08f, 2.5f)
                val padYPx = min(glyphHeightPx * 0.08f, 2.5f)
                val left = (glyph.left * imageWidth - padXPx).coerceAtLeast(0f)
                val right = (glyph.right * imageWidth + padXPx).coerceAtMost(imageWidth.toFloat())
                val top = (glyph.top * imageHeight - padYPx).coerceAtLeast(0f)
                val bottom = (glyph.bottom * imageHeight + padYPx).coerceAtMost(imageHeight.toFloat())
                val diameterPx = min((bottom - top) / 5f, (right - left) * 0.85f)
                    .coerceIn(1f, shortSide * 0.5f)
                val available = (bottom - top - diameterPx).coerceAtLeast(0f)
                val rows = max(1, (available / (diameterPx * 0.82f)).toInt() + 2)
                    .coerceAtMost(32)
                for (row in 0 until rows) {
                    val yPx = (top + diameterPx / 2f +
                        (if (rows == 1) 0f else available * row / (rows - 1)))
                        .coerceIn(0f, imageHeight.toFloat())
                    val x0 = (left + diameterPx / 2f).coerceAtMost(right)
                    val x1 = (right - diameterPx / 2f).coerceAtLeast(left)
                    val positions = if (x1 - x0 <= 0.5f) {
                        listOf(MaskPoint(((left + right) / 2f / imageWidth).coerceIn(0f, 1f),
                            (yPx / imageHeight).coerceIn(0f, 1f)))
                    } else listOf(
                        MaskPoint((x0 / imageWidth).coerceIn(0f, 1f),
                            (yPx / imageHeight).coerceIn(0f, 1f)),
                        MaskPoint((x1 / imageWidth).coerceIn(0f, 1f),
                            (yPx / imageHeight).coerceIn(0f, 1f)),
                    )
                    add(MaskStroke(positions, (diameterPx / shortSide).coerceIn(0.00001f, 0.5f)))
                }
            }
        }
    }
}

/**
 * Bundled Russian/English OCR with actual symbol-level bounds.
 */
class OnDeviceTextGlyphDetector(private val context: android.content.Context) {
    suspend fun detect(image: Bitmap): List<DetectedGlyph> = LocalTextOcr.glyphs(context, image)
}
