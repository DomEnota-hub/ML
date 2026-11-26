package com.domenota.medialoader.data.editor

import android.content.Context
import android.os.StatFs
import android.util.Base64
import android.util.Base64InputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.FilterInputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Genuine MIT SRNet weights converted to ONNX from the original PyTorch checkpoint.
 * At ~230 KiB the verified model ships as an encoded app asset and is installed
 * into private storage ONLY on explicit user action. The identical pinned model
 * is also available from ML-models; the offline asset remains available.
 * Uninstallation removes the active local copy.
 *
 * Source code and weights:
 * https://github.com/zhiyiYo/Specular-Removal
 * Source revision: 6f44a130d2b20568f1a1b1159e32a4ec6b46ef19
 * Conversion CI: https://github.com/DomEnota-hub/ML/actions/runs/42581463302
 */
internal class SpecularSrNetModelStore(context: Context) {
    private val appContext = context.applicationContext
    private val folder = File(appContext.filesDir, "ai-models/specular-srnet-v1")
    private val model = File(folder, MODEL_NAME)

    companion object {
        const val MODEL_NAME = "specular_srnet_256.onnx"
        const val ASSET_PATH = "ai/specular_srnet_256.onnx.b64"
        const val MODEL_BYTES = 230_221L
        const val MODEL_SHA256 =
            "1538f5800858c329e93576566d40f568fcc07d8fdc674bca454fbfc4771c63e4"
        private const val MIN_FREE_BYTES = 8L * 1024 * 1024
        private val gate = Mutex()
        private const val MAX_ENCODED_BYTES = 400_000L
        private const val DOWNLOAD_URL = "https://raw.githubusercontent.com/DomEnota-hub/ML-models/3a09edfdab217e8ea294d69a134a06793cc41658/srnet/1/specular_srnet_256.onnx.b64"
    }

    fun installed(): Boolean = model.isFile && model.length() == MODEL_BYTES

    suspend fun verifyInstalled(): Boolean = withContext(Dispatchers.IO) { verified() }

    suspend fun requireInstalled(): File = withContext(Dispatchers.IO) {
        if (!verified()) throw IOException("SRNet отсутствует или модель повреждена. Установите её через «AI-модели».")
        model
    }

    suspend fun install(onProgress: (Int) -> Unit = {}) = withContext(Dispatchers.IO) {
        gate.withLock { installLocked({ appContext.assets.open(ASSET_PATH) }, onProgress) }
    }

    /** A failed network installation never authorizes different weights. */
    suspend fun download(onProgress: (Int) -> Unit = {}): Unit = withContext(Dispatchers.IO) {
        gate.withLock {
            if (verified()) { onProgress(100); return@withLock }
            val connection = URL(DOWNLOAD_URL).openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                if (connection.responseCode != HttpURLConnection.HTTP_OK)
                    throw IOException("Скачивание SRNet недоступно.")
                if (connection.contentLengthLong > MAX_ENCODED_BYTES)
                    throw IOException("Неверный размер SRNet.")
                installLocked({
                    object : FilterInputStream(connection.inputStream) {
                        private var total = 0L
                        private fun count(n: Int) {
                            if (n > 0) total += n
                            if (total > MAX_ENCODED_BYTES) throw IOException("Слишком большой файл SRNet.")
                        }
                        override fun read(): Int = super.read().also { if (it >= 0) count(1) }
                        override fun read(b: ByteArray, off: Int, len: Int): Int =
                            super.read(b, off, len).also(::count)
                    }
                }, onProgress)
            } finally { connection.disconnect() }
        }
    }

    private suspend fun installLocked(openSource: () -> InputStream, onProgress: (Int) -> Unit) {
        if (!folder.isDirectory && !folder.mkdirs())
            throw IOException("Не удалось создать папку модели SRNet.")
        if (verified()) {
            onProgress(100)
            return
        }
        if (StatFs(folder.absolutePath).availableBytes < MIN_FREE_BYTES)
            throw IOException("Недостаточно свободного места для модели SRNet.")
        val part = File(folder, "$MODEL_NAME.part")
        part.delete()
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            openSource().use { asset ->
                Base64InputStream(asset, Base64.DEFAULT).use { input ->
                    FileOutputStream(part).use { output ->
                        val buf = ByteArray(32 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val bytes = input.read(buf)
                            if (bytes < 0) break
                            total += bytes
                            if (total > MODEL_BYTES)
                                throw IOException("Файл SRNet имеет неверный размер.")
                            digest.update(buf, 0, bytes)
                            output.write(buf, 0, bytes)
                            onProgress((total * 100 / MODEL_BYTES).toInt().coerceIn(0, 99))
                        }
                        output.fd.sync()
                    }
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            if (total != MODEL_BYTES || hash != MODEL_SHA256)
                throw IOException("Модель SRNet не прошла SHA-256.")
            OrtSessions.srnet.release()
            if (!part.renameTo(model))
                throw IOException("Не удалось установить модель SRNet.")
            if (!verified()) throw IOException("SRNet не прошла финальную проверку.")
            onProgress(100)
        } finally {
            part.delete()
        }
    }

    suspend fun uninstall() = withContext(Dispatchers.IO) {
        gate.withLock {
            OrtSessions.srnet.release()
            for (f in listOf(model, File(folder, "$MODEL_NAME.part"))) {
                if (f.exists() && !f.delete()) throw IOException("Не удалось удалить модель SRNet.")
            }
        }
    }

    private fun verified(): Boolean {
        if (!installed()) return false
        val digest = MessageDigest.getInstance("SHA-256")
        model.inputStream().buffered().use { source ->
            val buf = ByteArray(32 * 1024)
            while (true) {
                val n = source.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == MODEL_SHA256
    }
}
