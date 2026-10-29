package com.domenota.medialoader.data.editor

import android.graphics.Bitmap

/**
 * Preparation for the optional offline inpainting module.
 * No model, download or UI is activated in MediaLoader 1.6.8.
 */
data class MaskPoint(val x: Float, val y: Float) {
    init {
        require(x in 0f..1f && y in 0f..1f) { "Mask coordinates must be normalized" }
    }
}

data class MaskStroke(
    val points: List<MaskPoint>,
    /** Brush diameter as a fraction of the image's shorter side. */
    val diameter: Float,
    /** Removes this area from the current selection instead of adding to it. */
    val erase: Boolean = false,
) {
    init {
        require(points.isNotEmpty()) { "An empty stroke cannot select an object" }
        require(diameter > 0f && diameter <= 0.5f) { "Invalid brush size" }
        require(points.size <= 20_000) { "Stroke is too large" }
    }
}

data class InpaintingSettings(
    val maxWorkingSide: Int = 1024,
) {
    init { require(maxWorkingSide in 256..4096) }
}

interface ImageInpaintingEngine {
    /**
     * Only operate on a working copy. The editor remains responsible for preview,
     * confirmation, undo and saving to a NEW file without changing the source.
     * Implementations must be cancelable and must respect memory limits.
     */
    suspend fun inpaint(
        source: Bitmap,
        mask: List<MaskStroke>,
        settings: InpaintingSettings = InpaintingSettings(),
    ): Bitmap
}
