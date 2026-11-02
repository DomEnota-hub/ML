package com.domenota.medialoader.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.domenota.medialoader.data.editor.VideoClipDraft
import com.domenota.medialoader.data.editor.VideoClipMediaKind
import com.domenota.medialoader.data.editor.VideoSpecialFxType
import kotlin.math.hypot

/** Small actual-frame sample; never decode or paint thumbnails on the Compose main thread. */
internal object VideoFxThumbnail {
    fun readFrame(context: Context, clip: VideoClipDraft): Bitmap? = runCatching {
        val source = Uri.parse(clip.sourceUri)
        val frame = if (clip.mediaKind == VideoClipMediaKind.IMAGE) {
            context.contentResolver.openInputStream(source)?.use {
                BitmapFactory.decodeStream(it)
            }
        } else {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, source)
                retriever.getFrameAtTime(
                    clip.inMs.coerceAtLeast(0L) * 1000L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                )
            } finally {
                retriever.release()
            }
        } ?: return@runCatching null
        val output = Bitmap.createScaledBitmap(frame, 144, 94, true)
        if (output !== frame) frame.recycle()
        output
    }.getOrNull()

    /**
     * Color/geometry is an honest static snapshot of the corresponding Media3
     * VideoSpecialFxOverlay shader treatment, not the same generic icon for every choice.
     */
    fun render(sample: Bitmap, type: VideoSpecialFxType, intensity: Float = .75f): Bitmap {
        val output = Bitmap.createBitmap(
            sample.width, sample.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawBitmap(sample, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        val width = output.width.toFloat()
        val height = output.height.toFloat()
        when (type) {
            VideoSpecialFxType.NONE -> Unit
            VideoSpecialFxType.VIGNETTE -> {
                val radius = hypot(width, height) * .58f
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = RadialGradient(width / 2, height / 2, radius,
                        intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT,
                            Color.argb((220 * intensity).toInt().coerceIn(0, 255), 0, 0, 0)),
                        floatArrayOf(0f, .40f, 1f), Shader.TileMode.CLAMP)
                }
                canvas.drawRect(0f, 0f, width, height, paint)
            }
            VideoSpecialFxType.SCANLINES -> {
                val paint = Paint().apply {
                    color = Color.argb((170 * intensity).toInt().coerceIn(0,255), 0, 0, 0)
                }
                var y = 1f
                val step = (height / 125f).coerceAtLeast(4f)
                while (y < height) {
                    canvas.drawRect(0f, y, width, y + (step / 4f).coerceAtLeast(1f), paint)
                    y += step
                }
            }
            VideoSpecialFxType.CINEMA_BARS -> {
                val band = height * .13f * intensity
                val paint = Paint().apply { color = Color.BLACK }
                canvas.drawRect(0f, 0f, width, band, paint)
                canvas.drawRect(0f, height - band, width, height, paint)
            }
            VideoSpecialFxType.PULSE -> {
                val paint = Paint().apply { color = Color.argb(
                    (100 * intensity).toInt().coerceIn(0, 255), 255, 255, 255) }
                canvas.drawRect(0f, 0f, width, height, paint)
            }
        }
        return output
    }
}
