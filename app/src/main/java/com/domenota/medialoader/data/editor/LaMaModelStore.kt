package com.domenota.medialoader.data.editor

import android.content.Context
import android.os.StatFs
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * The model is optional. Photos never leave the device; only the model file is downloaded,
 * after an explicit user action. Checksum is pinned to the model publisher's card.
 */
class LaMaModelStore(context: Context) {
    private val folder = File(context.applicationContext.filesDir, "inpainting-models")
    private val model = File(folder, "lama_fp32.onnx")

    fun installed(): Boolean = model.isFile && model.length() > 1_000_000L

    /** Throws when absent: network access is NOT implicitly initiated by editing. */
    fun requireInstalled(): File {
        if (!installed()) throw IOException("Модель LaMa ещё не установлена.")
        return model
    }

    suspend fun install(onProgress: (Int) -> Unit = {}): File = withContext(Dispatchers.IO) {
        folder.mkdirs()
        if (installed() && sha256(model) == SHA256) return@withContext model
        // Leave room for the downloaded model, ONNX optimizations and inference buffers.
        if (StatFs(folder.absolutePath).availableBytes < MIN_FREE_BYTES) {
            throw IOException("Для установки модели нужно не менее 750 МБ свободного места.")
        }
        val part = File.createTempFile("lama-", ".download", folder)
        try {
            val connection = URL(MODEL_URL).openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = true
                connection.connectTimeout = 15_000
                connection.readTimeout = 60_000
                connection.setRequestProperty("Accept", "application/octet-stream")
                val status = connection.responseCode
                if (status !in 200..299) throw IOException("Не удалось получить модель (HTTP $status).")
                if (connection.contentLengthLong > MAX_MODEL_BYTES) throw IOException("Модель превышает ограничение размера.")
                val length = connection.contentLengthLong
                var count = 0L
                val digest = MessageDigest.getInstance("SHA-256")
                connection.inputStream.use { input ->
                    FileOutputStream(part).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            count += n
                            if (count > MAX_MODEL_BYTES) throw IOException("Модель превышает ограничение размера.")
                            digest.update(buffer, 0, n)
                            output.write(buffer, 0, n)
                            if (length > 0) onProgress((count * 100 / length).toInt().coerceIn(0, 99))
                        }
                        output.fd.sync()
                    }
                }
                if (count < 1_000_000L || digest.digest().joinToString("") { "%02x".format(it) } != SHA256) {
                    throw IOException("Не удалось проверить подлинность модели (SHA-256).")
                }
                if (model.exists() && !model.delete()) throw IOException("Не удалось заменить прежнюю модель.")
                if (!part.renameTo(model)) throw IOException("Не удалось сохранить модель.")
                onProgress(100)
                model
            } finally {
                connection.disconnect()
            }
        } finally {
            part.delete()
        }
    }

    companion object {
        const val MODEL_NAME = "LaMa (ONNX, 512 × 512)"
        const val SHA256 = "1faef5301d78db7dda502fe59966957ec4b79dd64e16f03ed96913c7a4eb68d6"
        private const val MODEL_URL =
            "https://huggingface.co/sapienkit/LaMa-ONNX/resolve/main/lama_fp32.onnx?download=true"
        private const val MAX_MODEL_BYTES = 500L * 1024 * 1024
        private const val MIN_FREE_BYTES = 750L * 1024 * 1024

        private fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
