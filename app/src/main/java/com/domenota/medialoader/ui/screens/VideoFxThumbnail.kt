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
import android.os.Build
import com.domenota.medialoader.data.editor.VideoClipDraft
import com.domenota.medialoader.data.editor.VideoClipMediaKind
import com.domenota.medialoader.data.editor.VideoSpecialFxType
import kotlin.math.hypot

/** Small actual-frame sample; never decode or paint thumbnails on the Compose main thread. */
internal object VideoFxThumbnail {
    fun readFrame(context: Context, clip: VideoClipDraft): Bitmap? = runCatching {
        val source = Uri.parse(clip.sourceUri)
        val frame = if (clip.mediaKind == VideoClipMediaKind.IMAGE) {
            // Avoid decoding full-resolution phone photos just for a 144x94 tile.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(source)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
            var sampleSize = 1
            while (bounds.outWidth / sampleSize > 288 ||
                bounds.outHeight / sampleSize > 188) {
                sampleSize *= 2
            }
            context.contentResolver.openInputStream(source)?.use {
                BitmapFactory.decodeStream(it, null,
                    BitmapFactory.Options().apply { inSampleSize = sampleSize })
            }
        } else {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, source)
                val timeUs = clip.inMs.coerceAtLeast(0L) * 1000L
                if (Build.VERSION.SDK_INT >= 27) {
                    retriever.getScaledFrameAtTime(timeUs,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 144, 94)
                } else {
                    retriever.getFrameAtTime(timeUs,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }
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
        com.domenota.medialoader.data.editor.VideoSpecialFxPainter.draw(
            canvas, output.width, output.height,
            com.domenota.medialoader.data.editor.VideoSpecialFxDraft(type, intensity),
            stillPreview = true,
        )
        return output
    }
}
