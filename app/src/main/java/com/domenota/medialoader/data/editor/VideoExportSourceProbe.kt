package com.domenota.medialoader.data.editor

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri

/** Reads orientation-aware source dimensions; sources are never modified. */
internal object VideoExportSourceProbe {
    fun dimensions(context: Context, clip: VideoClipDraft): Pair<Int, Int> {
        val uri = Uri.parse(clip.sourceUri)
        if (clip.mediaKind == VideoClipMediaKind.IMAGE) {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val input = context.contentResolver.openInputStream(uri)
                ?: error("Изображение недоступно для экспорта")
            input.use { BitmapFactory.decodeStream(it, null, options) }
            require(options.outWidth > 0 && options.outHeight > 0) {
                "Не удалось прочитать размер изображения"
            }
            return options.outWidth to options.outHeight
        }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: error("Не удалось определить ширину исходного видео")
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: error("Не удалось определить высоту исходного видео")
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            require(width > 0 && height > 0)
            return if (rotation % 180 == 0) width to height else height to width
        } finally { retriever.release() }
    }
}
