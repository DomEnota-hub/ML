package com.domenota.medialoader.data.youtube

import java.io.File

/** Seam in front of the library that downloads and merges streams (yt-dlp + ffmpeg). */
interface YtDlpDownloader {
    /**
     * Downloads into [targetDir] as `out.<ext>` and returns that file. [selector] is a yt-dlp format
     * expression for video; for [audioOnly] the audio is transcoded to MP3 at 192 kbps. Cancelling
     * the coroutine must stop the underlying process. [onProgress] gets a percentage from 0 to 100
     * and may be called from any thread.
     */
    suspend fun download(
        url: String,
        selector: String?,
        audioOnly: Boolean,
        targetDir: File,
        onProgress: (percent: Float) -> Unit,
    ): File
}
