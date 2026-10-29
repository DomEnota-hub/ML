package com.domenota.medialoader.data.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
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
            val coverage = preparedCoverage(mask, width, height)
            val region = InpaintingPreparation.region(coverage, width, height, settings.maxWorkingSide)
                ?: return@withContext source.copy(Bitmap.Config.ARGB_8888, false)
            val pixels = IntArray(width * height)
            source.getPixels(pixels, 0, width, 0, 0, width, height)
            val inputs = InpaintingPreparation.inputs(pixels, coverage, width, height, region)
            currentCoroutineContext().ensureActive()
            val prediction = runner.predict(inputs.image, inputs.mask)
            currentCoroutineContext().ensureActive()
            val result = InpaintingPreparation.composite(pixels, coverage, width, height, region, prediction)
            currentCoroutineContext().ensureActive()
            Bitmap.createBitmap(result, width, height, Bitmap.Config.ARGB_8888)
        }

    companion object {
        const val SIZE = 512

        /** Coordinates are image-relative, independent of pan/zoom/viewport pixels. */
        fun rasterizeMask(strokes: List<MaskStroke>, width: Int, height: Int): Bitmap {
            val mask = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(mask)
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
            return mask
        }

        private fun preparedCoverage(strokes: List<MaskStroke>, width: Int, height: Int): IntArray {
            val mask = rasterizeMask(strokes, width, height)
            try {
                val pixels = IntArray(width * height)
                mask.getPixels(pixels, 0, width, 0, 0, width, height)
                return InpaintingPreparation.coverage(IntArray(pixels.size) { Color.alpha(pixels[it]) }, width, height)
            } finally { mask.recycle() }
        }

        /** The edited pixels already contain feathering: never apply that alpha twice. */
        fun makeOverlay(edited: Bitmap, batches: List<List<MaskStroke>>): Bitmap {
            val width = edited.width; val height = edited.height
            val selected = BooleanArray(width * height)
            for (strokes in batches) {
                val coverage = preparedCoverage(strokes, width, height)
                for (i in selected.indices) if (coverage[i] > 0) selected[i] = true
            }
            val pixels = IntArray(width * height)
            edited.getPixels(pixels, 0, width, 0, 0, width, height)
            for (i in pixels.indices) if (!selected[i]) pixels[i] = 0
            return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        }
    }
}
