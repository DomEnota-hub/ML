package com.domenota.medialoader.ui.screens.gallery

import android.graphics.Bitmap
import kotlin.math.ceil
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
    private val history = ArrayDeque<Map<Int, Int>>()
    private val redoHistory = ArrayDeque<Map<Int, Int>>()
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
            if (history.size >= 15) history.removeFirst()
            history.addLast(snapshot)
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
        val redoValues = HashMap<Int, Int>(change.size)
        change.forEach { (index, oldPixel) ->
            redoValues[index] = workingPixels[index]
            workingPixels[index] = oldPixel
        }
        if (redoHistory.size >= 15) redoHistory.removeFirst()
        redoHistory.addLast(redoValues)
        syncFull()
        return true
    }

    /** Replay the last undone stroke, retaining the ability to Undo again. */
    fun redo(): Boolean {
        endStroke()
        val change = redoHistory.removeLastOrNull() ?: return false
        val undoValues = HashMap<Int, Int>(change.size)
        change.forEach { (index, nextPixel) ->
            undoValues[index] = workingPixels[index]
            workingPixels[index] = nextPixel
        }
        if (history.size >= 15) history.removeFirst()
        history.addLast(undoValues)
        syncFull()
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
