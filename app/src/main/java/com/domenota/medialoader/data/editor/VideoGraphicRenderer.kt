package com.domenota.medialoader.data.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Render sticker shapes and SAF image overlays into the same raster frame used
 * by Media3 preview and export. Images are cached while an effect is active.
 */
internal class VideoGraphicRenderer(private val context: Context) {
    private val cachedImages = mutableMapOf<String, Bitmap?>()

    fun prepare(layers: List<VideoGraphicLayer>) {
        layers.filter { it.kind == VideoGraphicKind.IMAGE }.mapNotNull { it.sourceUri }
            .distinct().forEach { uri ->
                if (!cachedImages.containsKey(uri)) cachedImages[uri] = loadImage(uri)
            }
    }

    private fun loadImage(uri: String): Bitmap? = runCatching {
        val parsed = android.net.Uri.parse(uri)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(parsed)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024)
            sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) }
        context.contentResolver.openInputStream(parsed)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    }.getOrNull()

    fun draw(canvas: Canvas, width: Int, height: Int,
        layers: List<VideoGraphicLayer>, positionMs: Long) {
        val dimension = minOf(width, height).toFloat()
        layers.filter { it.visibleAt(positionMs) }.forEach { layer ->
            if (layer.opacity <= 0f) return@forEach
            val radius = dimension * layer.size * .5f
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                color = layer.color
                alpha = (Color.alpha(layer.color) * layer.opacity).toInt().coerceIn(0, 255)
                style = Paint.Style.FILL
            }
            val save = canvas.save()
            canvas.translate(width * layer.centerX, height * layer.centerY)
            canvas.rotate(layer.rotation)
            when (layer.kind) {
                VideoGraphicKind.RECTANGLE ->
                    canvas.drawRoundRect(RectF(-radius, -radius, radius, radius),
                        radius * .16f, radius * .16f, paint)
                VideoGraphicKind.CIRCLE -> canvas.drawCircle(0f, 0f, radius, paint)
                VideoGraphicKind.STAR -> {
                    val path = Path()
                    for (i in 0..9) {
                        val angle = (i * PI / 5.0 - PI / 2.0)
                        val r = if (i % 2 == 0) radius else radius * .44f
                        val x = (cos(angle) * r).toFloat()
                        val y = (sin(angle) * r).toFloat()
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    path.close()
                    canvas.drawPath(path, paint)
                }
                VideoGraphicKind.ARROW -> {
                    val path = Path().apply {
                        moveTo(-radius, -radius * .33f)
                        lineTo(radius * .13f, -radius * .33f)
                        lineTo(radius * .13f, -radius * .75f)
                        lineTo(radius, 0f)
                        lineTo(radius * .13f, radius * .75f)
                        lineTo(radius * .13f, radius * .33f)
                        lineTo(-radius, radius * .33f)
                        close()
                    }
                    canvas.drawPath(path, paint)
                }
                VideoGraphicKind.EMOJI -> {
                    paint.textSize = radius * 1.75f
                    paint.textAlign = Paint.Align.CENTER
                    paint.typeface = android.graphics.Typeface.DEFAULT
                    val baseline = -(paint.ascent() + paint.descent()) / 2f
                    canvas.drawText(layer.emoji, 0f, baseline, paint)
                }
                VideoGraphicKind.IMAGE -> {
                    val bitmap = layer.sourceUri?.let { cachedImages[it] }
                    if (bitmap != null && !bitmap.isRecycled) {
                        val ratio = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)
                        val halfWidth = if (ratio >= 1f) radius else radius * ratio
                        val halfHeight = if (ratio >= 1f) radius / ratio else radius
                        canvas.drawBitmap(bitmap, null,
                            RectF(-halfWidth, -halfHeight, halfWidth, halfHeight), paint)
                    }
                }
            }
            canvas.restoreToCount(save)
        }
    }

    fun release() {
        cachedImages.values.filterNotNull().forEach { if (!it.isRecycled) it.recycle() }
        cachedImages.clear()
    }
}
