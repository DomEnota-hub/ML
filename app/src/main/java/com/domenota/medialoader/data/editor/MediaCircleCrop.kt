package com.domenota.medialoader.data.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.min

/** Rectangle cropping remains destructive. Circle is a black-backed mask in the ORIGINAL canvas. */
enum class MediaCropShape { RECTANGLE, CIRCLE }

data class CircleCropRegion(val centerX: Float, val centerY: Float, val radius: Float) {
    init { require(centerX.isFinite() && centerY.isFinite() && radius.isFinite() && radius > 0f) }
}

/** Fractions describe the draggable bounding square, not discarded media.
 * Radius is measured in pixels so a portrait frame still contains a real circle. */
object MediaCircleCrop {
    fun region(width: Int, height: Int, crop: CropFractions): CircleCropRegion {
        require(width > 0 && height > 0)
        val keptWidth = (1f - crop.left - crop.right) * width
        val keptHeight = (1f - crop.top - crop.bottom) * height
        require(keptWidth > 0f && keptHeight > 0f)
        return CircleCropRegion(
            (crop.left + (1f - crop.left - crop.right) * .5f) * width,
            (crop.top + (1f - crop.top - crop.bottom) * .5f) * height,
            min(keptWidth, keptHeight) * .5f,
        )
    }

    /** Draw black beyond the circle; do not change the rectangular output dimensions.
     * Source is caller-owned, and a new bitmap is always returned. */
    fun apply(source: Bitmap, crop: CropFractions): Bitmap {
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(output)
            canvas.drawColor(Color.BLACK)
            val circle = region(source.width, source.height, crop)
            val outline = Path().apply {
                addCircle(circle.centerX,circle.centerY,circle.radius,Path.Direction.CW)
            }
            canvas.save()
            canvas.clipPath(outline)
            canvas.drawBitmap(source,0f,0f,Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            canvas.restore()
            return output
        } catch (error: Throwable) {
            output.recycle()
            throw error
        }
    }
}
