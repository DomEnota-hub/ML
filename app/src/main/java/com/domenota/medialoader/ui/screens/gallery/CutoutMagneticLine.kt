package com.domenota.medialoader.ui.screens.gallery

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Lightweight magnetic edge snapping. Works in bitmap coordinates and searches
 * around the finger instead of tracing the whole image on every touch event.
 * No network and no ML inference are needed for this first iteration.
 */
internal class CutoutMagneticLine(private val image: Bitmap) {
    data class Point(val x: Float, val y: Float)
    private val w = image.width
    private val h = image.height
    private val pixels = IntArray(w * h)
    init { image.getPixels(pixels, 0, w, 0, 0, w, h) }

    private fun luminance(x: Int, y: Int): Float {
        val c = pixels[y.coerceIn(0, h - 1) * w + x.coerceIn(0, w - 1)]
        return ((c ushr 16 and 255) * 0.2126f +
            (c ushr 8 and 255) * 0.7152f + (c and 255) * 0.0722f)
    }

    private fun edge(x: Int, y: Int): Float {
        if (x <= 0 || y <= 0 || x >= w - 1 || y >= h - 1) return 0f
        val gx = abs(luminance(x + 1, y) - luminance(x - 1, y))
        val gy = abs(luminance(x, y + 1) - luminance(x, y - 1))
        return (gx + gy).coerceAtMost(510f) / 510f
    }

    fun snap(x: Float, y: Float, previous: Point?, strength: Float): Point {
        if (w < 3 || h < 3 || strength <= 0f) return Point(x, y)
        val cx = x.roundToInt().coerceIn(1, w - 2)
        val cy = y.roundToInt().coerceIn(1, h - 2)
        val radius = (3 + strength.coerceIn(0f, 1f) * 13).roundToInt()
        val step = if (radius > 10) 2 else 1
        var bestX = cx
        var bestY = cy
        var bestScore = edge(cx, cy) * 2.5f
        for (dy in -radius..radius step step) {
            for (dx in -radius..radius step step) {
                if (dx * dx + dy * dy > radius * radius) continue
                val px = (cx + dx).coerceIn(1, w - 2)
                val py = (cy + dy).coerceIn(1, h - 2)
                val contrast = edge(px, py)
                val distancePenalty = hypot(dx.toFloat(), dy.toFloat()) / radius
                val continuityPenalty = if (previous == null) 0f else {
                    // Resist jumping to a distant unrelated edge.
                    (hypot(px - previous.x, py - previous.y) / (radius * 3f))
                        .coerceAtMost(1f) * .5f
                }
                val score = contrast * 2.5f - distancePenalty * .55f - continuityPenalty
                if (score > bestScore) {
                    bestScore = score
                    bestX = px
                    bestY = py
                }
            }
        }
        // Avoid snapping to noise or flat image regions.
        return if (edge(bestX, bestY) < .09f) Point(x, y)
            else Point(bestX.toFloat(), bestY.toFloat())
    }
}
