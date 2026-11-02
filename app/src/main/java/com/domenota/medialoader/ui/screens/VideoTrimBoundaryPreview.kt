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

    fun decode(context: Context, request: Request): Bitmap? = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, Uri.parse(request.sourceUri))
            val timeUs = request.sourceMs.coerceAtLeast(0L) * 1000L
            if (Build.VERSION.SDK_INT >= 27) {
                // Decode at preview resolution: never allocate a full 4K frame.
                retriever.getScaledFrameAtTime(
                    timeUs, MediaMetadataRetriever.OPTION_CLOSEST, 360, 360)
            } else {
                val frame = retriever.getFrameAtTime(
                    timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                    ?: return@runCatching null
                val scale = (360f / maxOf(frame.width, frame.height)).coerceAtMost(1f)
                if (scale == 1f) frame else {
                    val small = Bitmap.createScaledBitmap(frame,
                        (frame.width * scale).roundToInt().coerceAtLeast(1),
                        (frame.height * scale).roundToInt().coerceAtLeast(1), true)
                    frame.recycle()
                    small
                }
            }
        } finally {
            retriever.release()
        }
    }.getOrNull()
}
