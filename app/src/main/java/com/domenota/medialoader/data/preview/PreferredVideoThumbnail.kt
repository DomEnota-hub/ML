package com.domenota.medialoader.data.preview

import android.graphics.Bitmap
import com.domenota.medialoader.data.preferences.VideoThumbnailMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A cached cover wins immediately; saved frames can be shown while a new source cover loads. */
internal suspend fun preferredVideoThumbnail(
    mode: VideoThumbnailMode,
    previewUrl: String?,
    loadSource: suspend (String) -> Bitmap?,
    loadFrame: suspend () -> Bitmap?,
    loadCachedSource: suspend (String) -> Bitmap? = { null },
    publishFallback: ((Bitmap) -> Unit)? = null,
): Bitmap? {
    if (mode == VideoThumbnailMode.SOURCE && !previewUrl.isNullOrBlank()) {
        loadCachedSource(previewUrl)?.let { return it }
        // A saved item must not stay blank behind a slow or expired CDN request.
        // Keep this frame for the final fallback so a failed cover never decodes it twice.
        val frame = if (publishFallback != null) loadFrame() else null
        currentCoroutineContext().ensureActive()
        frame?.let { publishFallback?.invoke(it) }
        val source = try { loadSource(previewUrl) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        if (source != null) return source
        if (publishFallback != null) return frame
    }
    return loadFrame()
}
