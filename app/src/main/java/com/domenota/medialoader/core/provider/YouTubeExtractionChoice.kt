package com.domenota.medialoader.core.provider

/** Choose only formats that yt-dlp actually reported, with audio available for playback. */
object YouTubeExtractionChoice {
    fun maxVideoHeight(info: StreamInfo): Int =
        info.formats.filter { it.hasVideo }.mapNotNull { it.height }.maxOrNull() ?: 0

    fun publicResultIsEnough(info: StreamInfo): Boolean =
        info.formats.any { it.hasVideo && ((it.height ?: 0) >= 1080 || (it.width ?: 0) >= 1920) } &&
            info.formats.any { it.hasAudio }

    fun publicResultCanBoundWait(info: StreamInfo): Boolean =
        maxVideoHeight(info) >= 720 && info.formats.any { it.hasAudio }

    fun preferPublic(publicInfo: StreamInfo, savedSessionInfo: StreamInfo): Boolean {
        if (publicInfo.formats.none { it.hasAudio }) return false
        val publicHeight = maxVideoHeight(publicInfo)
        val savedHeight = maxVideoHeight(savedSessionInfo)
        return publicHeight > savedHeight ||
            (publicHeight == savedHeight && savedSessionInfo.formats.none { it.hasAudio })
    }
}
