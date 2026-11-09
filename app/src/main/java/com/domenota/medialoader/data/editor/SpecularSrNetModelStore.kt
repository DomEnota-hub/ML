package com.domenota.medialoader.data.editor

import android.content.Context
import android.os.StatFs
import android.util.Base64
import android.util.Base64InputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Genuine MIT SRNet weights converted to ONNX from the original PyTorch checkpoint.
 * At ~230 KiB the verified model ships as an encoded app asset and is installed
 * into private storage ONLY on explicit user action. No private photos or model
 * downloads touch the network. Uninstallation removes the active local copy.
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
    }

    fun installed(): Boolean = model.isFile && model.length() == MODEL_BYTES

    suspend fun verifyInstalled(): Boolean = withContext(Dispatchers.IO) { verified() }

    suspend fun requireInstalled(): File = withContext(Dispatchers.IO) {
        if (!verified()) throw IOException("SRNet отсутствует или модель повреждена. Установите её через «AI-модели».")
        model
    }

    suspend fun install(onProgress: (Int) -> Unit = {}) = withContext(Dispatchers.IO) {
        if (!folder.isDirectory && !folder.mkdirs())
            throw IOException("Не удалось создать папку модели SRNet.")
        if (verified()) {
            onProgress(100)
            return@withContext
        }
        if (StatFs(folder.absolutePath).availableBytes < MIN_FREE_BYTES)
            throw IOException("Недостаточно свободного места для модели SRNet.")
        val part = File(folder, "$MODEL_NAME.part")
        part.delete()
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            appContext.assets.open(ASSET_PATH).use { asset ->
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
                throw IOException("Встроенная модель SRNet не прошла SHA-256.")
            OrtSessions.srnet.release()
            if (model.exists() && !model.delete())
                throw IOException("Не удалось заменить существующую модель SRNet.")
            if (!part.renameTo(model))
                throw IOException("Не удалось установить модель SRNet.")
            if (!verified()) throw IOException("SRNet не прошла финальную проверку.")
            onProgress(100)
        } finally {
            part.delete()
        }
    }

    suspend fun uninstall() = withContext(Dispatchers.IO) {
        OrtSessions.srnet.release()
        for (f in listOf(model, File(folder, "$MODEL_NAME.part"))) {
            if (f.exists() && !f.delete()) throw IOException("Не удалось удалить модель SRNet.")
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
