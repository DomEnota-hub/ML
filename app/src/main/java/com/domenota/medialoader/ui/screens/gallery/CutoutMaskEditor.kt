package com.domenota.medialoader.ui.screens.gallery

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Non-destructive manual correction of the foreground alpha mask.
 *
 * Eraser removes unwanted pixels; Restore takes color and original alpha from
 * the source photo, bringing back details missed by segmentation. Each drag is
 * one undoable edit; no photos are written or sent to a server.
 */
internal class CutoutMaskEditor(source: Bitmap, cutout: Bitmap) {
    val width = cutout.width
    val height = cutout.height
    /** Borrowed read-only original; never export this orientation aid as PNG. */
    val sourceReference: Bitmap = source
    private val sourcePixels = IntArray(width * height)
    private val initialPixels = IntArray(width * height)
    private val workingPixels = IntArray(width * height)
    // Full-region deltas avoid per-pixel HashMap allocations when filling a large area.
    private sealed class Edit {
        data class Brush(val old: Map<Int, Int>) : Edit()
        data class Region(val left: Int, val top: Int, val width: Int, val height: Int,
            val old: IntArray) : Edit()
    }
    private val history = ArrayDeque<Edit>()
    private val redoHistory = ArrayDeque<Edit>()
    private var current: MutableMap<Int, Int>? = null
    val preview: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

    init {
        require(source.width == width && source.height == height) { "Размер исходника и маски различается" }
        source.getPixels(sourcePixels, 0, width, 0, 0, width, height)
        cutout.getPixels(initialPixels, 0, width, 0, 0, width, height)
        initialPixels.copyInto(workingPixels)
        syncFull()
    }

    val canUndo: Boolean get() = history.isNotEmpty() || !current.isNullOrEmpty()
    val canRedo: Boolean get() = redoHistory.isNotEmpty()

    fun startStroke(x: Float, y: Float, radius: Float, restore: Boolean) {
        endStroke()
        current = HashMap()
        dab(x, y, radius, restore)
    }

    fun dragStroke(fromX: Float, fromY: Float, toX: Float, toY: Float, radius: Float, restore: Boolean) {
        if (current == null) current = HashMap()
        val gap = max(1.5f, radius * .3f)
        val steps = ceil(hypot(toX - fromX, toY - fromY) / gap).toInt().coerceIn(1, 256)
        for (i in 1..steps) {
            val t = i.toFloat() / steps
            dab(fromX + (toX - fromX) * t, fromY + (toY - fromY) * t, radius, restore)
        }
    }

    fun endStroke() {
        val snapshot = current
        current = null
        if (!snapshot.isNullOrEmpty()) {
            // A new committed paint operation creates a new timeline.
            redoHistory.clear()
            addHistory(Edit.Brush(snapshot))
        }
    }

    /** Aborts a stroke when a second finger appears; multi-touch must never edit alpha. */
    fun cancelStroke() {
        current?.forEach { (index, oldPixel) -> workingPixels[index] = oldPixel }
        current = null
        syncFull()
    }

    fun undo(): Boolean {
        endStroke()
        val change = history.removeLastOrNull() ?: return false
        redoHistory.addLast(swap(change))
        if (redoHistory.size > 15) redoHistory.removeFirst()
        syncFull()
        return true
    }

    /** Both freehand and magnetic edits share one exact Undo/Redo timeline. */
    fun redo(): Boolean {
        endStroke()
        val change = redoHistory.removeLastOrNull() ?: return false
        history.addLast(swap(change))
        if (history.size > 15) history.removeFirst()
        syncFull()
        return true
    }

    private fun swap(edit: Edit): Edit = when (edit) {
        is Edit.Brush -> {
            val opposite = HashMap<Int, Int>(edit.old.size)
            edit.old.forEach { (index, oldPixel) ->
                opposite[index] = workingPixels[index]
                workingPixels[index] = oldPixel
            }
            Edit.Brush(opposite)
        }
        is Edit.Region -> {
            val other = IntArray(edit.width * edit.height)
            for (row in 0 until edit.height) {
                val index = (edit.top + row) * width + edit.left
                workingPixels.copyInto(other, row * edit.width, index, index + edit.width)
                edit.old.copyInto(workingPixels, index, row * edit.width,
                    (row + 1) * edit.width)
            }
            edit.copy(old = other)
        }
    }

    private fun addHistory(edit: Edit) {
        redoHistory.clear()
        history.addLast(edit)
        // Bound Undo memory: one full-image polygon edit is about 10 MB at 1600px.
        fun bytes(value: Edit): Long = when (value) {
            is Edit.Brush -> value.old.size * 24L
            is Edit.Region -> value.old.size * 4L
        }
        var used = history.sumOf(::bytes)
        while (history.size > 1 && (history.size > 15 || used > 48L * 1024 * 1024)) {
            used -= bytes(history.removeFirst())
        }
    }

    /** A narrow, contour-following correction instead of circular hand painting. */
    fun applyPolyline(points: List<Offset>, lineWidth: Float, restore: Boolean): Boolean {
        if (points.size < 2 || points.any { !it.x.isFinite() || !it.y.isFinite() }) return false
        endStroke()
        val radius = (lineWidth / 2f).coerceIn(1f, 16f)
        startStroke(points.first().x, points.first().y, radius, restore)
        for (i in 1 until points.size) {
            dragStroke(points[i - 1].x, points[i - 1].y, points[i].x, points[i].y, radius, restore)
        }
        val changed = !current.isNullOrEmpty()
        endStroke()
        return changed
    }

    /** Closed magnetic selection. Scanline fill in one compact, undoable transaction. */
    fun applyPolygon(points: List<Offset>, restore: Boolean): Boolean {
        if (points.size < 3 || points.any { !it.x.isFinite() || !it.y.isFinite() }) return false
        val twiceArea = points.indices.sumOf { index ->
            val p = points[index]
            val q = points[(index + 1) % points.size]
            (p.x.toDouble() * q.y - q.x.toDouble() * p.y)
        }
        if (kotlin.math.abs(twiceArea) < 8.0) return false
        endStroke()
        val x0 = floor(points.minOf { it.x }).toInt().coerceIn(0, width - 1)
        val x1 = ceil(points.maxOf { it.x }).toInt().coerceIn(0, width - 1)
        val y0 = floor(points.minOf { it.y }).toInt().coerceIn(0, height - 1)
        val y1 = ceil(points.maxOf { it.y }).toInt().coerceIn(0, height - 1)
        if (x0 >= x1 || y0 >= y1) return false
        val rectWidth = x1 - x0 + 1
        val rectHeight = y1 - y0 + 1
        val before = IntArray(rectWidth * rectHeight)
        for (row in 0 until rectHeight) {
            val base = (y0 + row) * width + x0
            workingPixels.copyInto(before, row * rectWidth, base, base + rectWidth)
        }
        var changed = false
        val intersections = ArrayList<Float>(min(points.size, 64))
        for (y in y0..y1) {
            intersections.clear()
            val scan = y + .5f
            for (i in points.indices) {
                val a = points[i]; val b = points[(i + 1) % points.size]
                if ((a.y <= scan && b.y > scan) || (b.y <= scan && a.y > scan)) {
                    val fraction = (scan - a.y) / (b.y - a.y)
                    intersections += a.x + fraction * (b.x - a.x)
                }
            }
            intersections.sort()
            for (j in 0 until intersections.size - 1 step 2) {
                val from = ceil(intersections[j] - .5f).toInt().coerceIn(x0, x1 + 1)
                val to = floor(intersections[j + 1] - .5f).toInt().coerceIn(x0 - 1, x1)
                if (from > to) continue
                for (x in from..to) {
                    val idx = y * width + x
                    val old = workingPixels[idx]
                    val targetAlpha = if (restore) sourcePixels[idx] ushr 24 else 0
                    val next = (targetAlpha shl 24) or (sourcePixels[idx] and 0x00ffffff)
                    if (old != next) {
                        workingPixels[idx] = next
                        changed = true
                    }
                }
            }
        }
        if (!changed) return false
        preview.setPixels(workingPixels, y0 * width + x0, width,
            x0, y0, rectWidth, rectHeight)
        addHistory(Edit.Region(x0, y0, rectWidth, rectHeight, before))
        return true
    }

    fun reset() {
        current = null
        history.clear()
        redoHistory.clear()
        initialPixels.copyInto(workingPixels)
        syncFull()
    }

    fun snapshot(): Bitmap = preview.copy(Bitmap.Config.ARGB_8888, false)

    fun release() { if (!preview.isRecycled) preview.recycle() }

    private fun dab(x: Float, y: Float, radius: Float, restore: Boolean) {
        if (!x.isFinite() || !y.isFinite()) return
        val r = radius.coerceIn(1f, min(width, height).toFloat())
        val left = max(0, (x - r).toInt())
        val right = min(width - 1, (x + r).toInt())
        val top = max(0, (y - r).toInt())
        val bottom = min(height - 1, (y + r).toInt())
        if (left > right || top > bottom) return
        for (py in top..bottom) {
            for (px in left..right) {
                val distance = hypot(px + .5f - x, py + .5f - y)
                if (distance >= r) continue
                // Soft edge preserves hair and fine detail at brush boundaries.
                val strength = if (distance < r * .72f) 1f
                    else ((r - distance) / (r * .28f)).coerceIn(0f, 1f)
                val index = py * width + px
                val old = workingPixels[index]
                val oldAlpha = old ushr 24
                val sourceAlpha = sourcePixels[index] ushr 24
                val alpha = if (restore)
                    (oldAlpha + (sourceAlpha - oldAlpha).coerceAtLeast(0) * strength).roundToInt()
                else (oldAlpha * (1f - strength)).roundToInt()
                val newPixel = (alpha.coerceIn(0, 255) shl 24) or
                    (sourcePixels[index] and 0x00ffffff)
                if (newPixel != old) {
                    current?.putIfAbsent(index, old)
                    workingPixels[index] = newPixel
                }
            }
        }
        // Only update the changed rectangle; dragging does not re-upload a whole image.
        preview.setPixels(workingPixels, top * width + left, width,
            left, top, right - left + 1, bottom - top + 1)
    }

    private fun syncFull() {
        preview.setPixels(workingPixels, 0, width, 0, 0, width, height)
    }
}
