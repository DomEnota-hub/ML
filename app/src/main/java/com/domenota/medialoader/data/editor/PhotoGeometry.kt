package com.domenota.medialoader.data.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** 4 corners ordered: top-left, top-right, bottom-right, bottom-left. */
data class PhotoGeometryPoint(val x: Float, val y: Float) {
    init { require(x.isFinite() && y.isFinite() && x in 0f..1f && y in 0f..1f) }
}

/** Persistent geometry is resolution-independent and is identical in preview and export. */
data class PhotoGeometry(
    val straighten: Float = 0f,
    val horizontalPerspective: Float = 0f,
    val verticalPerspective: Float = 0f,
    val corners: List<PhotoGeometryPoint> = identityCorners(),
) {
    init {
        require(straighten.isFinite() && straighten in -45f..45f)
        require(horizontalPerspective.isFinite() && horizontalPerspective in -50f..50f)
        require(verticalPerspective.isFinite() && verticalPerspective in -50f..50f)
        require(corners.size == 4 && PhotoGeometryMath.isValidQuad(corners))
    }
    fun isNeutral() = straighten == 0f && horizontalPerspective == 0f &&
        verticalPerspective == 0f && corners == identityCorners()

    companion object {
        fun identityCorners() = listOf(
            PhotoGeometryPoint(0f, 0f), PhotoGeometryPoint(1f, 0f),
            PhotoGeometryPoint(1f, 1f), PhotoGeometryPoint(0f, 1f))
    }
}

object PhotoGeometryMath {
    /** Reject crossed, collapsed and concave shapes before they reach the renderer. */
    fun isValidQuad(points: List<PhotoGeometryPoint>): Boolean {
        if (points.size != 4) return false
        val doubleArea = points.indices.sumOf { i ->
            val a = points[i]; val b = points[(i + 1) % 4]
            (a.x * b.y - b.x * a.y).toDouble()
        }
        if (doubleArea < .12) return false
        var sign = 0
        for (i in 0 until 4) {
            val a = points[i]; val b = points[(i + 1) % 4]
            val c = points[(i + 2) % 4]
            val cross = (b.x - a.x) * (c.y - b.y) -
                (b.y - a.y) * (c.x - b.x)
            if (abs(cross) < .001f) return false
            val nextSign = if (cross > 0) 1 else -1
            if (sign != 0 && nextSign != sign) return false
            sign = nextSign
        }
        return true
    }

    fun moveCorner(settings: PhotoGeometry, index: Int, point: PhotoGeometryPoint): PhotoGeometry {
        require(index in 0..3)
        val candidate = settings.corners.toMutableList()
        candidate[index] = point
        return if (isValidQuad(candidate)) settings.copy(corners = candidate) else settings
    }

    /** Pixel-space coordinates of the perspective destination, bounded inside the output. */
    fun destination(settings: PhotoGeometry, width: Int, height: Int): FloatArray {
        require(width > 0 && height > 0)
        val h = settings.horizontalPerspective / 100f
        val v = settings.verticalPerspective / 100f
        // +h narrows the top edge; +v narrows the left edge.
        val offsets = arrayOf(
            floatArrayOf(h, v),
            floatArrayOf(1f - h, -v),
            floatArrayOf(1f + h, 1f + v),
            floatArrayOf(-h, 1f - v),
        )
        return FloatArray(8) { component ->
            val i = component / 2
            val corner = settings.corners[i]
            val p = offsets[i][component % 2]
            val coordinate = if (component % 2 == 0) corner.x + p -
                (if (i == 0 || i == 3) 0f else 1f)
                else corner.y + p - (if (i < 2) 0f else 1f)
            coordinate * (if (component % 2 == 0) width.toFloat() else height.toFloat())
        }
    }

    /**
     * Bitmap perspective is applied after straighten. Straighten zooms into
     * the image to avoid exposed empty triangles at the canvas perimeter.
     */
    fun transformMatrix(settings: PhotoGeometry, width: Int, height: Int): Matrix {
        require(width > 0 && height > 0)
        val src = floatArrayOf(0f, 0f, width.toFloat(), 0f,
            width.toFloat(), height.toFloat(), 0f, height.toFloat())
        val dst = destination(settings, width, height)
        val warp = Matrix()
        require(warp.setPolyToPoly(src, 0, dst, 0, 4)) { "Недопустимая перспектива" }
        if (settings.straighten != 0f) {
            val radians = Math.toRadians(settings.straighten.toDouble())
            val sine = abs(sin(radians)).toFloat()
            val cosine = abs(cos(radians)).toFloat()
            val zoom = max(cosine + height.toFloat() / width * sine,
                cosine + width.toFloat() / height * sine).coerceIn(1f, 4f)
            val rotation = Matrix().apply {
                setRotate(settings.straighten, width / 2f, height / 2f)
                postScale(zoom, zoom, width / 2f, height / 2f)
            }
            warp.preConcat(rotation)
        }
        return warp
    }

    fun apply(source: Bitmap, settings: PhotoGeometry): Bitmap {
        if (settings.isNeutral()) return source
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(output)
            canvas.drawBitmap(source, transformMatrix(settings, source.width, source.height),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            return output
        } catch (error: Throwable) {
            output.recycle()
            throw error
        }
    }
}
