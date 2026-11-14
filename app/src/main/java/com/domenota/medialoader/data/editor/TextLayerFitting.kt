package com.domenota.medialoader.data.editor

import android.content.Context
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.cos
import kotlin.math.sin

/** Placement in actual EXIF-oriented output pixels. Draft size limits remain unchanged. */
internal data class FittedTextPlacement(val centerX: Float, val centerY: Float,
    val scale: Float, val bounds: RectF)

/** Opt-in fitting for shared batch watermarks; ordinary photo/video layers remain unconstrained. */
internal object TextLayerFitting {
    fun placement(context: Context, width: Int, height: Int, layer: TextLayer): FittedTextPlacement {
        require(width > 0 && height > 0)
        val layout = TextLayerRenderer.layout(context, width, height, layer)
        var left = Float.POSITIVE_INFINITY
        var right = Float.NEGATIVE_INFINITY
        var top = 0f
        var bottom = layout.height.toFloat()
        val ink = Rect()
        for (line in 0 until layout.lineCount) {
            val start = layout.getLineStart(line)
            val end = layout.getLineEnd(line)
            layout.paint.getTextBounds(layer.text, start, end, ink)
            val lineLeft = layout.getLineLeft(line)
            val advance = layout.paint.measureText(layer.text, start, end)
            left = minOf(left, lineLeft + minOf(0, ink.left))
            right = maxOf(right, layout.getLineRight(line) + maxOf(0f, ink.right - advance))
            top = minOf(top, layout.getLineBaseline(line) + ink.top.toFloat())
            bottom = maxOf(bottom, layout.getLineBaseline(line) + ink.bottom.toFloat())
        }
        // Stroke extends by half its width. Shadow includes blur plus both offsets.
        val margin = layout.paint.textSize * (layer.outline + if (layer.shadow) .24f else 0f) +
            minOf(width, height) * .002f
        left -= layout.width / 2f + margin
        right -= layout.width / 2f - margin
        top -= layout.height / 2f + margin
        bottom -= layout.height / 2f - margin
        val angle = Math.toRadians(layer.rotation.toDouble())
        val c = cos(angle).toFloat(); val s = sin(angle).toFloat()
        val corners = listOf(left to top, right to top, right to bottom, left to bottom)
            .map { (x,y) -> (x*c-y*s) to (x*s+y*c) }
        val bounds = RectF(corners.minOf { it.first },corners.minOf { it.second },
            corners.maxOf { it.first },corners.maxOf { it.second })
        val scale = minOf(1f,width / bounds.width().coerceAtLeast(1f),
            height / bounds.height().coerceAtLeast(1f))
        val minX = -bounds.left * scale; val maxX = width - bounds.right * scale
        val minY = -bounds.top * scale; val maxY = height - bounds.bottom * scale
        val cx = (width * layer.centerX).coerceIn(minOf(minX,maxX),maxOf(minX,maxX))
        val cy = (height * layer.centerY).coerceIn(minOf(minY,maxY),maxOf(minY,maxY))
        return FittedTextPlacement(cx,cy,scale,RectF(cx+bounds.left*scale,cy+bounds.top*scale,
            cx+bounds.right*scale,cy+bounds.bottom*scale))
    }

    fun centered(context: Context, width: Int, height: Int, layer: TextLayer): TextLayer {
        val fitted = placement(context,width,height,layer)
        return layer.copy(centerX=(fitted.centerX/width).coerceIn(0f,1f),
            centerY=(fitted.centerY/height).coerceIn(0f,1f))
    }
}
