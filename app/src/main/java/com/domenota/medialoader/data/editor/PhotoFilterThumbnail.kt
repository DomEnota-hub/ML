package com.domenota.medialoader.data.editor

import android.graphics.Bitmap
import kotlin.math.roundToInt

/** Shared thumbnail geometry for photo and video filter cards. No upscaling or square distortion. */
object PhotoFilterThumbnail {
    fun dimensions(width: Int, height: Int, longEdge: Int): Pair<Int, Int> {
        require(width > 0 && height > 0 && longEdge > 0)
        val scale = minOf(1.0, longEdge.toDouble() / maxOf(width, height))
        return (width * scale).roundToInt().coerceAtLeast(1) to
            (height * scale).roundToInt().coerceAtLeast(1)
    }

    fun render(source: Bitmap, effect: PhotoEffect, longEdge: Int): Bitmap {
        val (width, height) = dimensions(source.width, source.height, longEdge)
        val resized = Bitmap.createScaledBitmap(source, width, height, true)
        val sample = if (resized === source) source.copy(Bitmap.Config.ARGB_8888, false) else resized
        try {
            val rendered = PhotoColorMath.apply(sample, PhotoColorAdjustments(effect = effect))
            if (rendered !== sample) sample.recycle()
            return rendered
        } catch (error: Throwable) {
            sample.recycle()
            throw error
        }
    }
}
