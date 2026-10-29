package com.domenota.medialoader.data.download

import android.content.Context
import com.domenota.medialoader.core.model.DownloadTask
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.data.storage.StorageManager
import com.domenota.medialoader.data.youtube.YtDlpDownloader
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * YouTube video (merged MP4) and audio-only (MP3) through yt-dlp. The file is built in the app cache
 * and then handed to StorageManager, so the target folder is still decided in one place.
 */
class YouTubeDownloadEngine(
    private val context: Context,
    private val storage: StorageManager,
    private val downloader: YtDlpDownloader,
) : DownloadEngine {
    override suspend fun download(
        task: DownloadTask,
        onProgress: suspend (bytesDownloaded: Long, totalBytes: Long?) -> Unit,
    ): DownloadResult {
        val workDir = File(context.cacheDir, "ytdlp-${UUID.randomUUID()}").apply { mkdirs() }
        val audioOnly = task.item.type == MediaType.AUDIO
        try {
            val file = coroutineScope {
                // yt-dlp reports percent from its own thread; the queue wants suspend calls, so a channel bridges them.
                val percents = Channel<Float>(Channel.CONFLATED)
                val reporter = launch { for (percent in percents) onProgress((percent * 10).toLong(), PER_MILLE) }
                try {
                    downloader.download(task.item.downloadUrl, task.item.formatSelector, audioOnly, workDir) {
                        percents.trySend(it)
                    }
                } finally {
                    percents.close()
                    reporter.join()
                }
            }
            task.publishAfter?.await()
            val published = storage.publish(file, task.fileName, if (audioOnly) "audio/mpeg" else "video/mp4")
            return DownloadResult(published.uri.toString(), file.length(), published.fileName)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: DownloadFailure) {
            throw failure
        } catch (error: Exception) {
            throw DownloadFailure("Не удалось сохранить файл с YouTube. Повторите попытку.", error)
        } finally {
            workDir.deleteRecursively()
        }
    }

    private companion object {
        /** Progress is reported in per-mille: bytesDownloaded / 1000 is the fraction done. */
        const val PER_MILLE = 1000L
    }
}
