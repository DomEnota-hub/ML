package com.domenota.medialoader.data.editor

import kotlin.math.sqrt

/**
 * Aggregate, image-free diagnostics for distinguishing a huge inpainting
 * request from a flat/failed LaMa prediction. Never stores source pixels,
 * filenames, OCR text or content URIs.
 */
internal data class InpaintingDiagnostics(
    val sourceMaskFraction: Float,
    val modelMaskFraction: Float,
    val workingSideFraction: Float,
    val modelPredictionSpread: Float,
) {
    val largeArea: Boolean get() =
        sourceMaskFraction >= .30f || modelMaskFraction >= .50f
}

internal object InpaintingDiagnosticPolicy {
    fun analyze(
        sourceCoverage: IntArray, sourceWidth: Int, sourceHeight: Int,
        modelMask: FloatArray, workingSide: Int, prediction: FloatArray,
    ): InpaintingDiagnostics {
        require(sourceWidth > 0 && sourceHeight > 0)
        require(sourceCoverage.size == sourceWidth * sourceHeight)
        require(modelMask.isNotEmpty() && prediction.size == modelMask.size * 3)
        val selected = sourceCoverage.count { it > 0 }
        val modelSelected = modelMask.count { it >= .5f }
        val plane = modelMask.size
        var sum = 0.0
        var sumSquared = 0.0
        var count = 0
        for (i in 0 until plane) if (modelMask[i] >= .5f) {
            val gray = (prediction[i] + prediction[plane + i] +
                prediction[2 * plane + i]) / 3.0
            if (gray.isFinite()) {
                sum += gray
                sumSquared += gray * gray
                count++
            }
        }
        val variance = if (count < 2) 0.0 else
            (sumSquared / count - (sum / count) * (sum / count)).coerceAtLeast(0.0)
        return InpaintingDiagnostics(
            sourceMaskFraction = selected.toFloat() / sourceCoverage.size,
            modelMaskFraction = modelSelected.toFloat() / plane,
            workingSideFraction = workingSide.toFloat() / maxOf(sourceWidth, sourceHeight),
            modelPredictionSpread = sqrt(variance).toFloat(),
        )
    }
}
