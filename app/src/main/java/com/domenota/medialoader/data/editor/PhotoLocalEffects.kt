package com.domenota.medialoader.data.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.min
import kotlin.math.max
import kotlin.math.roundToInt

/** Persist only normalized geometry/parameters, never a preview-resolution bitmap. */
enum class PhotoLocalEffectKind(val title: String) {
    SQUARE("Мозаика"), STAGGERED("Геометрия"), BLUR("Размытие")
}
enum class PhotoLocalSelection(val title: String) {
    BRUSH("Кисть"), RECTANGLE("Прямоугольник"), OVAL("Овал")
}
data class PhotoLocalStroke(
    val kind: PhotoLocalEffectKind,
    val selection: PhotoLocalSelection,
    val points: List<PhotoPoint>,
    /** Diameter relative to the shorter edge of the edited photo. */
    val brushFraction: Float = .065f,
    /** Size of mosaic pixels / blur kernel relative to shorter edge. */
    val intensity: Float = .025f,
) {
    init {
        require(points.isNotEmpty() && points.size <= 700)
        require(brushFraction.isFinite() && brushFraction in .005f..0.30f)
        require(intensity.isFinite() && intensity in .005f..0.08f)
        require(selection == PhotoLocalSelection.BRUSH || points.size >= 2)
    }
}

/**
 * Non-destructive effect renderer. Previews and full resolution export call
 * exactly the same method, with masks defined in normalized photo coordinates.
 * Later strokes are applied after earlier ones, including overlapping areas.
 */
object PhotoLocalEffects {
    const val MAX_STROKES = 100

    fun maskPath(stroke: PhotoLocalStroke, width: Int, height: Int): Path {
        require(width > 0 && height > 0)
        val first = stroke.points.first()
        val x1 = first.x * width
        val y1 = first.y * height
        val path = Path()
        if (stroke.selection == PhotoLocalSelection.BRUSH) {
            val radius = min(width, height) * stroke.brushFraction / 2f
            if (stroke.points.size == 1) path.addCircle(x1, y1, radius, Path.Direction.CW)
            else {
                val line = Path().apply {
                    moveTo(x1, y1)
                    stroke.points.drop(1).forEach { lineTo(it.x * width, it.y * height) }
                }
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                    strokeWidth = radius * 2f
                }.getFillPath(line, path)
            }
        } else {
            val last = stroke.points.last()
            val bounds = RectF(min(x1, last.x * width), min(y1, last.y * height),
                max(x1, last.x * width), max(y1, last.y * height))
            if (stroke.selection == PhotoLocalSelection.RECTANGLE)
                path.addRect(bounds, Path.Direction.CW)
            else path.addOval(bounds, Path.Direction.CW)
        }
        return path
    }

    fun apply(source: Bitmap, strokes: List<PhotoLocalStroke>): Bitmap {
        require(strokes.size <= MAX_STROKES)
        if (strokes.isEmpty()) return source
        require(source.width > 0 && source.height > 0)
        val result = source.copy(Bitmap.Config.ARGB_8888, true)
            ?: throw IllegalStateException("Не удалось создать рабочее изображение.")
        try {
            val canvas = Canvas(result)
            strokes.forEach { stroke ->
                val clipped = canvas.save()
                try {
                    canvas.clipPath(maskPath(stroke, result.width, result.height))
                    val size = (min(result.width, result.height) * stroke.intensity)
                        .roundToInt().coerceAtLeast(3)
                    when (stroke.kind) {
                        PhotoLocalEffectKind.SQUARE -> {
                            val miniature = Bitmap.createScaledBitmap(result,
                                max(1, result.width / size), max(1, result.height / size), false)
                            try {
                                canvas.drawBitmap(miniature, null,
                                    Rect(0, 0, result.width, result.height),
                                    Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = false })
                            } finally { if (miniature !== result) miniature.recycle() }
                        }
                        PhotoLocalEffectKind.STAGGERED -> {
                            // Staggered cells are sampled BEFORE painting; no accidental
                            // feedback from cells already modified during this stroke.
                            val cols = max(1, result.width / size)
                            val rows = max(1, result.height / size)
                            val samples = Bitmap.createScaledBitmap(result, cols, rows, false)
                            try {
                                val paint = Paint().apply { style = Paint.Style.FILL }
                                for (y in 0 until rows) {
                                    val top = (y.toFloat() / rows * result.height)
                                    val bottom = ((y + 1f) / rows * result.height)
                                    val step = result.width.toFloat() / cols
                                    val shift = if (y % 2 == 1) step * .5f else 0f
                                    for (x in -1..cols) {
                                        paint.color = samples.getPixel(
                                            x.coerceIn(0, cols - 1), y)
                                        canvas.drawRect(x * step - shift, top,
                                            (x + 1) * step - shift, bottom, paint)
                                    }
                                }
                            } finally { if (samples !== result) samples.recycle() }
                        }
                        PhotoLocalEffectKind.BLUR -> {
                            // Blur on a capped working resolution, then bilinear-upscale.
                            // Two separable box passes approximate a soft Gaussian blur
                            // without allocating several full-size Android bitmaps.
                            val scale = min(1f, 640f / max(result.width, result.height))
                            val w = max(2, (result.width * scale).roundToInt())
                            val h = max(2, (result.height * scale).roundToInt())
                            // Always allocate an independent mutable bitmap, even
                            // when the source itself is smaller than 640px.
                            val small = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            Canvas(small).drawBitmap(result, null, Rect(0, 0, w, h),
                                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                            try {
                                val radius = max(1, (size * scale * 0.7f).roundToInt())
                                val pixels = IntArray(w * h)
                                small.getPixels(pixels, 0, w, 0, 0, w, h)
                                val buffer = IntArray(pixels.size)
                                repeat(2) {
                                    boxPass(pixels, buffer, w, h, radius, horizontal = true)
                                    boxPass(buffer, pixels, w, h, radius, horizontal = false)
                                }
                                small.setPixels(pixels, 0, w, 0, 0, w, h)
                                canvas.drawBitmap(small, null,
                                    Rect(0, 0, result.width, result.height),
                                    Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                            } finally { if (small !== result) small.recycle() }
                        }
                    }
                } finally { canvas.restoreToCount(clipped) }
            }
            return result
        } catch (error: Throwable) {
            result.recycle()
            throw error
        }
    }

    // Sliding window: O(width × height), independent of blur radius.
    private fun boxPass(input: IntArray, output: IntArray, w: Int, h: Int,
        radius: Int, horizontal: Boolean) {
        val longSide = if (horizontal) w else h
        val shortSide = if (horizontal) h else w
        val r = radius.coerceIn(1, longSide)
        fun index(primary: Int, secondary: Int) =
            if (horizontal) secondary * w + primary else primary * w + secondary
        for (s in 0 until shortSide) {
            var a = 0L; var red = 0L; var green = 0L; var blue = 0L
            fun add(pos: Int, mul: Int) {
                val color = input[index(pos.coerceIn(0, longSide - 1), s)]
                a += ((color ushr 24) and 255).toLong() * mul
                red += ((color ushr 16) and 255).toLong() * mul
                green += ((color ushr 8) and 255).toLong() * mul
                blue += (color and 255).toLong() * mul
            }
            for (d in -r..r) add(d, 1)
            val n = 2 * r + 1
            for (p in 0 until longSide) {
                output[index(p, s)] =
                    ((a / n).toInt() shl 24) or ((red / n).toInt() shl 16) or
                    ((green / n).toInt() shl 8) or (blue / n).toInt()
                add(p - r, -1); add(p + r + 1, 1)
            }
        }
    }
}
