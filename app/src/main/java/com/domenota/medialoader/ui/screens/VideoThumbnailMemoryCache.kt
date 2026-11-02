package com.domenota.medialoader.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import com.domenota.medialoader.data.editor.PhotoColorAdjustments
import com.domenota.medialoader.data.editor.PhotoColorMath
import com.domenota.medialoader.data.editor.PhotoEffect
import com.domenota.medialoader.data.editor.VideoClipDraft
import com.domenota.medialoader.data.editor.VideoSpecialFxType

/** Bounded, shared thumbnail cache. Invoke from a background dispatcher only. */
internal object VideoThumbnailMemoryCache {
    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    internal fun sourceKey(clip: VideoClipDraft): String =
        "${clip.sourceUri}|${clip.mediaKind}|${clip.inMs}|${clip.outMs}|${clip.sourceDurationMs}"

    @Synchronized
    private fun frame(context: Context, clip: VideoClipDraft): Bitmap? {
        val key = "frame|${sourceKey(clip)}"
        cache.get(key)?.let { return it }
        return VideoFxThumbnail.readFrame(context, clip)?.also { cache.put(key, it) }
    }

    @Synchronized
    fun photo(context: Context, clip: VideoClipDraft, effect: PhotoEffect): Bitmap? {
        val key = "photo|${sourceKey(clip)}|${effect.name}"
        cache.get(key)?.let { return it }
        val sample = frame(context, clip) ?: return null
        val rendered = PhotoColorMath.apply(sample, PhotoColorAdjustments(effect = effect))
        val result = if (rendered === sample) {
            sample.copy(Bitmap.Config.ARGB_8888, false)
        } else rendered
        cache.put(key, result)
        return result
    }

    @Synchronized
    fun special(context: Context, clip: VideoClipDraft, effect: VideoSpecialFxType): Bitmap? {
        val key = "special|${sourceKey(clip)}|${effect.name}"
        cache.get(key)?.let { return it }
        return VideoFxThumbnail.render(frame(context, clip) ?: return null, effect).also {
            cache.put(key, it)
        }
    }

    @Synchronized
    internal fun currentSizeBytes(): Int = cache.size()
}
