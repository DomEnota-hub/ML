package com.domenota.medialoader.data.editor

import android.content.Context
import android.net.Uri
import android.os.StatFs
import com.domenota.medialoader.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Exact verified DExNet. Local assembly fetches original checkpoints and a weight-free
 * graph recipe. Existing verified import and optional bundled install remain available. */
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
        const val EXACT_BYTES = 91_341_128L
        const val EXPECTED_SHA256 = "0898eefd440b3adfc13b32b49b14c94e1acbf9b5ad5270f4022513b891a90917"
        private const val MIN_FREE_BYTES = 220L * 1024 * 1024
        private val gate = Mutex()
    }

    fun bundled(): Boolean = BuildConfig.REFLECTION_MODEL_BUNDLED

    fun installed(): Boolean =
        installed.isFile && installed.length() == EXACT_BYTES

    private fun expectedHash(): String {
        // Audited real pretrained ONNX. Import never trusts a file name or MIME.
        if (bundled()) {
            val assetHash = appContext.assets.open(SHA_PATH)
                .bufferedReader(Charsets.US_ASCII).use { it.readText().trim().lowercase() }
            if (assetHash != EXPECTED_SHA256) throw IOException(
                "Встроенная модель не соответствует проверенной DExNet.")
        }
        return EXPECTED_SHA256
    }

    suspend fun verifyInstalled(): Boolean = withContext(Dispatchers.IO) {
        if (gate.tryLock()) {
            try { File(folder, ".assembly").deleteRecursively() } finally { gate.unlock() }
        }
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
        gate.withLock {
            if (verifyInstalled()) { onProgress(100); return@withLock }
            val expected = expectedHash()
            installStream(expected, { appContext.assets.open(ASSET_PATH) }, onProgress)
        }
    }

    /** Explicit SAF import of the same SHA-pinned ONNX, outside the base APK. */
    suspend fun installFromUri(uri: Uri, onProgress: (Int) -> Unit = {}) = withContext(Dispatchers.IO) {
        gate.withLock {
            if (verifyInstalled()) { onProgress(100); return@withLock }
            val expected = expectedHash()
            installStream(expected, {
                appContext.contentResolver.openInputStream(uri) ?: throw IOException("Не удалось открыть файл модели.")
            }, onProgress)
        }
    }

    private suspend fun installStream(expected: String, open: () -> java.io.InputStream, onProgress: (Int) -> Unit) {
        if (!folder.isDirectory && !folder.mkdirs()) throw IOException("Не удалось создать каталог модели отражений.")
        if (StatFs(folder.absolutePath).availableBytes < MIN_FREE_BYTES)
            throw IOException("Для установки модели нужно 220 МБ свободного места.")
        val part = File(folder, "$MODEL_NAME.part")
        if (part.exists() && !part.delete()) throw IOException("Не удалось удалить временную модель.")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            open().use { input ->
                FileOutputStream(part).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        copied += n
                        if (n == 0 || copied > EXACT_BYTES) throw IOException("Неверный размер DExNet.")
                        output.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        onProgress((copied * 100 / EXACT_BYTES).toInt().coerceIn(0, 99))
                    }
                    output.fd.sync()
                }
            }
            if (copied != EXACT_BYTES || ModelRangeAssembler.hex(digest.digest()) != expected)
                throw IOException("Неверная DExNet + VGG19: размер или SHA-256 не совпал.")
            activate(part, onProgress)
        } finally { part.delete() }
    }

    suspend fun downloadAndAssemble(onProgress: (Int) -> Unit = {}) = withContext(Dispatchers.IO) {
        gate.withLock {
            if (verifyInstalled()) { onProgress(100); return@withLock }
            if (!folder.isDirectory && !folder.mkdirs()) throw IOException("Не удалось создать каталог DExNet.")
            if (StatFs(folder.absolutePath).availableBytes < DExNetLocalInstaller.MIN_FREE_BYTES)
                throw IOException("Для сборки DExNet нужно 900 МБ свободного места.")
            val staging = File(folder, ".assembly")
            if (staging.exists() && !staging.deleteRecursively()) throw IOException("Не удалось очистить временные файлы модели.")
            if (!staging.mkdirs()) throw IOException("Не удалось создать временный каталог модели.")
            try {
                val result = DExNetLocalInstaller.build(staging, onProgress)
                activate(result, onProgress)
            } finally { staging.deleteRecursively() }
        }
    }

    private suspend fun activate(part: File, onProgress: (Int) -> Unit) {
        currentCoroutineContext().ensureActive()
        OrtSessions.dexnet.release()
        // Same private filesystem. Never unlink the old file before replacing it.
        if (!part.renameTo(installed)) throw IOException("Не удалось завершить установку DExNet.")
        if (!verifyInstalled()) throw IOException("Установленная DExNet не прошла SHA-256.")
        onProgress(100)
    }

    suspend fun uninstall() = withContext(Dispatchers.IO) {
        gate.withLock {
            OrtSessions.dexnet.release()
            for (file in listOf(installed, File(folder, "$MODEL_NAME.part"))) {
                if (file.isFile && !file.delete()) throw IOException("Не удалось удалить модель отражений.")
            }
            val staging = File(folder, ".assembly")
            if (staging.exists() && !staging.deleteRecursively())
                throw IOException("Не удалось удалить временные файлы модели.")
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
