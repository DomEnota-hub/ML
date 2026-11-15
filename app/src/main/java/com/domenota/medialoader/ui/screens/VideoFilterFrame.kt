package com.domenota.medialoader.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import com.domenota.medialoader.data.editor.PhotoFilterThumbnail
import com.domenota.medialoader.data.editor.PhotoSourceOrientation
import com.domenota.medialoader.data.editor.VideoClipDraft
import com.domenota.medialoader.data.editor.VideoClipMediaKind

/** One aspect-preserving source sample for an entire filter strip. No disk/global cache. */
internal object VideoFilterFrame {
    data class Key(val clipId: String, val uri: String, val kind: VideoClipMediaKind,
        val startMs: Long, val endMs: Long, val sourceDurationMs: Long, val edge: Int)
    fun key(clip: VideoClipDraft, edge: Int) = Key(clip.id, clip.sourceUri, clip.mediaKind,
        clip.inMs, clip.outMs, clip.sourceDurationMs, edge)

    /** Prefer the middle of the retained clip; fall back to two other retained positions. */
    fun positions(clip: VideoClipDraft): List<Long> {
        val span = clip.outMs - clip.inMs
        return listOf(span / 2, span / 4, span - span / 4)
            .map { (clip.inMs + it).coerceIn(clip.inMs, clip.outMs - 1) }.distinct()
    }

    internal fun fitOwned(frame: Bitmap, edge: Int): Bitmap {
        val (w, h) = PhotoFilterThumbnail.dimensions(frame.width, frame.height, edge)
        val fitted = Bitmap.createScaledBitmap(frame, w, h, true)
        if (fitted !== frame) frame.recycle()
        return fitted
    }

    /** Dark content can be valid; keep the first frame as fallback, not a fabricated image. */
    internal fun isInformative(frame: Bitmap): Boolean {
        var lit = 0
        for (y in 0 until 8) for (x in 0 until 8) {
            val px = frame.getPixel((x * (frame.width - 1)) / 7, (y * (frame.height - 1)) / 7)
            val r = (px ushr 16) and 255
            val g = (px ushr 8) and 255
            val b = px and 255
            if (r * 3 + g * 6 + b > 240) lit++
        }
        return lit >= 8
    }

    fun read(context: Context, clip: VideoClipDraft, edge: Int): Bitmap? {
        require(edge in 256..384)
        val uri = Uri.parse(clip.sourceUri)
        return try {
            if (clip.mediaKind == VideoClipMediaKind.IMAGE) readImage(context, uri, edge)
            else readVideo(context, uri, clip, edge)
        } catch (_: Exception) { null }
    }

    private fun readImage(context: Context, uri: Uri, edge: Int): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > edge * 2) sample *= 2
        val orientation = resolver.openInputStream(uri)?.use {
            runCatching { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)
        } ?: 1
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })
        } ?: return null
        val oriented = try { PhotoSourceOrientation.apply(decoded, orientation) }
            catch (error: Throwable) { decoded.recycle(); throw error }
        if (oriented !== decoded) decoded.recycle()
        return fitOwned(oriented, edge)
    }

    private fun readVideo(context: Context, uri: Uri, clip: VideoClipDraft, edge: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        var fallback: Bitmap? = null
        try {
            retriever.setDataSource(context, uri)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            // Scaled extraction retains the original aspect ratio in a bounding square.
            // Do not ask for an enlarged sample from a low-resolution source.
            val limit = if (width != null && height != null && width > 0 && height > 0)
                minOf(edge, maxOf(width, height)) else edge
            for (ms in positions(clip)) {
                val timeUs = ms.coerceAtMost(Long.MAX_VALUE / 1000L) * 1000L
                val raw = if (Build.VERSION.SDK_INT >= 27) {
                    retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST, limit, limit)
                        ?: retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, limit, limit)
                } else retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                if (raw == null) continue
                val frame = fitOwned(raw, limit)
                if (isInformative(frame)) {
                    fallback?.recycle()
                    fallback = null
                    return frame
                }
                if (fallback == null) fallback = frame else frame.recycle()
            }
            val result = fallback
            fallback = null
            return result
        } finally {
            fallback?.recycle()
            retriever.release()
        }
    }
}
