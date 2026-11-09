package com.domenota.medialoader.data.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.os.SystemClock
import com.domenota.medialoader.core.logging.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Local contextual inpainting. Image and mask use one uniform source-space transform. */
class LaMaInpaintingEngine internal constructor(private val runner: InpaintingModelRunner) : ImageInpaintingEngine {
    constructor(models: LaMaModelStore) : this(LaMaOnnxRunner(models))

    override suspend fun inpaint(source: Bitmap, mask: List<MaskStroke>, settings: InpaintingSettings): Bitmap =
        withContext(Dispatchers.Default) {
            require(mask.isNotEmpty()) { "Сначала закрасьте объект кистью." }
            currentCoroutineContext().ensureActive()
            val width = source.width; val height = source.height
            val startedNs = SystemClock.elapsedRealtimeNanos()
            val prepared = preparedCoverage(mask, width, height)
            val maskNs = SystemClock.elapsedRealtimeNanos()
            val coverage = prepared.data
            val region = InpaintingPreparation.region(
                coverage, width, height, settings.maxWorkingSide, prepared.bounds)
                ?: return@withContext source.copy(Bitmap.Config.ARGB_8888, false)
            val pixels = IntArray(width * height)
            source.getPixels(pixels, 0, width, 0, 0, width, height)
            val inputs = InpaintingPreparation.inputs(pixels, coverage, width, height, region)
            val inputsNs = SystemClock.elapsedRealtimeNanos()
            currentCoroutineContext().ensureActive()
            val prediction = runner.predict(inputs.image, inputs.mask)
            val inferenceNs = SystemClock.elapsedRealtimeNanos()
            currentCoroutineContext().ensureActive()
            val result = InpaintingPreparation.composite(
                pixels, coverage, width, height, region, prediction, bounds = prepared.bounds)
            val compositeNs = SystemClock.elapsedRealtimeNanos()
            currentCoroutineContext().ensureActive()
            val output = Bitmap.createBitmap(result, width, height, Bitmap.Config.ARGB_8888)
            fun ms(a: Long, b: Long) = (b - a) / 1_000_000
            AppLog.i("LaMaPerf",
                "maskMs=${ms(startedNs, maskNs)} inputMs=${ms(maskNs, inputsNs)} " +
                "inferenceMs=${ms(inputsNs, inferenceNs)} " +
                "compositeMs=${ms(inferenceNs, compositeNs)} " +
                "bboxPx=${prepared.bounds.width}x${prepared.bounds.height} " +
                "photoPx=${width}x$height")
            output
        }

    companion object {
        const val SIZE = 512

        /** Coordinates are image-relative, independent of pan/zoom/viewport pixels. */
        fun rasterizeMask(strokes: List<MaskStroke>, width: Int, height: Int): Bitmap {
            val mask = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            drawStrokes(Canvas(mask), strokes, width, height)
            return mask
        }

        private fun drawStrokes(canvas: Canvas, strokes: List<MaskStroke>, width: Int, height: Int) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND
                style = Paint.Style.STROKE
            }
            for (stroke in strokes) {
                p.xfermode = if (stroke.erase) PorterDuffXfermode(PorterDuff.Mode.CLEAR) else null
                // Partial CLEAR leaves a*(1-a) antialias residues, which then
                // become selected again after binary thresholding/dilation.
                p.isAntiAlias = !stroke.erase
                p.strokeWidth = (stroke.diameter * minOf(width, height) + if (stroke.erase) 2f else 0f).coerceAtLeast(1f)
                var last = stroke.points.first()
                p.style = Paint.Style.FILL
                canvas.drawCircle(last.x * width, last.y * height, p.strokeWidth / 2f, p)
                p.style = Paint.Style.STROKE
                for (point in stroke.points.drop(1)) {
                    canvas.drawLine(last.x * width, last.y * height,
                        point.x * width, point.y * height, p)
                    last = point
                }
            }
        }

        /** Source-pixel bounds conservatively include the stroke radius and dilation. */
        internal data class PreparedCoverage(val data: IntArray, val bounds: PixelBounds)

        internal fun strokeBounds(strokes: List<MaskStroke>, width: Int, height: Int): PixelBounds {
            require(strokes.isNotEmpty())
            var left = Float.MAX_VALUE; var top = Float.MAX_VALUE
            var right = -Float.MAX_VALUE; var bottom = -Float.MAX_VALUE
            var reach = 0f
            for (stroke in strokes) {
                reach = maxOf(reach,
                    (stroke.diameter * minOf(width, height) + if (stroke.erase) 2f else 0f)
                        .coerceAtLeast(1f) / 2f)
                for (point in stroke.points) {
                    left = minOf(left, point.x * width); right = maxOf(right, point.x * width)
                    top = minOf(top, point.y * height); bottom = maxOf(bottom, point.y * height)
                }
            }
            // Antialias fringe, up-to-3px dilation, feather ring and a safety pad.
            val margin = kotlin.math.ceil(reach).toInt() + 8
            return PixelBounds(
                (left.toInt() - margin).coerceIn(0, width),
                (top.toInt() - margin).coerceIn(0, height),
                (right.toInt() + 1 + margin).coerceIn(0, width),
                (bottom.toInt() + 1 + margin).coerceIn(0, height))
        }

        /** Keeps the full-size output for compatibility; only raster/blur work is cropped. */
        internal fun preparedCoverage(strokes: List<MaskStroke>, width: Int, height: Int): PreparedCoverage {
            val bounds = strokeBounds(strokes, width, height)
            val data = IntArray(width * height)
            if (bounds.width <= 0 || bounds.height <= 0)
                return PreparedCoverage(data, bounds)
            val crop = Bitmap.createBitmap(bounds.width, bounds.height, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(crop)
                canvas.translate(-bounds.left.toFloat(), -bounds.top.toFloat())
                drawStrokes(canvas, strokes, width, height)
                val croppedPixels = IntArray(bounds.width * bounds.height)
                crop.getPixels(croppedPixels, 0, bounds.width, 0, 0, bounds.width, bounds.height)
                val local = InpaintingPreparation.coverage(
                    IntArray(croppedPixels.size) { Color.alpha(croppedPixels[it]) },
                    bounds.width, bounds.height, radiusBasis = minOf(width, height))
                for (y in 0 until bounds.height) {
                    local.copyInto(data, (bounds.top + y) * width + bounds.left,
                        y * bounds.width, (y + 1) * bounds.width)
                }
            } finally { crop.recycle() }
            return PreparedCoverage(data, bounds)
        }

        /** The edited pixels already contain feathering: never apply that alpha twice. */
        fun makeOverlay(edited: Bitmap, batches: List<List<MaskStroke>>): Bitmap {
            val width = edited.width; val height = edited.height
            val selected = BooleanArray(width * height)
            for (strokes in batches) {
                val prepared = preparedCoverage(strokes, width, height)
                val b = prepared.bounds
                for (y in b.top until b.bottom) for (x in b.left until b.right) {
                    if (prepared.data[y * width + x] > 0)
                        selected[y * width + x] = true
                }
            }
            val pixels = IntArray(width * height)
            edited.getPixels(pixels, 0, width, 0, 0, width, height)
            for (i in pixels.indices) if (!selected[i]) pixels[i] = 0
            return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        }
    }
}
