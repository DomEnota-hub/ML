package com.domenota.medialoader.data.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.min

/** Coordinates are normalized to the final cropped and rotated image, not the device display. */
data class PhotoPoint(val x: Float, val y: Float) {
    init { require(x.isFinite() && y.isFinite() && x in 0f..1f && y in 0f..1f) }
}

enum class PhotoMarkKind(val title: String) {
    PEN("Карандаш"), MARKER("Маркер"), NEON("Неоновая кисть"),
    LINE("Линия"), RECTANGLE("Прямоугольник"),
    OVAL("Овал"), ARROW("Стрелка")
}

data class PhotoMark(
    val kind: PhotoMarkKind,
    val points: List<PhotoPoint>,
    val color: Int = android.graphics.Color.WHITE,
    /** Fraction of the shorter image side. */
    val thickness: Float = .008f,
    /** Rotation of a vector shape around its own center. */
    val rotationDegrees: Float = 0f,
) {
    init {
        require(points.isNotEmpty() && points.size <= 900)
        require(thickness.isFinite() && thickness in .001f..0.08f)
        require(rotationDegrees.isFinite() && rotationDegrees in -360f..360f)
    }
}

object PhotoDrawing {
    const val MAX_MARKS = 150
    fun clamp(x: Float, y: Float): PhotoPoint = PhotoPoint(
        x.coerceIn(0f, 1f), y.coerceIn(0f, 1f)
    )

    /** Preview and final-resolution export call this identical geometry renderer. */
    fun draw(canvas: Canvas, width: Int, height: Int, marks: List<PhotoMark>) {
        require(marks.size <= MAX_MARKS)
        val strokeScale = min(width, height).toFloat()
        marks.forEach { mark ->
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                color = mark.color
                style = Paint.Style.STROKE
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND
                strokeWidth = (strokeScale * mark.thickness).coerceAtLeast(1f)
                if (mark.kind == PhotoMarkKind.MARKER) alpha = 140
            }
            val first = mark.points.first()
            val last = mark.points.last()
            val x1 = first.x * width
            val y1 = first.y * height
            val x2 = last.x * width
            val y2 = last.y * height
            val vector = mark.kind !in setOf(PhotoMarkKind.PEN, PhotoMarkKind.MARKER, PhotoMarkKind.NEON)
            val save = if (vector && mark.rotationDegrees != 0f) canvas.save() else -1
            if (save >= 0) canvas.rotate(mark.rotationDegrees, (x1 + x2) / 2f, (y1 + y2) / 2f)
            when (mark.kind) {
                PhotoMarkKind.PEN, PhotoMarkKind.MARKER, PhotoMarkKind.NEON -> {
                    if (mark.kind == PhotoMarkKind.NEON) {
                        val glow = Paint(paint).apply {
                            strokeWidth *= 3.2f
                            alpha = 65
                        }
                        val glowPath = Path().apply {
                            moveTo(x1, y1)
                            mark.points.drop(1).forEach { lineTo(it.x * width, it.y * height) }
                        }
                        if (mark.points.size == 1) canvas.drawPoint(x1, y1, glow)
                        else canvas.drawPath(glowPath, glow)
                    }
                    val path = Path().apply {
                        moveTo(x1, y1)
                        mark.points.drop(1).forEach { lineTo(it.x * width, it.y * height) }
                    }
                    if (mark.points.size == 1) {
                        canvas.drawPoint(x1, y1, paint)
                    } else canvas.drawPath(path, paint)
                }
                PhotoMarkKind.LINE -> canvas.drawLine(x1, y1, x2, y2, paint)
                PhotoMarkKind.RECTANGLE -> canvas.drawRect(
                    RectF(minOf(x1, x2), minOf(y1, y2), maxOf(x1, x2), maxOf(y1, y2)), paint)
                PhotoMarkKind.OVAL -> canvas.drawOval(
                    RectF(minOf(x1, x2), minOf(y1, y2), maxOf(x1, x2), maxOf(y1, y2)), paint)
                PhotoMarkKind.ARROW -> {
                    canvas.drawLine(x1, y1, x2, y2, paint)
                    val dx = x2 - x1
                    val dy = y2 - y1
                    val length = kotlin.math.hypot(dx, dy)
                    if (length > 1f) {
                        val head = minOf(strokeScale * .045f, length * .42f)
                        val ux = dx / length
                        val uy = dy / length
                        canvas.drawLine(x2, y2, x2 - head * (ux * .8f - uy * .6f),
                            y2 - head * (uy * .8f + ux * .6f), paint)
                        canvas.drawLine(x2, y2, x2 - head * (ux * .8f + uy * .6f),
                            y2 - head * (uy * .8f - ux * .6f), paint)
                    }
                }
            }
            if (save >= 0) canvas.restoreToCount(save)
        }
    }

    fun overlay(source: Bitmap, marks: List<PhotoMark>): Bitmap {
        if (marks.isEmpty()) return source
        val result = source.copy(Bitmap.Config.ARGB_8888, true)
        draw(Canvas(result), result.width, result.height, marks)
        return result
    }
}
