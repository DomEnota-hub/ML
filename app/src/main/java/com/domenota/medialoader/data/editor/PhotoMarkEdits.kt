package com.domenota.medialoader.data.editor

import android.graphics.RectF
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** All editing geometry is normalized, so preview and full-resolution export agree. */
object PhotoMarkEdits {
    fun bounds(mark: PhotoMark): RectF = RectF(
        mark.points.minOf { it.x }, mark.points.minOf { it.y },
        mark.points.maxOf { it.x }, mark.points.maxOf { it.y }
    )

    private fun distanceToSegment(p: PhotoPoint, a: PhotoPoint, b: PhotoPoint): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len2 = dx * dx + dy * dy
        val t = if (len2 < 1e-9f) 0f
            else (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).coerceIn(0f, 1f)
        return hypot(p.x - (a.x + dx * t), p.y - (a.y + dy * t))
    }

    private fun isFreehand(kind: PhotoMarkKind) =
        kind == PhotoMarkKind.PEN || kind == PhotoMarkKind.MARKER || kind == PhotoMarkKind.NEON

    fun hit(mark: PhotoMark, at: PhotoPoint, tolerance: Float = .025f): Boolean {
        val bounds = bounds(mark)
        if (!isFreehand(mark.kind) &&
            at.x >= bounds.left - tolerance && at.x <= bounds.right + tolerance &&
            at.y >= bounds.top - tolerance && at.y <= bounds.bottom + tolerance) return true
        if (mark.points.size == 1) {
            val p = mark.points[0]
            return hypot(at.x - p.x, at.y - p.y) < tolerance
        }
        return mark.points.zipWithNext().any { (a, b) ->
            distanceToSegment(at, a, b) <= tolerance
        }
    }

    fun pick(marks: List<PhotoMark>, at: PhotoPoint, tolerance: Float = .025f): Int =
        marks.indices.reversed().firstOrNull { hit(marks[it], at, tolerance) } ?: -1

    /** Move every point by an equal amount; clamp the whole group, not individual points. */
    fun move(mark: PhotoMark, dx: Float, dy: Float): PhotoMark {
        val b = bounds(mark)
        val safeDx = dx.coerceIn(-b.left, 1f - b.right)
        val safeDy = dy.coerceIn(-b.top, 1f - b.bottom)
        return mark.copy(points = mark.points.map {
            PhotoDrawing.clamp(it.x + safeDx, it.y + safeDy)
        })
    }

    fun resize(mark: PhotoMark, end: PhotoPoint): PhotoMark {
        if (isFreehand(mark.kind) || mark.points.size < 2) return mark
        return mark.copy(points = listOf(mark.points.first(), end))
    }

    fun rotate(mark: PhotoMark, degrees: Float): PhotoMark {
        if (isFreehand(mark.kind)) return mark
        var value = (mark.rotationDegrees + degrees) % 360f
        if (value > 180f) value -= 360f
        if (value < -180f) value += 360f
        return mark.copy(rotationDegrees = value)
    }

    /**
     * Erase only the touched section of a freehand path, preserving unaffected
     * fragments. Vector shapes are erased as a single editable object.
     */
    fun eraseNear(marks: List<PhotoMark>, at: PhotoPoint, radius: Float = .024f): List<PhotoMark> {
        val index = pick(marks, at, radius)
        if (index < 0) return marks
        val target = marks[index]
        if (!isFreehand(target.kind)) return marks.filterIndexed { i, _ -> i != index }
        val fragments = mutableListOf<PhotoMark>()
        var segment = mutableListOf<PhotoPoint>()
        target.points.forEachIndexed { i, point ->
            val touched = hypot(point.x - at.x, point.y - at.y) <= radius ||
                (i > 0 && distanceToSegment(at, target.points[i - 1], point) <= radius)
            if (touched) {
                if (segment.isNotEmpty()) {
                    fragments.add(target.copy(points = segment.toList()))
                    segment = mutableListOf()
                }
            } else {
                segment.add(point)
            }
        }
        if (segment.isNotEmpty()) fragments.add(target.copy(points = segment))
        return (marks.take(index) + fragments + marks.drop(index + 1)).take(PhotoDrawing.MAX_MARKS)
    }
}
