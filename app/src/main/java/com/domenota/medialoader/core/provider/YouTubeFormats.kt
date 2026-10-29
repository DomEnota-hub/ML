package com.domenota.medialoader.core.provider

/** yt-dlp format expressions. Kept here, pure, so they can be unit-tested. */
object YouTubeFormats {
    const val MAX_DEFAULT_HEIGHT = 1080

    /** Exactly [height] p; H.264 + AAC first (plays everywhere), then any codecs, then a single stream. */
    fun videoSelector(height: Int): String =
        "bv*[height=$height][vcodec^=avc1]+ba[ext=m4a]/bv*[height=$height]+ba/b[height=$height]"

    /** Use a muxed stream when YouTube exposes no separate audio-only format. */
    const val AUDIO_SELECTOR = "bestaudio[ext=m4a]/bestaudio/best"

    const val BEST_VIDEO_SELECTOR = "bv*+ba/b"
}
