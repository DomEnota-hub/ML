package com.domenota.medialoader.data.editor

import android.content.Context
import android.os.StatFs
import com.domenota.medialoader.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * A real, independently validated DExNet + pretrained VGG19 ONNX model.
 *
 * Model weights are NOT stored in Git: QA build must recreate the ONNX from
 * pinned public checkpoints, pass strict CPU parity/quality/RAM tests, then
 * place BOTH .onnx and its SHA256 into the signed APK assets.
 *
 * No false install: if the audited asset is missing, the reflection mode stays
 * unavailable and this store throws. A user explicitly installs the asset to
 * app-private storage before inference. No photo or weights go to a server.
 *
 * Inference/license provenance: docs/qa/stage4_reflection_model_gate_20261109.md
 */
internal class ReflectionDExModelStore(context: Context) {
    private val appContext = context.applicationContext
    private val folder = File(appContext.filesDir, "ai-models/dexnet-vgg19-128")
    private val installed = File(folder, MODEL_NAME)

    companion object {
        const val MODEL_NAME = "dexnet_vgg19_reflection_128.onnx"
        const val ASSET_PATH = "ai/$MODEL_NAME"
        const val SHA_PATH = "$ASSET_PATH.sha256"
        const val MIN_BYTES = 88_000_000L
        const val MAX_BYTES = 110_000_000L
        private const val MIN_FREE_BYTES = 220L * 1024 * 1024
    }

    fun bundled(): Boolean = BuildConfig.REFLECTION_MODEL_BUNDLED

    fun installed(): Boolean =
        bundled() && installed.isFile && installed.length() in MIN_BYTES..MAX_BYTES

    private fun expectedHash(): String {
        if (!bundled()) throw IOException(
            "Модель отражений не вошла в эту сборку. Обновите QA-приложение.")
        val hash = appContext.assets.open(SHA_PATH)
            .bufferedReader(Charsets.US_ASCII).use { it.readText().trim().lowercase() }
        if (!hash.matches(Regex("[0-9a-f]{64}")))
            throw IOException("Неверный контрольный SHA-256 модели отражений.")
        return hash
    }

    suspend fun verifyInstalled(): Boolean = withContext(Dispatchers.IO) {
        if (!installed()) return@withContext false
        try {
            val expected = expectedHash()
            installed.inputStream().use { source ->
                hashBytes(source) == expected
            }
        } catch (_: IOException) {
            false
        }
    }

    suspend fun requireInstalled(): File = withContext(Dispatchers.IO) {
        if (!verifyInstalled()) throw IOException(
            "DExNet отсутствует или повреждена. Установите её через «AI-модели».")
        installed
    }

    suspend fun install(onProgress: (Int) -> Unit = {}) = withContext(Dispatchers.IO) {
        val expected = expectedHash()
        if (verifyInstalled()) {
            onProgress(100)
            return@withContext
        }
        if (!folder.isDirectory && !folder.mkdirs()) throw IOException(
            "Не удалось создать каталог модели отражений.")
        if (StatFs(folder.absolutePath).availableBytes < MIN_FREE_BYTES) throw IOException(
            "Для установки модели отражений необходимо 220 МБ свободного места.")
        val part = File(folder, "$MODEL_NAME.part")
        part.delete()
        try {
            var copied = 0L
            val digest = MessageDigest.getInstance("SHA-256")
            appContext.assets.open(ASSET_PATH).use { source ->
                FileOutputStream(part).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = source.read(buffer)
                        if (n < 0) break
                        copied += n
                        if (copied > MAX_BYTES) throw IOException(
                            "Файл модели отражений превышает безопасный размер.")
                        output.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        onProgress((copied * 100 / 91_341_128L)
                            .toInt().coerceIn(0, 99))
                    }
                    output.fd.sync()
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (copied !in MIN_BYTES..MAX_BYTES || actual != expected)
                throw IOException("Контрольная сумма DExNet не совпадает.")
            if (installed.exists() && !installed.delete())
                throw IOException("Не удалось заменить прежнюю DExNet.")
            if (!part.renameTo(installed))
                throw IOException("Не удалось завершить установку DExNet.")
            if (!verifyInstalled()) throw IOException("Повторная проверка модели не пройдена.")
            onProgress(100)
        } catch (cancel: CancellationException) {
            throw cancel
        } finally {
            part.delete()
        }
    }

    suspend fun uninstall() = withContext(Dispatchers.IO) {
        for (file in listOf(installed, File(folder, "$MODEL_NAME.part"))) {
            if (file.isFile && !file.delete())
                throw IOException("Не удалось удалить модель отражений.")
        }
    }

    private fun hashBytes(source: java.io.InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = source.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
