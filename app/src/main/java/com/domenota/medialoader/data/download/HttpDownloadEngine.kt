package com.domenota.medialoader.data.download

import android.content.Context
import android.util.Log
import com.domenota.medialoader.BuildConfig
import com.domenota.medialoader.core.model.DownloadTask
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.core.provider.InstagramProvider
import com.domenota.medialoader.data.storage.StorageManager
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Downloads within this app's network context, then publishes to the selected Downloads folder. */
class HttpDownloadEngine(
    private val context: Context,
    private val storage: StorageManager,
    private val instagramCookies: () -> String?,
) : DownloadEngine {
    override suspend fun download(
        task: DownloadTask,
        onProgress: suspend (Long, Long?) -> Unit,
    ): DownloadResult = withContext(Dispatchers.IO) {
        val temporary = File.createTempFile("media-", ".part", context.cacheDir)
        try {
            val size = try {
                transfer(task.item.downloadUrl, temporary, onProgress)
            } catch (error: DownloadFailure) {
                throw error
            } catch (error: IOException) {
                if (BuildConfig.DEBUG) Log.w("MediaLoaderDownload", "Transfer failed: ${error.javaClass.simpleName}")
                throw DownloadFailure("Нет соединения или файл недоступен. Повторите попытку.", error)
            }
            task.publishAfter?.await()
            val mime = if (task.item.type == MediaType.VIDEO) "video/mp4" else "image/jpeg"
            val published = try {
                storage.publish(temporary, task.fileName, mime)
            } catch (error: Exception) {
                throw DownloadFailure("Не удалось сохранить файл. Повторите попытку.", error)
            }
            DownloadResult(published.uri.toString(), size, published.fileName)
        } catch (error: DownloadFailure) {
            throw error
        } finally {
            temporary.delete()
        }
    }

    private suspend fun transfer(url: String, target: File, progress: suspend (Long, Long?) -> Unit): Long {
        var current = URL(url)
        repeat(5) {
            if (!InstagramProvider.safeMediaUrl(current.toString())) throw DownloadFailure("Медиа недоступно.")
            val connection = current.openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15_000
                connection.readTimeout = 25_000
                connection.setRequestProperty("Referer", "https://www.instagram.com/")
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36")
                if (current.host.equals("instagram.com", true) || current.host.endsWith(".instagram.com", true)) {
                    instagramCookies()?.let { connection.setRequestProperty("Cookie", it) }
                }
                val status = connection.responseCode
                if (BuildConfig.DEBUG) Log.d("MediaLoaderDownload", "GET ${current.host} HTTP $status")
                if (status in 300..399) {
                    val location = connection.getHeaderField("Location")
                        ?: throw DownloadFailure("Файл недоступен. Повторите анализ ссылки.")
                    val next = URL(current, location)
                    if (!InstagramProvider.safeMediaUrl(next.toString())) throw DownloadFailure("Медиа недоступно.")
                    current = next
                    return@repeat
                }
                if (status == 401 || status == 403) throw DownloadFailure(
                    "Доступ к файлу закрыт или ссылка устарела. Повторите анализ ссылки.",
                )
                if (status !in 200..299) throw DownloadFailure("Файл недоступен. Повторите анализ ссылки.")
                val declared = connection.contentLengthLong.takeIf { it > 0 }
                if (declared != null && declared + 16L * 1024 * 1024 > context.cacheDir.usableSpace) {
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
                            total += count
                            if (context.cacheDir.usableSpace < 16L * 1024 * 1024) {
                                throw DownloadFailure("Недостаточно места на устройстве.")
                            }
                            output.write(buffer, 0, count)
                            progress(total, declared)
                        }
                    }
                }
                if (total == 0L || declared != null && total != declared) {
                    throw DownloadFailure("Загрузка прервалась. Повторите анализ ссылки.")
                }
                return total
            } finally {
                connection.disconnect()
            }
        }
        throw DownloadFailure("Слишком много перенаправлений. Повторите анализ ссылки.")
    }
}
