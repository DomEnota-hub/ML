package com.domenota.medialoader.data.download

import android.content.Context
import com.domenota.medialoader.core.logging.AppLog
import com.domenota.medialoader.core.logging.SafeNetworkAddress
import com.domenota.medialoader.core.model.DownloadTask
import com.domenota.medialoader.data.storage.StorageManager
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Muzmo-only MP3 transport. It never accepts or contacts a HitMoz address. */
class MuzmoDownloadEngine(
    private val context: Context,
    private val storage: StorageManager,
) : DownloadEngine {
    override suspend fun download(
        task: DownloadTask,
        onProgress: suspend (Long, Long?) -> Unit,
    ): DownloadResult = withContext(Dispatchers.IO) {
        if (task.item.providerId != "muzmo" || !MuzmoDownloadUrls.isMp3(task.item.downloadUrl)) {
            AppLog.w("Muzmo", "Download blocked before connection · provider=${task.item.providerId}")
            throw DownloadFailure("Недопустимая задача Muzmo.")
        }
        val temp = File.createTempFile("muzmo-", ".part", context.cacheDir)
        try {
            var current = URL(task.item.downloadUrl)
            repeat(MAX_REDIRECTS + 1) { hop ->
                val startedAt = System.nanoTime()
                AppLog.verbose("Muzmo", "MP3 request · id=${task.id} · hop=$hop · ${SafeNetworkAddress.describe(current.toString())} · method=GET")
                val connection = (current.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    setRequestProperty("User-Agent", USER_AGENT)
                    setRequestProperty("Accept", "audio/mpeg,audio/*;q=0.9,*/*;q=0.5")
                }
                try {
                    val status = connection.responseCode
                    val type = connection.contentType.orEmpty().substringBefore(';')
                    AppLog.verbose("Muzmo", "MP3 response · id=${task.id} · hop=$hop · status=$status · host=${current.host.lowercase()} · mime=$type · length=${connection.contentLengthLong} · elapsedMs=${(System.nanoTime() - startedAt) / 1_000_000}")
                    if (status in 300..399) {
                        val next = connection.getHeaderField("Location")?.let { URL(current, it) }
                            ?: throw DownloadFailure("Muzmo ответил перенаправлением без адреса.")
                        val trusted = MuzmoDownloadUrls.isTrustedRedirect(next.toString())
                        AppLog.verbose("Muzmo", "MP3 redirect · id=${task.id} · hop=$hop · trusted=$trusted · ${SafeNetworkAddress.describe(next.toString())}")
                        if (!trusted) {
                            throw DownloadFailure("Muzmo перенаправил файл на неподтверждённый адрес.")
                        }
                        current = next
                        return@repeat
                    }
                    if (status !in 200..299) throw DownloadFailure("Muzmo не отдал MP3 (HTTP $status).")
                    if (type.contains("html", true) || type.contains("json", true) || type.contains("text/", true)) {
                        throw DownloadFailure("Muzmo вернул страницу вместо MP3.")
                    }
                    val expected = connection.contentLengthLong.takeIf { it > 0L }
                    var bytes = 0L
                    connection.inputStream.use { input -> temp.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            bytes += count
                            output.write(buffer, 0, count)
                            onProgress(bytes, expected)
                        }
                    } }
                    if (bytes == 0L || expected != null && bytes != expected || !isMp3(temp)) {
                        throw DownloadFailure("Muzmo передал некорректный MP3.")
                    }
                    task.publishAfter?.await()
                    val saved = storage.publish(temp, task.fileName, "audio/mpeg")
                    AppLog.ok("Muzmo", "MP3 stream verified · id=${task.id} · bytes=$bytes")
                    return@withContext DownloadResult(saved.uri.toString(), bytes, saved.fileName)
                } finally {
                    connection.disconnect()
                }
            }
            throw DownloadFailure("Слишком много перенаправлений Muzmo.")
        } catch (error: IOException) {
            AppLog.w("Muzmo", "MP3 transport failed · id=${task.id} · reason=${error.javaClass.simpleName}", error)
            throw DownloadFailure("Не удалось скачать MP3 с Muzmo.", error)
        } finally {
            temp.delete()
        }
    }

    private fun isMp3(file: File): Boolean = file.inputStream().use { input ->
        val header = ByteArray(4)
        if (input.read(header) < 4) return@use false
        (header[0] == 'I'.code.toByte() && header[1] == 'D'.code.toByte() && header[2] == '3'.code.toByte()) ||
            (header[0].toInt() and 0xFF == 0xFF && header[1].toInt() and 0xE0 == 0xE0)
    }

    private companion object {
        const val MAX_REDIRECTS = 3
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/125.0.0.0 Mobile Safari/537.36"
    }
}
