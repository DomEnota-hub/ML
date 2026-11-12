package com.domenota.medialoader.data.editor

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Bounded recovery for a large silhouette when LaMa predicts a dark/coloured
 * ghost on a demonstrably smooth background. Estimate a robust RGB gradient
 * exclusively from real unmasked context. Fail closed for textured/ambiguous
 * scenes, insufficient surrounding support, or an already plausible LaMa
 * result. Never paint outside the prepared mask and never replace the model.
 */
internal object LargePersonBackgroundRecovery {
    private data class Sample(val x: Int, val y: Int, val color: Int)
    private data class Plane(val coefficients: Array<DoubleArray>) {
        fun value(x: Double, y: Double, channel: Int): Double =
            (coefficients[0][channel] + coefficients[1][channel] * x +
                coefficients[2][channel] * y).coerceIn(0.0, 255.0)
    }

    private fun component(color: Int, channel: Int) =
        (color ushr ((2 - channel) * 8)) and 255

    fun recover(source: IntArray, coverage: IntArray, width: Int, height: Int,
        modelComposite: IntArray, checkCancellation: () -> Unit = {}): IntArray? {
        require(width > 0 && height > 0 && source.size == width * height &&
            coverage.size == source.size && modelComposite.size == source.size)
        val masked = coverage.count { it > 0 }
        val fraction = masked.toDouble() / coverage.size
        if (fraction < .30 || fraction > .72 || width < 32 || height < 32) return null
        val step = (maxOf(width, height) / 100).coerceAtLeast(1)
        val guard = (maxOf(width, height) / 100).coerceAtLeast(2)
        val xs = DoubleArray(width) { it * 2.0 / (width - 1) - 1.0 }
        val ys = DoubleArray(height) { it * 2.0 / (height - 1) - 1.0 }
        val samples = ArrayList<Sample>()
        for (y in 0 until height step step) for (x in 0 until width step step) {
            if (x == 0) checkCancellation()
            val i = y * width + x
            if (coverage[i] != 0 || (source[i] ushr 24) < 250) continue
            var clear = true
            for (dy in -1..1) for (dx in -1..1) {
                val nx = (x + dx * guard).coerceIn(0, width - 1)
                val ny = (y + dy * guard).coerceIn(0, height - 1)
                if (coverage[ny * width + nx] != 0) clear = false
            }
            if (clear) samples.add(Sample(x, y, source[i]))
        }
        if (samples.size < 120) return null
        fun error(sample: Sample, plane: Plane): Double =
            (0..2).sumOf { channel -> abs(component(sample.color, channel) -
                plane.value(xs[sample.x], ys[sample.y], channel)) } / 3.0

        var inliers: List<Sample> = samples
        repeat(4) {
            checkCancellation()
            val estimate = fit(inliers, xs, ys) ?: return null
            val errors = samples.map { error(it, estimate) }.sorted()
            val threshold = (errors[errors.size / 2] * 2.5).coerceIn(12.0, 32.0)
            inliers = samples.filter { error(it, estimate) <= threshold }
            if (inliers.size < 100) return null
        }
        val plane = fit(inliers, xs, ys) ?: return null
        val errors = samples.map { error(it, plane) }.sorted()
        val trusted = samples.filter { error(it, plane) <= 22.0 }
        if (trusted.size < samples.size * .78 || errors[errors.size / 2] > 10.0) return null
        val trustedErrors = trusted.map { error(it, plane) }.sorted()
        if (trustedErrors[(trustedErrors.size - 1) * 9 / 10] > 18.0) return null
        // A single strip of sky or floor cannot justify extrapolating across
        // an entire person. Require known context in all four quadrants.
        val quadrants = IntArray(4)
        for (sample in trusted) quadrants[(if (sample.y >= height / 2) 2 else 0) +
            (if (sample.x >= width / 2) 1 else 0)]++
        if (quadrants.any { it < maxOf(8, trusted.size / 50) }) return null

        val modelErrors = ArrayList<Double>()
        for (y in 0 until height step step) for (x in 0 until width step step) {
            val i = y * width + x
            if (coverage[i] != 255) continue
            modelErrors += error(Sample(x, y, modelComposite[i]), plane)
        }
        if (modelErrors.size < 40 ||
            modelErrors.count { it > 35.0 } < modelErrors.size * .35) return null
        modelErrors.sort()
        if (modelErrors[modelErrors.size / 2] < 22.0) return null

        // Keep a little of the model's local high-frequency texture, not its
        // low-frequency ghost/colour cast. The cap avoids reintroducing edges.
        val output = source.copyOf()
        val radius = (maxOf(width, height) / 220).coerceIn(2, 5)
        val neighbour = IntArray(3)
        for (y in 0 until height) for (x in 0 until width) {
            if (x == 0 && y % 16 == 0) checkCancellation()
            val i = y * width + x
            val alpha = coverage[i]
            if (alpha == 0) continue
            var color = source[i] and 0xff000000.toInt()
            var count = 0
            neighbour.fill(0)
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = x + dx * radius; val ny = y + dy * radius
                if (nx !in 0 until width || ny !in 0 until height) continue
                val n = ny * width + nx
                if (coverage[n] != 255) continue
                for (channel in 0..2) neighbour[channel] += component(modelComposite[n], channel)
                count++
            }
            for (channel in 0..2) {
                val detail = if (count >= 2 && alpha == 255)
                    (component(modelComposite[i], channel) - neighbour[channel].toDouble() / count)
                        .coerceIn(-6.0, 6.0) else 0.0
                val replacement = (plane.value(xs[x], ys[y], channel) + detail).coerceIn(0.0, 255.0)
                val value = ((component(source[i], channel) * (255 - alpha) +
                    replacement * alpha) / 255.0).roundToInt().coerceIn(0, 255)
                color = color or (value shl ((2 - channel) * 8))
            }
            output[i] = color
        }
        return output
    }

    /** Three-coefficient least-squares plane with three RGB right-hand sides. */
    private fun fit(samples: List<Sample>, xs: DoubleArray, ys: DoubleArray): Plane? {
        val matrix = Array(3) { DoubleArray(6) }
        for (sample in samples) {
            val vector = doubleArrayOf(1.0, xs[sample.x], ys[sample.y])
            for (row in 0..2) {
                for (col in 0..2) matrix[row][col] += vector[row] * vector[col]
                for (channel in 0..2) matrix[row][3 + channel] +=
                    vector[row] * component(sample.color, channel)
            }
        }
        for (col in 0..2) {
            val pivot = (col..2).maxByOrNull { abs(matrix[it][col]) } ?: return null
            if (abs(matrix[pivot][col]) < 1e-8) return null
            val temp = matrix[col]; matrix[col] = matrix[pivot]; matrix[pivot] = temp
            val scale = matrix[col][col]
            for (j in col until 6) matrix[col][j] /= scale
            for (row in 0..2) if (row != col) {
                val factor = matrix[row][col]
                for (j in col until 6) matrix[row][j] -= factor * matrix[col][j]
            }
        }
        return Plane(Array(3) { row -> DoubleArray(3) { channel -> matrix[row][3 + channel] } })
    }
}
