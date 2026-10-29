package com.domenota.medialoader.data.download

import com.domenota.medialoader.core.model.DownloadTask
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.core.provider.RutubeProvider
import com.domenota.medialoader.core.provider.VkProvider
import com.domenota.medialoader.core.provider.YouTubeProvider

/** Single entry point for the queue with dedicated yt-dlp engines for YouTube, VK and RUTUBE. */
class RoutingDownloadEngine(
    private val media: DownloadEngine,
    private val audio: DownloadEngine,
    private val youtube: DownloadEngine? = null,
    private val vk: DownloadEngine? = null,
    private val rutube: DownloadEngine? = null,
    private val hitmoz: DownloadEngine? = null,
    private val muzmo: DownloadEngine? = null,
) : DownloadEngine {
    override suspend fun download(
        task: DownloadTask,
        onProgress: suspend (bytesDownloaded: Long, totalBytes: Long?) -> Unit,
    ): DownloadResult = when (task.item.providerId) {
        YouTubeProvider.ID -> requireNotNull(youtube).download(task, onProgress)
        VkProvider.ID -> requireNotNull(vk).download(task, onProgress)
        RutubeProvider.ID -> requireNotNull(rutube).download(task, onProgress)
        "hitmoz" -> requireNotNull(hitmoz).download(task, onProgress)
        "muzmo" -> requireNotNull(muzmo).download(task, onProgress)
        else -> if (task.item.type == MediaType.AUDIO) audio.download(task, onProgress)
        else media.download(task, onProgress)
    }
}
