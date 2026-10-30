package com.domenota.medialoader.data.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import kotlin.math.max
import kotlin.math.roundToInt

/** A document selected by the Android system picker. Non-destructive until export. */
data class PhotoGraphicWatermark(
    val uri: String,
    val centerX: Float = .78f,
    val centerY: Float = .85f,
    /** Relative to final image width, independent of phone density. */
    val relativeWidth: Float = .25f,
    val opacity: Float = .7f,
) {
    init {
        require(Uri.parse(uri).scheme == "content")
        require(centerX.isFinite() && centerX in 0f..1f)
        require(centerY.isFinite() && centerY in 0f..1f)
        require(relativeWidth.isFinite() && relativeWidth in .04f..0.90f)
        require(opacity.isFinite() && opacity in .05f..1f)
    }
}

internal fun photoWatermarkBounds(
    width: Int, height: Int, markWidth: Int, markHeight: Int,
    graphic: PhotoGraphicWatermark,
): RectF {
    require(width > 0 && height > 0 && markWidth > 0 && markHeight > 0)
    val w = width * graphic.relativeWidth
    val h = w * markHeight.toFloat() / markWidth
    // Clamp the center so the watermark stays inside the output whenever it can fit.
    val centerX = (graphic.centerX * width).coerceIn(w / 2f, width - w / 2f)
    val centerY = if (h <= height) (graphic.centerY * height).coerceIn(h / 2f, height - h / 2f)
        else height / 2f
    return RectF(centerX - w / 2f, centerY - h / 2f, centerX + w / 2f, centerY + h / 2f)
}

internal object PhotoGraphicWatermarkRenderer {
    fun decode(context: Context, uriString: String): Bitmap {
        val uri = Uri.parse(uriString)
        require(uri.scheme == "content")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val initialStream = context.contentResolver.openInputStream(uri)
            ?: throw java.io.IOException("Графический водяной знак недоступен.")
        initialStream.use {
            // DecodeStream intentionally returns null when inJustDecodeBounds=true.
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0)
            throw java.io.IOException("Не удалось прочитать изображение водяного знака.")
        var scale = 1
        while (max(bounds.outWidth, bounds.outHeight) / scale > 2048) scale *= 2
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                inSampleSize = scale
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })
        } ?: throw java.io.IOException("Не удалось открыть водяной знак.")
    }

    /** Paint directly onto a mutable output; avoid creating another full-resolution copy. */
    fun draw(context: Context, canvas: Canvas, width: Int, height: Int, graphic: PhotoGraphicWatermark) {
        val mark = decode(context, graphic.uri)
        try {
            canvas.drawBitmap(mark, null,
                photoWatermarkBounds(width, height, mark.width, mark.height, graphic),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                    alpha = (graphic.opacity * 255).roundToInt().coerceIn(0, 255)
                })
        } finally { mark.recycle() }
    }

    fun overlay(context: Context, source: Bitmap, graphic: PhotoGraphicWatermark?): Bitmap {
        if (graphic == null) return source
        val output = source.copy(Bitmap.Config.ARGB_8888, true)
        try {
            draw(context, Canvas(output), output.width, output.height, graphic)
            return output
        } catch (error: Throwable) {
            output.recycle()
            throw error
        }
    }
}
