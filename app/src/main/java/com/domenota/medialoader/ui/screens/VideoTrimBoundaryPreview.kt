package com.domenota.medialoader.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import kotlin.math.roundToInt

/**
 * Read the actual SOURCE frame under the active trim handle, not the stale
 * frame in a clipping playlist that is being rebuilt during dragging.
 * Called only on Dispatchers.IO; source files are read, never changed.
 */
internal object VideoTrimBoundaryPreview {
    data class Request(val sourceUri: String, val sourceMs: Long)

    interface Source : java.io.Closeable { fun frame(sourceMs: Long): Bitmap? }

    /** One retriever per gesture/source, rather than reopening the file every 95ms. */
    class Decoder(context: Context,
        private val open: (String) -> Source = { RetrieverSource(context, it) }
    ) : java.io.Closeable {
        private var uri: String? = null
        private var source: Source? = null
        private var closed = false
        fun decode(request: Request): Bitmap? {
            val started = com.domenota.medialoader.core.logging.PerformanceLog.now()
            return try { runCatching {
            if (closed) return null
            if (uri != request.sourceUri) {
                source?.close(); source = null; uri = null
                source = open(request.sourceUri); uri = request.sourceUri
            }
            com.domenota.medialoader.data.editor.VideoFrameDecodeBudget.decode {
                source?.frame(request.sourceMs)
            }
        }.getOrNull() } finally {
                com.domenota.medialoader.core.logging.PerformanceLog.slow("trim_boundary_decode", started)
            }
        }
        override fun close() {
            if (closed) return
            closed = true
            try { source?.close() } finally { source = null; uri = null }
        }
    }

    fun decode(context: Context, request: Request): Bitmap? = Decoder(context).use { it.decode(request) }

    private class RetrieverSource(context: Context, uri: String) : Source {
        private val retriever = MediaMetadataRetriever().also {
            try { it.setDataSource(context, Uri.parse(uri)) }
            catch (error: Exception) { it.release(); throw error }
        }
        override fun frame(sourceMs: Long): Bitmap? {
            val timeUs = sourceMs.coerceAtLeast(0L) * 1000L
            if (Build.VERSION.SDK_INT >= 27) return retriever.getScaledFrameAtTime(
                timeUs, MediaMetadataRetriever.OPTION_CLOSEST, 360, 360)
            val frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                ?: return null
            val scale = (360f / maxOf(frame.width, frame.height)).coerceAtMost(1f)
            if (scale == 1f) return frame
            return Bitmap.createScaledBitmap(frame,
                (frame.width * scale).roundToInt().coerceAtLeast(1),
                (frame.height * scale).roundToInt().coerceAtLeast(1), true)
                .also { frame.recycle() }
        }
        override fun close() { retriever.release() }
    }
}
