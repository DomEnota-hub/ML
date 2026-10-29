package com.domenota.medialoader.data.preview

import android.graphics.Bitmap
import com.domenota.medialoader.data.preferences.VideoThumbnailMode
import kotlinx.coroutines.CancellationException

/** Source I/O and frame decoding are separate so frame extraction stays lazy. */
internal suspend fun preferredVideoThumbnail(
    mode: VideoThumbnailMode,
    previewUrl: String?,
    loadSource: suspend (String) -> Bitmap?,
    loadFrame: suspend () -> Bitmap?,
): Bitmap? {
    if (mode == VideoThumbnailMode.SOURCE && !previewUrl.isNullOrBlank()) {
        val source = try { loadSource(previewUrl) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        if (source != null) return source
    }
    return loadFrame()
}
