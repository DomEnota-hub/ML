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
            val began = SystemClock.elapsedRealtime()
            val prepared = preparedCoverage(mask, width, height)
            val maskMs = SystemClock.elapsedRealtime() - began
            val coverage = prepared.data
            val region = InpaintingPreparation.region(
                coverage, width, height, settings.maxWorkingSide, prepared.bounds)
                ?: return@withContext source.copy(Bitmap.Config.ARGB_8888, false)
            val pixels = IntArray(width * height)
            source.getPixels(pixels, 0, width, 0, 0, width, height)
            val beforeInputs = SystemClock.elapsedRealtime()
            val inputs = InpaintingPreparation.inputs(pixels, coverage, width, height, region)
            currentCoroutineContext().ensureActive()
            val inputsMs = SystemClock.elapsedRealtime() - beforeInputs
            val preparationMs = SystemClock.elapsedRealtime() - began
            val inferenceStarted = SystemClock.elapsedRealtime()
            val prediction = runner.predict(inputs.image, inputs.mask)
            val inferenceMs = SystemClock.elapsedRealtime() - inferenceStarted
            currentCoroutineContext().ensureActive()
            // Aggregate diagnostics only, no image content or names in logs.
            // Large area and flat neural prediction can now be distinguished
            // from a wrong ROI/mask scale in Xiaomi device QA.
            val diagnostics = InpaintingDiagnosticPolicy.analyze(
                coverage, width, height, inputs.mask, region.size, prediction)
            val compositeStarted = SystemClock.elapsedRealtime()
            val modelResult = InpaintingPreparation.composite(
                pixels, coverage, width, height, region, prediction,
                bounds = prepared.bounds)
            currentCoroutineContext().ensureActive()
            val cancellationContext = currentCoroutineContext()
            val recovered = if (settings.recoverLargePersonOnUniformBackground && diagnostics.largeArea)
                LargePersonBackgroundRecovery.recover(pixels, coverage, width, height, modelResult) {
                    cancellationContext.ensureActive()
                } else null
            val result = recovered ?: modelResult
            val compositingMs = SystemClock.elapsedRealtime() - compositeStarted
            AppLog.i("AiOrtPerf", "model=LAMA size=${width}x$height " +
                "mask_ms=$maskMs input_sample_ms=$inputsMs " +
                "mask_preparation_ms=$preparationMs inference_ms=$inferenceMs " +
                "composite_ms=$compositingMs " +
                "mask_fraction=${diagnostics.sourceMaskFraction} " +
                "model_mask_fraction=${diagnostics.modelMaskFraction} " +
                "roi_side_fraction=${diagnostics.workingSideFraction} " +
                "prediction_spread=${diagnostics.modelPredictionSpread} " +
                "large_area=${diagnostics.largeArea} " +
                "uniform_background_recovery=${recovered != null} " +
                "total_ms=${SystemClock.elapsedRealtime() - began}")
            currentCoroutineContext().ensureActive()
            Bitmap.createBitmap(result, width, height, Bitmap.Config.ARGB_8888)
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

        internal data class PreparedCoverage(val data: IntArray, val bounds: PixelBounds)

        /**
         * Every circle and segment is contained, including CLEAR stroke width,
         * antialias fringe, 3px morphology and 1px feather. A conservatively
         * padded rectangle ensures the cropped raster is pixel-equivalent.
         */
        internal fun strokeBounds(strokes: List<MaskStroke>, width: Int, height: Int): PixelBounds {
            require(width > 0 && height > 0)
            var left = width.toFloat()
            var top = height.toFloat()
            var right = 0f
            var bottom = 0f
            val shortSide = minOf(width, height)
            for (stroke in strokes) {
                val actualWidth = (stroke.diameter * shortSide +
                    if (stroke.erase) 2f else 0f).coerceAtLeast(1f)
                val margin = actualWidth / 2f + 8f
                for (point in stroke.points) {
                    val x = point.x * width
                    val y = point.y * height
                    left = minOf(left, x - margin)
                    top = minOf(top, y - margin)
                    right = maxOf(right, x + margin)
                    bottom = maxOf(bottom, y + margin)
                }
            }
            if (right <= left || bottom <= top) return PixelBounds(0, 0, 0, 0)
            return PixelBounds(
                kotlin.math.floor(left.toDouble()).toInt().coerceIn(0, width),
                kotlin.math.floor(top.toDouble()).toInt().coerceIn(0, height),
                (kotlin.math.ceil(right.toDouble()).toInt() + 1).coerceIn(0, width),
                (kotlin.math.ceil(bottom.toDouble()).toInt() + 1).coerceIn(0, height),
            )
        }

        /** Same full-size result array; all rasterization and dilation use bbox. */
        internal fun preparedCoverage(
            strokes: List<MaskStroke>, width: Int, height: Int,
        ): PreparedCoverage {
            val bounds = strokeBounds(strokes, width, height)
            val data = IntArray(width * height)
            if (bounds.width <= 0 || bounds.height <= 0)
                return PreparedCoverage(data, bounds)
            // PorterDuff.CLEAR overlaps can have different raster-edge rounding
            // under a translated crop on native Skia. Preserve the exact old
            // full-frame raster for eraser batches; still bound ROI/composition.
            if (strokes.any { it.erase }) {
                val full = rasterizeMask(strokes, width, height)
                try {
                    val pixels = IntArray(data.size)
                    full.getPixels(pixels, 0, width, 0, 0, width, height)
                    return PreparedCoverage(InpaintingPreparation.coverage(
                        IntArray(pixels.size) { Color.alpha(pixels[it]) }, width, height), bounds)
                } finally {
                    full.recycle()
                }
            }
            val crop = Bitmap.createBitmap(bounds.width, bounds.height, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(crop)
                canvas.translate(-bounds.left.toFloat(), -bounds.top.toFloat())
                drawStrokes(canvas, strokes, width, height)
                val pixels = IntArray(bounds.width * bounds.height)
                crop.getPixels(pixels, 0, bounds.width, 0, 0,
                    bounds.width, bounds.height)
                val local = InpaintingPreparation.coverage(
                    IntArray(pixels.size) { Color.alpha(pixels[it]) },
                    bounds.width, bounds.height, radiusBasis = minOf(width, height))
                for (y in 0 until bounds.height) {
                    local.copyInto(data, (bounds.top + y) * width + bounds.left,
                        y * bounds.width, (y + 1) * bounds.width)
                }
                return PreparedCoverage(data, bounds)
            } finally {
                crop.recycle()
            }
        }

        /** The edited pixels already contain feathering: never apply that alpha twice. */
        fun makeOverlay(edited: Bitmap, batches: List<List<MaskStroke>>): Bitmap {
            val width = edited.width; val height = edited.height
            val selected = BooleanArray(width * height)
            for (strokes in batches) {
                val prepared = preparedCoverage(strokes, width, height)
                val b = prepared.bounds
                for (y in b.top until b.bottom) for (x in b.left until b.right) {
                    if (prepared.data[y * width + x] > 0) selected[y * width + x] = true
                }
            }
            val pixels = IntArray(width * height)
            edited.getPixels(pixels, 0, width, 0, 0, width, height)
            for (i in pixels.indices) if (!selected[i]) pixels[i] = 0
            return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        }
    }
}
