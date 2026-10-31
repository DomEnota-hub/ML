package com.domenota.medialoader.data.editor

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class PhotoCurveChannel(val title: String) {
    RGB("RGB"), RED("Красный"), GREEN("Зелёный"), BLUE("Синий")
}
data class PhotoCurvePoint(val x: Float, val y: Float) {
    init { require(x.isFinite() && y.isFinite() && x in 0f..1f && y in 0f..1f) }
}
data class PhotoCurve(
    val points: List<PhotoCurvePoint> = listOf(PhotoCurvePoint(0f, 0f), PhotoCurvePoint(1f, 1f)),
) {
    init {
        require(points.size in 2..12)
        require(points.first().x == 0f && points.last().x == 1f)
        require(points.zipWithNext().all { (a, b) -> b.x - a.x >= .005f })
    }

    fun withPoint(index: Int, x: Float, y: Float): PhotoCurve {
        require(index in points.indices)
        val left = if (index == 0) 0f else points[index - 1].x + .006f
        val right = if (index == points.lastIndex) 1f else points[index + 1].x - .006f
        val px = if (index == 0) 0f else if (index == points.lastIndex) 1f
            else x.coerceIn(left, right)
        return PhotoCurve(points.toMutableList().also {
            it[index] = PhotoCurvePoint(px, y.coerceIn(0f, 1f))
        })
    }

    fun insert(x: Float, y: Float): PhotoCurve {
        if (points.size == 12) return this
        val sorted = points.toMutableList()
        val insert = sorted.indexOfFirst { it.x > x }
        if (insert <= 0 || insert >= sorted.size ||
            x - sorted[insert - 1].x < .008f || sorted[insert].x - x < .008f) return this
        sorted.add(insert, PhotoCurvePoint(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f)))
        return PhotoCurve(sorted)
    }

    fun remove(index: Int): PhotoCurve =
        if (index <= 0 || index >= points.lastIndex) this
        else PhotoCurve(points.filterIndexed { i, _ -> i != index })

    fun isNeutral() = points.all { kotlin.math.abs(it.x - it.y) <= 1e-6f }
}

data class PhotoCurves(
    val master: PhotoCurve = PhotoCurve(),
    val red: PhotoCurve = PhotoCurve(),
    val green: PhotoCurve = PhotoCurve(),
    val blue: PhotoCurve = PhotoCurve(),
) {
    fun forChannel(channel: PhotoCurveChannel): PhotoCurve = when (channel) {
        PhotoCurveChannel.RGB -> master
        PhotoCurveChannel.RED -> red
        PhotoCurveChannel.GREEN -> green
        PhotoCurveChannel.BLUE -> blue
    }
    fun update(channel: PhotoCurveChannel, curve: PhotoCurve) = when (channel) {
        PhotoCurveChannel.RGB -> copy(master = curve)
        PhotoCurveChannel.RED -> copy(red = curve)
        PhotoCurveChannel.GREEN -> copy(green = curve)
        PhotoCurveChannel.BLUE -> copy(blue = curve)
    }
    fun isNeutral() = master.isNeutral() && red.isNeutral() && green.isNeutral() && blue.isNeutral()
}

object PhotoCurveMath {
    /** Monotone cubic Hermite slopes: no unwanted overshoot or crushed detail. */
    fun lut(curve: PhotoCurve): IntArray {
        val p = curve.points
        val n = p.size
        val secant = FloatArray(n - 1) { i ->
            (p[i + 1].y - p[i].y) / (p[i + 1].x - p[i].x)
        }
        val slopes = FloatArray(n)
        slopes[0] = secant[0]; slopes[n - 1] = secant.last()
        for (i in 1 until n - 1) {
            val a = secant[i - 1]; val b = secant[i]
            slopes[i] = if (a * b <= 0f) 0f else {
                val left = p[i].x - p[i - 1].x
                val right = p[i + 1].x - p[i].x
                val w1 = 2f * right + left
                val w2 = right + 2f * left
                (w1 + w2) / (w1 / a + w2 / b)
            }
        }
        return IntArray(256) { value ->
            val x = value / 255f
            val index = (0 until n - 1).firstOrNull { x <= p[it + 1].x } ?: (n - 2)
            val begin = p[index]; val end = p[index + 1]
            val h = end.x - begin.x
            val t = ((x - begin.x) / h).coerceIn(0f, 1f)
            val t2 = t * t; val t3 = t2 * t
            val y = (2f * t3 - 3f * t2 + 1f) * begin.y +
                (t3 - 2f * t2 + t) * h * slopes[index] +
                (-2f * t3 + 3f * t2) * end.y +
                (t3 - t2) * h * slopes[index + 1]
            (y.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
        }
    }

    data class Prepared(
        val master: IntArray, val red: IntArray, val green: IntArray, val blue: IntArray,
    ) {
        fun mapRed(value: Int) = red[master[value.coerceIn(0, 255)]]
        fun mapGreen(value: Int) = green[master[value.coerceIn(0, 255)]]
        fun mapBlue(value: Int) = blue[master[value.coerceIn(0, 255)]]
    }
    fun prepare(curves: PhotoCurves) = Prepared(
        lut(curves.master), lut(curves.red), lut(curves.green), lut(curves.blue))
}
