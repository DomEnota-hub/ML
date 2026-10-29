package com.domenota.medialoader.data.preferences

import android.content.Context

enum class VideoThumbnailMode(val stored: String, val label: String) {
    SOURCE("source", "Обложка источника"), FRAME("frame", "Кадр из видео")
}

class VideoPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("ui", Context.MODE_PRIVATE)
    var thumbnailMode: VideoThumbnailMode
        get() = VideoThumbnailMode.entries.firstOrNull { it.stored == prefs.getString(THUMBNAIL_KEY, null) } ?: VideoThumbnailMode.SOURCE
        set(value) { prefs.edit().putString(THUMBNAIL_KEY, value.stored).apply() }
    var preferredHeight: Int?
        get() = prefs.getInt(QUALITY_KEY, 0).takeIf { it in QUALITY_OPTIONS }
        set(value) {
            require(value in QUALITY_OPTIONS)
            prefs.edit().putInt(QUALITY_KEY, value ?: 0).apply()
        }

    companion object {
        const val THUMBNAIL_KEY = "video_thumbnail_mode"
        const val QUALITY_KEY = "preferred_video_height"
        val QUALITY_OPTIONS: List<Int?> = listOf(null, 2160, 1440, 1080, 720, 480, 360, 240, 144)
        fun qualityLabel(height: Int?): String = height?.let { "${it}p" } ?: "Максимальное"
    }
}
