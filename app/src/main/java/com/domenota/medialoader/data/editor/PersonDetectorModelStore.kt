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
 * Official MediaPipe Tasks model with required TFLite metadata:
 * https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/float32/1/efficientdet_lite0.tflite
 * SHA-256 and byte count independently matched by an Apache-2.0 model mirror:
 * https://huggingface.co/ketiswp/mediapipe-EfficientDet-Lite0-COCO2017-fp32-onnx
 * Weight is NOT included in the APK and never silently downloaded.
 */
internal class PersonDetectorModelStore(context: Context) {
    private val folder = File(context.applicationContext.filesDir, "ai-models/efficientdet-lite0-v1")
    private val target = File(folder, FILENAME)

    companion object {
        const val REVISION = "float32/1"
        const val FILENAME = "efficientdet_lite0.tflite"
        const val SHA256 = "40338edf5ec70d43e318b0a716a84d4564cd1802759a7a07170c7e43796dbf58"
        const val MODEL_BYTES = 13_836_895L
        const val URL_PATH = "https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/$REVISION/$FILENAME"
        const val MAX_BYTES = 16L * 1024 * 1024
        const val MIN_DISK_BYTES = 48L * 1024 * 1024
    }

    fun installed(): Boolean = target.isFile && target.length() == MODEL_BYTES

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
                if (length > MAX_BYTES || length in 0..MAX_BYTES && length != MODEL_BYTES)
                    throw IOException("Неверный размер модели людей.")
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
                if (downloaded != MODEL_BYTES || actual != SHA256)
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
