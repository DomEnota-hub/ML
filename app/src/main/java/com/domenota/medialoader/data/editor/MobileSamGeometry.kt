package com.domenota.medialoader.data.editor

import java.io.IOException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** SAM encoder keeps image aspect ratio, resizing its longest side to 1024. */
internal data class MobileSamFrame(val width: Int, val height: Int)

internal object MobileSamGeometry {
    const val INPUT_SIDE = 1024
    const val EMBEDDING_ELEMENTS = 256 * 64 * 64

    fun frame(width: Int, height: Int): MobileSamFrame {
        require(width > 0 && height > 0)
        val scale = INPUT_SIDE.toFloat() / max(width, height)
        return MobileSamFrame(
            (width * scale).roundToInt().coerceIn(1, INPUT_SIDE),
            (height * scale).roundToInt().coerceIn(1, INPUT_SIDE),
        )
    }

    /** x,y are normalized in displayed source pixels. The model uses resized source pixels. */
    fun prompt(point: MaskPoint, frame: MobileSamFrame): FloatArray = floatArrayOf(
        (point.x * frame.width).coerceIn(0f, frame.width - 1f),
        (point.y * frame.height).coerceIn(0f, frame.height - 1f),
    )

    /**
     * Turn the decoder's *logits* (NOT probabilities) into existing LaMa mask strokes.
     * Keep only the connected component under the user's tap so disconnected islands
     * and unrelated background are not accidentally deleted.
     */
    fun strokesFromLogits(
        logits: FloatArray,
        width: Int,
        height: Int,
        click: MaskPoint,
    ): List<MaskStroke> {
        require(width > 0 && height > 0 && width <= INPUT_SIDE && height <= INPUT_SIDE)
        require(logits.size == width * height) { "Неверный размер маски MobileSAM." }
        val pixels = width * height
        if (logits.any { !it.isFinite() }) throw IOException("Некорректные значения маски MobileSAM.")
        val foreground = BooleanArray(pixels) { logits[it] > 0f }
        val x = (click.x * width).toInt().coerceIn(0, width - 1)
        val y = (click.y * height).toInt().coerceIn(0, height - 1)
        var seed = y * width + x
        if (!foreground[seed]) {
            var best = Int.MAX_VALUE
            for (dy in -5..5) for (dx in -5..5) {
                val sx = x + dx; val sy = y + dy
                if (sx !in 0 until width || sy !in 0 until height) continue
                val pos = sy * width + sx
                val distance = dx * dx + dy * dy
                if (foreground[pos] && distance < best) {
                    best = distance
                    seed = pos
                }
            }
            if (best == Int.MAX_VALUE) throw IOException("Объект не выделен. Попробуй другую точку.")
        }

        val visited = BooleanArray(pixels)
        val queue = IntArray(pixels)
        var first = 0; var last = 1
        queue[0] = seed; visited[seed] = true
        while (first < last) {
            val index = queue[first++]
            val cx = index % width
            val cy = index / width
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = cx + dx; val ny = cy + dy
                if (nx !in 0 until width || ny !in 0 until height) continue
                val next = ny * width + nx
                if (!visited[next] && foreground[next]) {
                    visited[next] = true
                    queue[last++] = next
                }
            }
        }
        if (last < 32) throw IOException("Выделение слишком маленькое.")
        if (last > pixels * 0.80f) throw IOException(
            "Выделена почти вся фотография. Уточни объект или используй кисть."
        )

        val strokes = ArrayList<MaskStroke>()
        // A compact horizontal run in source-normalized coordinates is an ordinary
        // mask stroke. A 2px brush width bridges adjacent scanlines without holes.
        val diameter = (2.0f / minOf(width, height)).coerceIn(0.001f, 0.5f)
        for (row in 0 until height) {
            var column = 0
            while (column < width) {
                if (!visited[row * width + column]) {
                    column++
                    continue
                }
                val start = column
                while (column < width && visited[row * width + column]) column++
                val end = column - 1
                val py = (row + 0.5f) / height
                strokes.add(MaskStroke(listOf(
                    MaskPoint((start + 0.5f) / width, py),
                    MaskPoint((end + 0.5f) / width, py),
                ), diameter))
                if (strokes.size > 12_000) throw IOException(
                    "Слишком сложная маска. Уточни выделение кистью."
                )
            }
        }
        return strokes
    }
}
