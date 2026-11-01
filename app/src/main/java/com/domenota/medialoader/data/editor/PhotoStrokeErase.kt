package com.domenota.medialoader.data.editor

import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Destructive-to-the-drawing, non-destructive-to-the-photo eraser.
 *
 * Rather than persisting a fixed CLEAR overlay in canvas coordinates, remove
 * the touched portions from the stroke geometry itself. Remaining pieces can
 * subsequently be moved or resized without uncovering erased pixels.
 */
object PhotoStrokeErase {
    fun apply(
        marks: List<PhotoMark>,
        eraserPath: List<PhotoPoint>,
        eraserThickness: Float,
        imageWidth: Int,
        imageHeight: Int,
    ): List<PhotoMark> {
        if (marks.isEmpty() || eraserPath.isEmpty()) return marks
        val shortSide = min(imageWidth, imageHeight).coerceAtLeast(1).toFloat()
        val sx = imageWidth / shortSide
        val sy = imageHeight / shortSide
        val output = ArrayList<PhotoMark>(marks.size)
        for (mark in marks) {
            if (mark.kind == PhotoMarkKind.ERASER) {
                // Legacy fixed-screen eraser entries are kept for compatibility.
                output += mark
                continue
            }
            val curve = geometry(mark)
            if (curve.isEmpty()) continue
            val radius = (eraserThickness + mark.thickness) / 2f
            fun inside(p: PhotoPoint): Boolean {
                val px = p.x * sx
                val py = p.y * sy
                if (eraserPath.size == 1) {
                    val e = eraserPath.first()
                    return hypot(px - e.x * sx, py - e.y * sy) <= radius
                }
                for (i in 1 until eraserPath.size) {
                    val a = eraserPath[i - 1]
                    val b = eraserPath[i]
                    val ax = a.x * sx; val ay = a.y * sy
                    val vx = (b.x - a.x) * sx; val vy = (b.y - a.y) * sy
                    val t = if (vx * vx + vy * vy < 1e-10f) 0f
                        else (((px - ax) * vx + (py - ay) * vy) / (vx * vx + vy * vy)).coerceIn(0f, 1f)
                    if (hypot(px - ax - t * vx, py - ay - t * vy) <= radius) return true
                }
                return false
            }
            if (curve.none(::inside) && mark.points.none(::inside)) {
                // Do not fragment untouched shapes.
                output += mark
                continue
            }
            val pieces = mutableListOf<List<PhotoPoint>>()
            var current = mutableListOf<PhotoPoint>()
            fun flush() {
                if (current.isNotEmpty()) pieces += current.toList()
                current = mutableListOf()
            }
            for (point in curve) {
                if (inside(point)) flush()
                else {
                    if (current.size == 900) flush()
                    current += point
                }
            }
            flush()
            val kind = when (mark.kind) {
                PhotoMarkKind.PEN, PhotoMarkKind.MARKER, PhotoMarkKind.NEON -> mark.kind
                else -> PhotoMarkKind.PEN // Break vector outlines into erasable stroke pieces.
            }
            for (piece in pieces) {
                if (output.size >= PhotoDrawing.MAX_MARKS) break
                output += mark.copy(kind = kind, points = piece, rotationDegrees = 0f)
            }
        }
        return output.take(PhotoDrawing.MAX_MARKS)
    }

    private fun geometry(mark: PhotoMark): List<PhotoPoint> {
        val p = mark.points
        val first = p.first()
        val last = p.last()
        val vertices = when (mark.kind) {
            PhotoMarkKind.RECTANGLE -> listOf(first, PhotoPoint(last.x, first.y), last,
                PhotoPoint(first.x, last.y), first)
            PhotoMarkKind.OVAL -> {
                val cx = (first.x + last.x) / 2
                val cy = (first.y + last.y) / 2
                val rx = kotlin.math.abs(last.x - first.x) / 2
                val ry = kotlin.math.abs(last.y - first.y) / 2
                (0..96).map { i ->
                    val a = i / 96.0 * Math.PI * 2
                    PhotoDrawing.clamp((cx + rx * kotlin.math.cos(a)).toFloat(),
                        (cy + ry * kotlin.math.sin(a)).toFloat())
                }
            }
            else -> p
        }
        val out = ArrayList<PhotoPoint>(vertices.size)
        for (i in vertices.indices) {
            val to = vertices[i]
            if (i == 0) { out += to; continue }
            val from = vertices[i - 1]
            val steps = min(12, max(1, ceil(hypot(to.x - from.x, to.y - from.y) / .006f).toInt()))
            for (j in 1..steps) {
                val t = j.toFloat() / steps
                out += PhotoDrawing.clamp(from.x + (to.x - from.x) * t,
                    from.y + (to.y - from.y) * t)
            }
        }
        return out
    }
}
