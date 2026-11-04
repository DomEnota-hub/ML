package com.domenota.medialoader.data.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Monotone lens warp, shared by the bounded preview and full-resolution Canvas export. */
internal object PhotoDistortionMath {
    const val GRID = 64
    fun apply(source: Bitmap, amount: Float): Bitmap {
        require(amount.isFinite() && amount in -100f..100f)
        if (amount == 0f) return source
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        try {
            Canvas(output).drawBitmapMesh(source, GRID, GRID,
                vertices(source.width, source.height, amount), 0, null, 0,
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            return output
        } catch (error: Throwable) { output.recycle(); throw error }
    }
    fun vertices(width: Int, height: Int, amount: Float, grid: Int = GRID): FloatArray {
        require(width > 0 && height > 0 && grid > 0)
        require(amount.isFinite() && amount in -100f..100f)
        val strength = abs(amount) / 100f
        val angle = strength * if (amount >= 0) 1.45f else 1.25f
        val divisor = if (amount >= 0) sin(angle) else tan(angle)
        fun radialScale(radius: Float): Float {
            if (amount == 0f) return 1f
            if (radius < .00001f) return angle / divisor
            return (if (amount >= 0) sin(radius * angle) else tan(radius * angle)) / (radius * divisor)
        }
        // Keep side midpoints covering the output, even for a concave negative warp.
        val zoom = if (amount < 0) 1f / radialScale(1f / sqrt(2f)) else 1f
        val vertices = FloatArray((grid + 1) * (grid + 1) * 2)
        var offset = 0
        for (row in 0..grid) for (column in 0..grid) {
            val x = 2f * column / grid - 1f
            val y = 2f * row / grid - 1f
            val radius = sqrt((x * x + y * y) / 2f)
            val scale = radialScale(radius) * zoom
            vertices[offset++] = (.5f + x * scale * .5f) * width
            vertices[offset++] = (.5f + y * scale * .5f) * height
        }
        return vertices
    }
}
