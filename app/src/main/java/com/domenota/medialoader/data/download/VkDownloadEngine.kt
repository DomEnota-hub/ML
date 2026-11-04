package com.domenota.medialoader.data.download

import android.content.Context
import com.domenota.medialoader.core.model.DownloadTask
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.core.provider.VkDirectMedia
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import com.domenota.medialoader.data.storage.StorageManager
import com.domenota.medialoader.data.youtube.YtDlpDownloader
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Signed GAPI media downloads over HTTP; legacy VK pages retain their existing yt-dlp engine. */
class VkDownloadEngine(
    private val context: Context,
    private val storage: StorageManager,
    private val downloader: YtDlpDownloader,
) : DownloadEngine {
    private val directAudio = AudioExtractor(context, storage,
        mediaUrlAllowed = VkDirectMedia::isSafeUrl, referer = VkDirectMedia.REFERER)

    override suspend fun download(
        task: DownloadTask,
        onProgress: suspend (bytesDownloaded: Long, totalBytes: Long?) -> Unit,
    ): DownloadResult {
        if (VkDirectMedia.isSafeUrl(task.item.downloadUrl)) return downloadDirect(task, onProgress)
        val workDir = File(context.cacheDir, "vk-ytdlp-${UUID.randomUUID()}").apply { mkdirs() }
        val audioOnly = task.item.type == MediaType.AUDIO
        try {
            val file = coroutineScope {
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
            throw DownloadFailure("Не удалось сохранить файл из VK. Повторите попытку.", error)
        } finally {
            workDir.deleteRecursively()
        }
    }

    private suspend fun downloadDirect(
        task: DownloadTask,
        onProgress: suspend (Long, Long?) -> Unit,
    ): DownloadResult = withContext(Dispatchers.IO) {
        if (task.item.type == MediaType.AUDIO) {
            try {
                val saved = directAudio.saveAudio(task.item.downloadUrl, task.fileName, onProgress,
                    beforePublish = { task.publishAfter?.await() })
                return@withContext DownloadResult(saved.published.uri.toString(), saved.sizeBytes, saved.published.fileName)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { throw DownloadFailure("Не удалось сохранить MP3 из VK. Повторите анализ ссылки.", e) }
        }
        val temporary = File.createTempFile("vk-http-", ".part", context.cacheDir)
        try {
            val size = transfer(task.item.downloadUrl, temporary, onProgress)
            task.publishAfter?.await()
            val published = storage.publish(temporary, task.fileName, "video/mp4")
            DownloadResult(published.uri.toString(), size, published.fileName)
        } catch (e: CancellationException) { throw e }
        catch (e: DownloadFailure) { throw e }
        catch (e: Exception) { throw DownloadFailure("Не удалось сохранить файл из VK. Повторите анализ ссылки.", e) }
        finally { temporary.delete() }
    }

    /** No key, VK account cookies or page extractor ever sees this signed CDN URL. */
    private suspend fun transfer(url: String, target: File, progress: suspend (Long, Long?) -> Unit): Long {
        var current = URL(url)
        repeat(5) {
            currentCoroutineContext().ensureActive()
            if (!VkDirectMedia.isSafeUrl(current.toString())) throw DownloadFailure("Недопустимый адрес медиа VK.")
            val connection = current.openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15_000
                connection.readTimeout = 25_000
                connection.setRequestProperty("User-Agent", VkDirectMedia.USER_AGENT)
                connection.setRequestProperty("Referer", VkDirectMedia.REFERER)
                val status = connection.responseCode
                if (status in 300..399) {
                    val location = connection.getHeaderField("Location")
                        ?: throw DownloadFailure("Файл VK недоступен. Повторите анализ ссылки.")
                    current = URL(current, location)
                    if (!VkDirectMedia.isSafeUrl(current.toString())) throw DownloadFailure("Недопустимый адрес медиа VK.")
                    return@repeat
                }
                if (status !in 200..299) throw DownloadFailure("Ссылка VK устарела или недоступна. Повторите анализ ссылки.")
                val declared = connection.contentLengthLong.takeIf { it > 0 }
                if (declared != null && declared > context.cacheDir.usableSpace - MIN_FREE_BYTES) {
                    throw DownloadFailure("Недостаточно места на устройстве.")
                }
                var total = 0L
                connection.inputStream.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (context.cacheDir.usableSpace < MIN_FREE_BYTES) throw DownloadFailure("Недостаточно места на устройстве.")
                            output.write(buffer, 0, count)
                            total += count
                            progress(total, declared)
                        }
                    }
                }
                if (total == 0L || declared != null && total != declared) {
                    throw DownloadFailure("Загрузка VK прервалась. Повторите анализ ссылки.")
                }
                return total
            } finally { connection.disconnect() }
        }
        throw DownloadFailure("Слишком много перенаправлений VK. Повторите анализ ссылки.")
    }

    private companion object {
        const val MIN_FREE_BYTES = 16L * 1024 * 1024
        const val PER_MILLE = 1000L
    }
}
