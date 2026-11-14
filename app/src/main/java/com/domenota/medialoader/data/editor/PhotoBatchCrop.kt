package com.domenota.medialoader.data.editor

import android.graphics.Bitmap
import android.graphics.Matrix

/** Keep free crop fractions exactly. Fixed ratios keep the relative zoom and
 * center of the reference crop, constrained to the target EXIF-oriented image. */
object PhotoBatchCrop {
    fun adapt(crop: CropFractions, preset: CropAspectPreset, referenceWidth: Int,
        referenceHeight: Int, width: Int, height: Int): CropFractions {
        require(width > 0 && height > 0)
        if (preset == CropAspectPreset.FREE || (referenceWidth == width && referenceHeight == height)) return crop
        val target = VideoCropMath.centerCrop(width,height,preset)
        val reference = if (referenceWidth > 0 && referenceHeight > 0)
            VideoCropMath.centerCrop(referenceWidth,referenceHeight,preset) else target
        val zoom = minOf(crop.keptWidth/reference.keptWidth,crop.keptHeight/reference.keptHeight)
            .coerceIn(.05f,1f)
        val w = (target.keptWidth * zoom).coerceIn(.050001f,1f)
        val h = (target.keptHeight * zoom).coerceIn(.050001f,1f)
        val cx = ((crop.left + 1f - crop.right)/2f).coerceIn(w/2f,1f-w/2f)
        val cy = ((crop.top + 1f - crop.bottom)/2f).coerceIn(h/2f,1f-h/2f)
        return CropFractions((cx-w/2f).coerceAtLeast(0f),(1f-cx-w/2f).coerceAtLeast(0f),
            (cy-h/2f).coerceAtLeast(0f),(1f-cy-h/2f).coerceAtLeast(0f))
    }
}

object PhotoSourceOrientation {
    fun dimensions(width:Int,height:Int,orientation:Int):Pair<Int,Int> =
        if (orientation in 5..8) height to width else width to height

    fun apply(source:Bitmap,orientation:Int):Bitmap {
        val matrix = Matrix().apply {
            when (orientation) {
                2 -> setScale(-1f,1f)
                3 -> setRotate(180f)
                4 -> setScale(1f,-1f)
                5 -> { setRotate(90f); postScale(-1f,1f) }
                6 -> setRotate(90f)
                7 -> { setRotate(-90f); postScale(-1f,1f) }
                8 -> setRotate(-90f)
            }
        }
        return if(matrix.isIdentity) source else Bitmap.createBitmap(source,0,0,source.width,source.height,matrix,true)
    }
}
