package com.domenota.medialoader.data.editor

import android.content.Context
import android.os.StatFs
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Optional REAL EfficientDet-Lite0 (COCO person detector), run only on device.
 *
 * Published model: https://huggingface.co/litert-community/efficientdet
 * Model publisher's declared license: Apache-2.0, revision pinned below.
 * SHA-256 is published with the binary file in the model repository.
 * Weight is NOT included in the APK and never silently downloaded.
 */
internal class PersonDetectorModelStore(context: Context) {
    private val folder = File(context.applicationContext.filesDir, "ai-models/efficientdet-lite0-v1")
    private val target = File(folder, FILENAME)

    companion object {
        const val REVISION = "360eea05e7de8f0d0f5602b548b5bbee6f7b65c7"
        const val FILENAME = "efficientdet_lite0_detection.tflite"
        const val SHA256 = "33a3b622c7cac0762f96089353cd61495f3e993968d133af7871bfc2d5396704"
        const val URL_PATH = "https://huggingface.co/litert-community/efficientdet/resolve/$REVISION/$FILENAME?download=true"
        const val MAX_BYTES = 16L * 1024 * 1024
        const val MIN_DISK_BYTES = 48L * 1024 * 1024
    }

    fun installed(): Boolean = target.isFile && target.length() in 1_000_001..MAX_BYTES

    suspend fun verifyInstalled(): Boolean = withContext(Dispatchers.IO) { verified() }

    suspend fun requireInstalled(): File = withContext(Dispatchers.IO) {
        if (!verified()) throw IOException("Модель обнаружения людей не установлена или повреждена.")
        target
    }

    suspend fun install(onProgress: (Int) -> Unit = {}) = withContext(Dispatchers.IO) {
        if (!folder.isDirectory && !folder.mkdirs())
            throw IOException("Не удалось создать папку модели людей.")
        if (verified()) {
            onProgress(100)
            return@withContext
        }
        if (StatFs(folder.absolutePath).availableBytes < MIN_DISK_BYTES)
            throw IOException("Для модели обнаружения людей нужно не менее 48 МБ свободного места.")
        val part = File(folder, "$FILENAME.part")
        part.delete()
        try {
            val connection = URL(URL_PATH).openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = true
                connection.connectTimeout = 20_000
                connection.readTimeout = 60_000
                connection.setRequestProperty("Accept", "application/octet-stream")
                val status = connection.responseCode
                if (status !in 200..299) throw IOException("Ошибка загрузки модели людей: HTTP $status")
                val length = connection.contentLengthLong
                if (length > MAX_BYTES) throw IOException("Размер модели людей превышает допустимый.")
                val digest = MessageDigest.getInstance("SHA-256")
                var downloaded = 0L
                connection.inputStream.use { input ->
                    FileOutputStream(part).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            downloaded += n
                            if (downloaded > MAX_BYTES)
                                throw IOException("Модель людей больше установленного ограничения.")
                            digest.update(buffer, 0, n)
                            output.write(buffer, 0, n)
                            if (length > 0)
                                onProgress((downloaded * 100 / length).toInt().coerceIn(0, 99))
                        }
                        output.fd.sync()
                    }
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (downloaded <= 1_000_000L || actual != SHA256)
                    throw IOException("SHA-256 модели обнаружения людей не совпадает.")
                if (target.exists() && !target.delete())
                    throw IOException("Не удалось заменить старую модель людей.")
                if (!part.renameTo(target))
                    throw IOException("Не удалось завершить установку модели людей.")
                if (!verified()) throw IOException("Не прошла финальная проверка модели людей.")
                onProgress(100)
            } finally {
                connection.disconnect()
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } finally {
            part.delete()
        }
    }

    suspend fun uninstall() = withContext(Dispatchers.IO) {
        for (f in listOf(target, File(folder, "$FILENAME.part"))) {
            if (f.exists() && !f.delete()) throw IOException("Не удалось удалить модель людей.")
        }
    }

    private fun verified(): Boolean {
        if (!installed()) return false
        val digest = MessageDigest.getInstance("SHA-256")
        target.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == SHA256
    }
}
