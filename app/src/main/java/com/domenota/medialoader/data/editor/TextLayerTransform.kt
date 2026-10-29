package com.domenota.medialoader.data.editor

import kotlin.math.atan2
import kotlin.math.hypot

/** Pointer vectors are measured from the fixed text center in preview pixels. */
internal object TextLayerTransform {
    fun fromHandle(layer: TextLayer, startX: Float, startY: Float, x: Float, y: Float,
        previous: TextLayer = layer): TextLayer {
        val startLength = hypot(startX, startY)
        val length = hypot(x, y)
        if (!startLength.isFinite() || !length.isFinite() || startLength < 1f || length < 1f) return previous
        val angle = layer.rotation + Math.toDegrees((atan2(y, x) - atan2(startY, startX)).toDouble()).toFloat()
        return layer.copy(size = (layer.size * length / startLength).coerceIn(0.02f, 0.25f),
            rotation = ((angle + 180f) % 360f + 360f) % 360f - 180f)
    }
}

/** Keep a visible handle away from the pivot, including when the preferred corner is offscreen. */
internal object TextHandlePlacement {
    fun place(cx: Float, cy: Float, x: Float, y: Float, width: Float, height: Float,
        radius: Float, minimumDistance: Float): Pair<Float, Float> {
        val marginX = minOf(radius, width / 2f)
        val marginY = minOf(radius, height / 2f)
        fun clamp(px: Float, py: Float) = px.coerceIn(marginX, width - marginX) to
            py.coerceIn(marginY, height - marginY)
        fun distance(point: Pair<Float, Float>) = hypot(point.first - cx, point.second - cy)
        val preferred = clamp(x, y)
        if (distance(preferred) >= minimumDistance) return preferred
        val alternatives = (0 until 8).map { index ->
            val angle = Math.PI * index / 4
            clamp(cx + kotlin.math.cos(angle).toFloat() * minimumDistance,
                cy + kotlin.math.sin(angle).toFloat() * minimumDistance)
        }
        return alternatives.filter { distance(it) >= minimumDistance - 0.01f }
            .minByOrNull { hypot(it.first - preferred.first, it.second - preferred.second) }
            ?: listOf(clamp(0f, 0f), clamp(width, 0f), clamp(0f, height), clamp(width, height))
                .maxBy { distance(it) }
    }
}
