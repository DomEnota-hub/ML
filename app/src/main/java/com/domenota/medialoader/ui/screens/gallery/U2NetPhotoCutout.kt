package com.domenota.medialoader.ui.screens.gallery

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.domenota.medialoader.core.logging.AppLog
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.FloatBuffer
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Android 16 alternative to ML Kit Subject Segmentation 16.0.0-beta1.
 *
 * The Play-services model has a documented fatal SIGSEGV during native inference
 * on Android 16. Neither try/catch nor waiting for a module can prevent SIGSEGV.
 * U²-Netp runs entirely locally on ONNX Runtime's CPU execution provider and
 * produces one combined foreground (individual object segmentation is not supported).
 *
 * Only the model file is downloaded. No photographs or inference results leave
 * this device. The pinned digest protects against changed / corrupted downloads.
 */
internal object U2NetPhotoCutout {
    const val INPUT_SIZE = 320
    const val MAX_IMAGE_EDGE = 1600
    const val MODEL_SHA256 = "309c8469258dda742793dce0ebea8e6dd393174f89934733ecc8b14c76f4ddd8"
    private const val MODEL_SIZE = 4_574_861L
    private const val MAX_DOWNLOAD_BYTES = 10L * 1024 * 1024
    private const val MODEL_URL =
        "https://huggingface.co/Heliosoph/u2net-onnx/resolve/7fc34de/u2netp.onnx?download=1"
    private const val LOG = "PhotoCutout"
    private val installationLock = Mutex()
    private val inferenceLock = Mutex()
    private val channelMean = floatArrayOf(.485f, .456f, .406f)
    private val channelStd = floatArrayOf(.229f, .224f, .225f)

    /** Used by UI and unit tests to keep the unsafe native backend off Android 16. */
    fun usesOnnx(sdk: Int): Boolean = sdk >= 36

    suspend fun prepare(context: Context): File = installationLock.withLock {
        withContext(Dispatchers.IO) {
            val folder = File(context.applicationContext.filesDir, "cutout-models")
            check(folder.isDirectory || folder.mkdirs()) { "Не удалось создать каталог модели." }
            val model = File(folder, "u2netp.onnx")
            if (model.length() == MODEL_SIZE && digest(model) == MODEL_SHA256) {
                return@withContext model
            }
            val pending = File.createTempFile("u2netp-", ".tmp", folder)
            try {
                AppLog.i(LOG, "ONNX cutout model download started")
                val connection = URL(MODEL_URL).openConnection() as HttpURLConnection
                try {
                    connection.instanceFollowRedirects = true
                    connection.connectTimeout = 20_000
                    connection.readTimeout = 60_000
                    connection.setRequestProperty("Accept", "application/octet-stream")
                    val response = connection.responseCode
                    if (response !in 200..299) throw IOException("Не удалось загрузить модель, HTTP " + response)
                    val advertised = connection.contentLengthLong
                    if (advertised > MAX_DOWNLOAD_BYTES) throw IOException("Неверный размер модели")
                    val sha = MessageDigest.getInstance("SHA-256")
                    var length = 0L
                    connection.inputStream.use { input ->
                        FileOutputStream(pending).use { output ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buf)
                                if (count < 0) break
                                length += count
                                if (length > MAX_DOWNLOAD_BYTES) throw IOException("Превышен размер модели")
                                sha.update(buf, 0, count)
                                output.write(buf, 0, count)
                            }
                            output.fd.sync()
                        }
                    }
                    val actualSha = sha.digest().joinToString("") { "%02x".format(it) }
                    if (length != MODEL_SIZE || actualSha != MODEL_SHA256) {
                        throw IOException("Контрольная сумма модели не совпадает, загрузка отменена")
                    }
                    if (model.exists() && !model.delete()) throw IOException("Не удалось заменить повреждённую модель")
                    if (!pending.renameTo(model)) throw IOException("Не удалось сохранить модель")
                    AppLog.i(LOG, "ONNX cutout model installed and SHA-256 verified")
                    model
                } finally {
                    connection.disconnect()
                }
            } finally {
                pending.delete()
            }
        }
    }

    suspend fun segment(context: Context, original: Bitmap): GalleryPhotoTools.Cutout =
        inferenceLock.withLock {
            val model = prepare(context)
            withContext(Dispatchers.Default) {
                currentCoroutineContext().ensureActive()
                AppLog.i(LOG, "ONNX CPU cutout started, width=" + original.width + ", height=" + original.height)
                val start = System.nanoTime()
                val input = inputTensor(original)
                val environment = OrtEnvironment.getEnvironment()
                val mask = OrtSession.SessionOptions().use { options ->
                    options.setIntraOpNumThreads(2)
                    options.setInterOpNumThreads(1)
                    environment.createSession(model.absolutePath, options).use { session ->
                        OnnxTensor.createTensor(
                            environment, FloatBuffer.wrap(input),
                            longArrayOf(1L, 3L, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
                        ).use { tensor ->
                            val inputName = session.inputNames.first()
                            session.run(mapOf(inputName to tensor)).use { results ->
                                currentCoroutineContext().ensureActive()
                                @Suppress("UNCHECKED_CAST")
                                val output = results[0].value as Array<Array<Array<FloatArray>>>
                                val rows = output[0][0]
                                check(rows.size == INPUT_SIZE && rows.all { it.size == INPUT_SIZE }) {
                                    "Модель вернула маску неожиданного размера"
                                }
                                FloatArray(INPUT_SIZE * INPUT_SIZE).apply {
                                    for (y in 0 until INPUT_SIZE) {
                                        rows[y].copyInto(this, y * INPUT_SIZE)
                                    }
                                }
                            }
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                val foreground = composeForeground(original, mask)
                AppLog.i(LOG, "ONNX CPU cutout completed, ms=" + (System.nanoTime() - start) / 1_000_000)
                GalleryPhotoTools.Cutout(foreground, emptyList())
            }
        }

    /** Pure preprocessing for unit tests, channels-first with ImageNet normalization. */
    fun inputTensor(original: Bitmap): FloatArray {
        val scaled = Bitmap.createScaledBitmap(original, INPUT_SIZE, INPUT_SIZE, true)
        try {
            val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
            scaled.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
            val plane = INPUT_SIZE * INPUT_SIZE
            return FloatArray(plane * 3).also { data ->
                for (i in pixels.indices) {
                    val p = pixels[i]
                    data[i] = channel((p ushr 16) and 255, 0)
                    data[plane + i] = channel((p ushr 8) and 255, 1)
                    data[plane * 2 + i] = channel(p and 255, 2)
                }
            }
        } finally {
            if (scaled !== original) scaled.recycle()
        }
    }

    private fun channel(value: Int, index: Int): Float =
        (value / 255f - channelMean[index]) / channelStd[index]

    /** Do not hard-clip alpha: retain fine edges like hair/antennae where possible. */
    fun alphaForValue(value: Float, min: Float, max: Float): Int {
        if (!value.isFinite() || !min.isFinite() || !max.isFinite() || max - min < 1e-7f) return 0
        return (((value - min) / (max - min)).coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255)
    }

    fun composeForeground(original: Bitmap, rawMask: FloatArray): Bitmap {
        require(rawMask.size == INPUT_SIZE * INPUT_SIZE)
        var min = Float.POSITIVE_INFINITY
        var max = Float.NEGATIVE_INFINITY
        rawMask.forEach { value ->
            if (value.isFinite()) {
                if (value < min) min = value
                if (value > max) max = value
            }
        }
        check(min.isFinite() && max.isFinite() && max - min >= 1e-7f) {
            "Не удалось определить передний план. Попробуйте другое фото."
        }
        val argb = IntArray(INPUT_SIZE * INPUT_SIZE)
        for (i in argb.indices) {
            val a = alphaForValue(rawMask[i], min, max)
            argb[i] = Color.argb(a, 255, 255, 255)
        }
        val small = Bitmap.createBitmap(argb, INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
        val enlarged = Bitmap.createScaledBitmap(small, original.width, original.height, true)
        try {
            val output = Bitmap.createBitmap(original.width, original.height, Bitmap.Config.ARGB_8888)
            val sourceRow = IntArray(original.width)
            val maskRow = IntArray(original.width)
            for (y in 0 until original.height) {
                original.getPixels(sourceRow, 0, original.width, 0, y, original.width, 1)
                enlarged.getPixels(maskRow, 0, original.width, 0, y, original.width, 1)
                for (x in sourceRow.indices) {
                    val originalAlpha = (sourceRow[x] ushr 24) and 255
                    val maskAlpha = (maskRow[x] ushr 24) and 255
                    val a = originalAlpha * maskAlpha / 255
                    sourceRow[x] = (a shl 24) or (sourceRow[x] and 0x00ffffff)
                }
                output.setPixels(sourceRow, 0, original.width, 0, y, original.width, 1)
            }
            return output
        } finally {
            if (enlarged !== small) enlarged.recycle()
            small.recycle()
        }
    }

    private fun digest(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                hash.update(buf, 0, n)
            }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
}
