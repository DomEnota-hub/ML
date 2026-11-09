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
 * Optional MobileSAM encoder + decoder, downloaded only after explicit consent.
 * No photo pixels or other user data are uploaded. Exact immutable upstream
 * revision, byte count and SHA-256 are pinned for EACH component.
 *
 * Both components must pass verification: a half-downloaded installation is
 * never advertised as ready. No weights or .part files are shipped in the APK.
 */
class MobileSamModelStore(context: Context) {
    private val folder = File(context.applicationContext.filesDir, "ai-models/mobile-sam-v1")

    internal data class Blob(
        val filename: String,
        val bytes: Long,
        val sha256: String,
    ) {
        val url: String get() =
            "https://huggingface.co/Acly/MobileSAM/resolve/$REVISION/$filename?download=true"
    }

    companion object {
        const val REVISION = "0d3b403339b4674a82493d5e97964dd78089ddc8"
        const val ENCODER_SHA256 =
            "580f5fb648ea1062c0aabc26217aed56921985f03f0cbbd852bba81d760cc749"
        const val DECODER_SHA256 =
            "93915fc7c993ab9d59ab8c9ccd3bce37f7509c81ab4150a74abd4d2abbd8570d"
        const val ENCODER_BYTES = 28_157_093L
        const val DECODER_BYTES = 16_501_323L
        const val TOTAL_BYTES = ENCODER_BYTES + DECODER_BYTES
        // Free room for both files, a temporary file and ORT's runtime memory.
        const val MIN_DISK_BYTES = 160L * 1024 * 1024
    }

    private val blobs = listOf(
        Blob("mobile_sam_image_encoder.onnx", ENCODER_BYTES, ENCODER_SHA256),
        Blob("sam_mask_decoder_single.onnx", DECODER_BYTES, DECODER_SHA256),
    )

    /** Fast UI status, not a trust boundary. See verifyInstalled(). */
    fun installed(): Boolean = blobs.all { b ->
        val f = File(folder, b.filename)
        f.isFile && f.length() == b.bytes
    }

    /** Never open an unverified model in ONNX Runtime. */
    suspend fun verifyInstalled(): Boolean = withContext(Dispatchers.IO) {
        blobs.all { b -> verifiedFile(b) }
    }

    suspend fun requireInstalled(): Pair<File, File> = withContext(Dispatchers.IO) {
        if (!blobs.all { verifiedFile(it) }) {
            throw IOException("MobileSAM не установлена или нарушена целостность файлов.")
        }
        Pair(File(folder, blobs[0].filename), File(folder, blobs[1].filename))
    }

    suspend fun install(onProgress: (Int) -> Unit = {}): Unit = withContext(Dispatchers.IO) {
        if (!folder.isDirectory && !folder.mkdirs())
            throw IOException("Не удалось создать каталог AI-моделей.")
        if (StatFs(folder.absolutePath).availableBytes < MIN_DISK_BYTES) {
            throw IOException("Для MobileSAM нужно не менее 160 МБ свободного места.")
        }
        var completed = 0L
        for (blob in blobs) {
            currentCoroutineContext().ensureActive()
            if (verifiedFile(blob)) {
                completed += blob.bytes
                onProgress(((completed * 100) / TOTAL_BYTES).toInt().coerceIn(0, 100))
                continue
            }
            val target = File(folder, blob.filename)
            val partial = File(folder, blob.filename + ".part")
            partial.delete()
            try {
                val connection = URL(blob.url).openConnection() as HttpURLConnection
                try {
                    connection.instanceFollowRedirects = true
                    connection.connectTimeout = 20_000
                    connection.readTimeout = 60_000
                    connection.setRequestProperty("Accept", "application/octet-stream")
                    val status = connection.responseCode
                    if (status !in 200..299) throw IOException("Ошибка загрузки MobileSAM: HTTP $status")
                    val declared = connection.contentLengthLong
                    if (declared > blob.bytes) throw IOException("Неверный размер модели MobileSAM.")
                    val digest = MessageDigest.getInstance("SHA-256")
                    var readTotal = 0L
                    connection.inputStream.use { input ->
                        FileOutputStream(partial).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val n = input.read(buffer)
                                if (n < 0) break
                                readTotal += n
                                if (readTotal > blob.bytes)
                                    throw IOException("Превышен ожидаемый размер модели.")
                                digest.update(buffer, 0, n)
                                output.write(buffer, 0, n)
                                onProgress((((completed + readTotal) * 100) / TOTAL_BYTES)
                                    .toInt().coerceIn(0, 99))
                            }
                            output.fd.sync()
                        }
                    }
                    val actual = digest.digest().joinToString("") { "%02x".format(it) }
                    if (readTotal != blob.bytes || actual != blob.sha256)
                        throw IOException("Проверка SHA-256 модели MobileSAM не пройдена.")
                    // Close native sessions after SHA validation, before replacing either file.
                    OrtSessions.samEncoder.release()
                    OrtSessions.samDecoder.release()
                    // Never promote a truncated .part file. Only valid bytes reach the final name.
                    if (target.exists() && !target.delete())
                        throw IOException("Не удалось заменить старый файл модели.")
                    if (!partial.renameTo(target))
                        throw IOException("Не удалось установить файл MobileSAM.")
                    completed += blob.bytes
                } finally {
                    connection.disconnect()
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } finally {
                partial.delete()
            }
        }
        if (!blobs.all { verifiedFile(it) })
            throw IOException("Набор MobileSAM не прошёл завершающую проверку.")
        onProgress(100)
    }

    suspend fun uninstall() = withContext(Dispatchers.IO) {
        OrtSessions.samEncoder.release()
        OrtSessions.samDecoder.release()
        for (blob in blobs) {
            for (name in listOf(blob.filename, blob.filename + ".part")) {
                val f = File(folder, name)
                if (f.exists() && !f.delete())
                    throw IOException("Не удалось удалить файл модели: $name")
            }
        }
    }

    private fun verifiedFile(blob: Blob): Boolean {
        val file = File(folder, blob.filename)
        if (!file.isFile || file.length() != blob.bytes) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val size = stream.read(buffer)
                if (size < 0) break
                digest.update(buffer, 0, size)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == blob.sha256
    }
}
