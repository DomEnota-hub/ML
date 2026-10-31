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
        val h = settings.horizontalPerspective / 250f
        val v = settings.verticalPerspective / 250f
        // Horizontal perspective narrows/expands the top edge. Vertical
        // perspective narrows/expands the left edge. Adjacent points are
        // kept in the bounds to prevent invalid projective matrices.
        val delta = arrayOf(
            floatArrayOf(h, v), floatArrayOf(-h, 0f),
            floatArrayOf(0f, 0f), floatArrayOf(0f, -v),
        )
        val adjusted = List(4) { i ->
            PhotoGeometryPoint(
                (settings.corners[i].x + delta[i][0]).coerceIn(0f, 1f),
                (settings.corners[i].y + delta[i][1]).coerceIn(0f, 1f))
        }
        val quad = if (isValidQuad(adjusted)) adjusted else settings.corners
        return FloatArray(8) { i ->
            val p = quad[i / 2]
            if (i % 2 == 0) p.x * width else p.y * height
        }
    }

    /**
     * Bitmap perspective is applied after straighten. Straighten zooms into
     * the image to avoid exposed empty triangles at the canvas perimeter.
     */
    fun transformMatrix(settings: PhotoGeometry, width: Int, height: Int): Matrix {
        require(width > 0 && height > 0)
        val dst = destination(settings, width, height)
        val x0 = dst[0] / width; val y0 = dst[1] / height
        val x1 = dst[2] / width; val y1 = dst[3] / height
        val x2 = dst[4] / width; val y2 = dst[5] / height
        val x3 = dst[6] / width; val y3 = dst[7] / height
        // Solve the 4-point homography explicitly instead of relying on
        // Android Matrix.setPolyToPoly, which is not implemented consistently
        // by the Robolectric runner. The resulting projective 3x3 matrix is
        // valid for Android Canvas and independent of source resolution.
        val dx1 = x1 - x2; val dx2 = x3 - x2
        val dy1 = y1 - y2; val dy2 = y3 - y2
        val dx3 = x0 - x1 + x2 - x3
        val dy3 = y0 - y1 + y2 - y3
        val g: Float
        val h: Float
        if (abs(dx3) < 1e-7f && abs(dy3) < 1e-7f) {
            g = 0f; h = 0f
        } else {
            val det = dx1 * dy2 - dx2 * dy1
            require(abs(det) >= 1e-7f) { "Недопустимая перспектива" }
            g = (dx3 * dy2 - dx2 * dy3) / det
            h = (dx1 * dy3 - dx3 * dy1) / det
        }
        val a = x1 - x0 + g * x1
        val b = x3 - x0 + h * x3
        val c = x0
        val d = y1 - y0 + g * y1
        val e = y3 - y0 + h * y3
        val f = y0
        val warp = Matrix().apply {
            setValues(floatArrayOf(
                a, b * width / height, c * width,
                d * height / width, e, f * height,
                g / width, h / height, 1f,
            ))
        }
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

    fun preview(source: Bitmap, crop: CropFractions, rotation: Int,
        mirrorHorizontal: Boolean, mirrorVertical: Boolean,
        settings: PhotoGeometry): Bitmap {
        val prepared = TextLayerRenderer.preview(source, crop, rotation,
            mirrorHorizontal, mirrorVertical)
        try {
            val warped = apply(prepared, settings)
            if (warped !== prepared && prepared !== source) prepared.recycle()
            return warped
        } catch (error: Throwable) {
            if (prepared !== source && !prepared.isRecycled) prepared.recycle()
            throw error
        }
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
