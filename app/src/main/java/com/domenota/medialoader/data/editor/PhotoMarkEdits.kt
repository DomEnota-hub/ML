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
     * Split freehand strokes along the *actual eraser footprint*, sampling
     * sparse path segments first so long strokes are not deleted as a unit.
     * Shapes remain individually editable objects and can be removed by touch.
     */
    fun eraseNear(marks: List<PhotoMark>, at: PhotoPoint, radius: Float = .024f): List<PhotoMark> {
        if (radius <= 0f || !radius.isFinite()) return marks
        val index = marks.indices.reversed().firstOrNull { i ->
            val mark = marks[i]
            if (isFreehand(mark.kind)) hit(mark, at, radius + mark.thickness * .5f)
            else hit(mark, at, radius)
        } ?: return marks
        val target = marks[index]
        if (!isFreehand(target.kind)) return marks.filterIndexed { i, _ -> i != index }

        val threshold = radius + target.thickness * .5f
        val sampled = mutableListOf<PhotoPoint>()
        val original = target.points
        sampled.add(original.first())
        for (i in 1 until original.size) {
            val a = original[i - 1]; val b = original[i]
            val length = hypot(b.x - a.x, b.y - a.y)
            val pieces = kotlin.math.ceil(length / (threshold * .33f).coerceAtLeast(.002f))
                .toInt().coerceIn(1, 32)
            for (part in 1..pieces) {
                val t = part.toFloat() / pieces
                sampled.add(PhotoDrawing.clamp(
                    a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t))
            }
        }
        val fragments = mutableListOf<PhotoMark>()
        var segment = mutableListOf<PhotoPoint>()
        var touched = false
        for (point in sampled) {
            if (hypot(point.x - at.x, point.y - at.y) <= threshold) {
                touched = true
                if (segment.isNotEmpty()) {
                    fragments.add(target.copy(points = segment.take(900)))
                    segment = mutableListOf()
                }
            } else {
                if (segment.size < 900) segment.add(point)
                else {
                    fragments.add(target.copy(points = segment))
                    segment = mutableListOf(point)
                }
            }
        }
        if (segment.isNotEmpty()) fragments.add(target.copy(points = segment))
        if (!touched) return marks
        // Never discard unrelated marks just because the split would exceed
        // the vector document limit.
        if (marks.size - 1 + fragments.size > PhotoDrawing.MAX_MARKS) return marks
        return marks.take(index) + fragments + marks.drop(index + 1)
    }
}
