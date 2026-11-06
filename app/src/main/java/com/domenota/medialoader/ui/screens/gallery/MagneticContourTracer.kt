package com.domenota.medialoader.ui.screens.gallery

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Lightweight local edge guide for cutout corrections.
 * A reduced resolution color-gradient field is combined with the original
 * segmentation boundary. It is only a guide: the user's finger remains the
 * source of direction and weak edges never trigger arbitrary jumps.
 *
 * No network calls, on-device inference per pointer event or mutable global state.
 */
internal class MagneticContourTracer(
    source: Bitmap,
    originalMask: Bitmap,
) {
    val width: Int = source.width
    val height: Int = source.height
    private val gridWidth = min(512, width)
    private val gridHeight = min(512, height)
    private val xRatio = width.toFloat() / gridWidth
    private val yRatio = height.toFloat() / gridHeight
    private val field = FloatArray(gridWidth * gridHeight)

    init {
        require(width > 1 && height > 1 && width == originalMask.width &&
            height == originalMask.height) { "Неподходящий размер маски для контура" }
        val pixels = IntArray(width * height)
        val alpha = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        originalMask.getPixels(alpha, 0, width, 0, 0, width, height)
        val rgb = IntArray(gridWidth * gridHeight)
        val mask = IntArray(gridWidth * gridHeight)
        for (gy in 0 until gridHeight) for (gx in 0 until gridWidth) {
            val x = ((gx + .5f) * xRatio).toInt().coerceIn(0, width - 1)
            val y = ((gy + .5f) * yRatio).toInt().coerceIn(0, height - 1)
            val idx = gy * gridWidth + gx
            rgb[idx] = pixels[y * width + x]
            mask[idx] = alpha[y * width + x] ushr 24
        }
        for (y in 1 until gridHeight - 1) for (x in 1 until gridWidth - 1) {
            val i = y * gridWidth + x
            val left = rgb[i - 1]; val right = rgb[i + 1]
            val up = rgb[i - gridWidth]; val down = rgb[i + gridWidth]
            // Channel-aware contrast, robust to edges with equal luminance.
            val colorChange = (channelDistance(left, right) +
                channelDistance(up, down)) / 360f
            val maskChange = (abs(mask[i - 1] - mask[i + 1]) +
                abs(mask[i - gridWidth] - mask[i + gridWidth])) / 510f
            field[i] = (colorChange * .69f + maskChange * .95f).coerceIn(0f, 1f)
        }
    }

    private fun channelDistance(a: Int, b: Int): Float =
        (abs((a shr 16 and 255) - (b shr 16 and 255)) +
            abs((a shr 8 and 255) - (b shr 8 and 255)) +
            abs((a and 255) - (b and 255))).toFloat() / 3f

    fun snap(point: Offset, previous: Offset?, attraction: Float): Offset {
        if (!point.x.isFinite() || !point.y.isFinite()) return previous ?: Offset.Zero
        val x = point.x.coerceIn(0f, width - 1f)
        val y = point.y.coerceIn(0f, height - 1f)
        val fingerX = x / xRatio
        val fingerY = y / yRatio
        val intensity = (attraction / 100f).coerceIn(0f, 1f)
        if (intensity < .01f) return Offset(x, y)
        val radius = (2f + 12f * intensity).roundToInt()
        val ix = fingerX.roundToInt().coerceIn(1, gridWidth - 2)
        val iy = fingerY.roundToInt().coerceIn(1, gridHeight - 2)
        var bestX = ix
        var bestY = iy
        var bestScore = Float.NEGATIVE_INFINITY
        var strongest = 0f
        for (cy in max(1, iy - radius)..min(gridHeight - 2, iy + radius)) {
            for (cx in max(1, ix - radius)..min(gridWidth - 2, ix + radius)) {
                val distance = hypot((cx - fingerX).toDouble(), (cy - fingerY).toDouble()).toFloat()
                if (distance > radius) continue
                val edge = field[cy * gridWidth + cx]
                strongest = max(strongest, edge)
                val continuity = previous?.let {
                    val fromX = it.x / xRatio
                    val fromY = it.y / yRatio
                    hypot((cx - fromX).toDouble(), (cy - fromY).toDouble()).toFloat()
                } ?: 0f
                // Finger proximity beats far-away edges. Penalize discontinuous jumps.
                val score = edge * (1.1f + intensity * 1.5f) -
                    distance * (.10f - .055f * intensity) -
                    (continuity - 4f).coerceAtLeast(0f) * .005f
                if (score > bestScore) {
                    bestScore = score
                    bestX = cx
                    bestY = cy
                }
            }
        }
        if (strongest < .09f || field[bestY * gridWidth + bestX] < .075f) return Offset(x, y)
        return Offset(((bestX + .5f) * xRatio).coerceIn(0f, width - 1f),
            ((bestY + .5f) * yRatio).coerceIn(0f, height - 1f))
    }

    /**
     * Fill in sampled positions along the finger's movement. Without intermediate
     * samples, a quick movement would cut corners between detected edge points.
     */
    fun follow(from: Offset, to: Offset, attraction: Float): List<Offset> {
        if (!from.x.isFinite() || !to.x.isFinite() ||
            !from.y.isFinite() || !to.y.isFinite()) return emptyList()
        val step = min(xRatio, yRatio).coerceAtLeast(1f) * 2.3f
        val distance = hypot((to.x - from.x).toDouble(), (to.y - from.y).toDouble()).toFloat()
        val steps = ceil(distance / step).toInt().coerceIn(1, 256)
        val points = ArrayList<Offset>(steps)
        var previous = from
        for (index in 1..steps) {
            val f = index.toFloat() / steps
            val finger = Offset(from.x + (to.x - from.x) * f, from.y + (to.y - from.y) * f)
            val nearest = snap(finger, previous, attraction)
            if (hypot(nearest.x - previous.x, nearest.y - previous.y) > .65f) {
                points += nearest
                previous = nearest
            }
        }
        return points
    }
}
