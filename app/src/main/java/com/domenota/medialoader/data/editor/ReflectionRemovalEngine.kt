package com.domenota.medialoader.data.editor

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.Color
import java.io.IOException
import java.nio.FloatBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Real trained DExNet + VGG19 ONNX reflection removal (128px CPU), not LaMa,
 * a blur filter or an imaginary segmentation network.
 *
 * The model predicts its transmission layer (scene behind glass) and the
 * reflection layer. Its RGB residual is applied to the ORIGINAL full-res pixels,
 * preserving high-frequency detail. The user controls the residual strength.
 *
 * Model limitations: strong, opaque reflections cannot recover unseen pixels;
 * the network may make some scenes worse. Fail closed on damaged weights, tensor
 * shapes and missing license-cleared model assets.
 */
internal class ReflectionRemovalEngine internal constructor(
    private val runner: ReflectionModelRunner,
) {
    constructor(store: ReflectionDExModelStore) : this(ReflectionOnnxRunner(store))

    companion object {
        const val SIDE = 128
        const val PIXELS = SIDE * SIDE
    }

    suspend fun remove(source: Bitmap, strength: Float = .75f): Bitmap =
        withContext(Dispatchers.Default) {
            require(source.width > 0 && source.height > 0)
            require(strength.isFinite() && strength in 0.1f..1f)
            val scale = SIDE.toFloat() / max(source.width, source.height)
            val w = (source.width * scale).roundToInt().coerceIn(1, SIDE)
            val h = (source.height * scale).roundToInt().coerceIn(1, SIDE)
            val px = (SIDE - w) / 2
            val py = (SIDE - h) / 2
            val preview = Bitmap.createScaledBitmap(source, w, h, true)
            val previewPixels = IntArray(w * h)
            try {
                preview.getPixels(previewPixels, 0, w, 0, 0, w, h)
            } finally {
                if (preview !== source) preview.recycle()
            }
            val input = FloatArray(3 * PIXELS)
            for (y in 0 until SIDE) for (x in 0 until SIDE) {
                val c = previewPixels[
                    (y - py).coerceIn(0, h - 1) * w +
                    (x - px).coerceIn(0, w - 1)]
                val p = y * SIDE + x
                input[p] = Color.red(c) / 255f
                input[PIXELS + p] = Color.green(c) / 255f
                input[2 * PIXELS + p] = Color.blue(c) / 255f
            }
            currentCoroutineContext().ensureActive()
            val transmission = runner.predictTransmission(input)
            if (transmission.size != 3 * PIXELS || transmission.any { !it.isFinite() })
                throw IOException("DExNet вернула повреждённый результат.")
            val deviation = input.indices.count { index ->
                kotlin.math.abs(transmission[index] - input[index]) > .005f
            }
            if (deviation < 12)
                throw IOException("DExNet не нашла достаточно различимых отражений.")
            val result = Bitmap.createBitmap(source.width, source.height,
                Bitmap.Config.ARGB_8888)
            try {
                val row = IntArray(source.width)
                for (y in 0 until source.height) {
                    currentCoroutineContext().ensureActive()
                    source.getPixels(row, 0, source.width, 0, y, source.width, 1)
                    val sy = py + (y + .5f) * h / source.height - .5f
                    for (x in row.indices) {
                        val sx = px + (x + .5f) * w / source.width - .5f
                        val original = row[x]
                        val rgb = intArrayOf(Color.red(original),
                            Color.green(original), Color.blue(original))
                        for (channel in 0..2) {
                            val predicted = bilinear(transmission, channel, sx, sy)
                            val lowResolutionOriginal = bilinear(input, channel, sx, sy)
                            val delta = (predicted - lowResolutionOriginal)
                                .coerceIn(-.45f, .45f)
                            rgb[channel] = (rgb[channel] + delta * 255f * strength)
                                .roundToInt().coerceIn(0, 255)
                        }
                        row[x] = Color.argb(Color.alpha(original), rgb[0], rgb[1], rgb[2])
                    }
                    result.setPixels(row, 0, source.width, 0, y, source.width, 1)
                }
                result
            } catch (e: Throwable) {
                result.recycle()
                throw e
            }
        }

    private fun bilinear(values: FloatArray, channel: Int, x: Float, y: Float): Float {
        val xx = x.coerceIn(0f, (SIDE - 1).toFloat())
        val yy = y.coerceIn(0f, (SIDE - 1).toFloat())
        val x0 = xx.toInt()
        val y0 = yy.toInt()
        val x1 = (x0 + 1).coerceAtMost(SIDE - 1)
        val y1 = (y0 + 1).coerceAtMost(SIDE - 1)
        val dx = xx - x0
        val dy = yy - y0
        val offset = channel * PIXELS
        val top = values[offset + y0 * SIDE + x0] * (1 - dx) +
            values[offset + y0 * SIDE + x1] * dx
        val bottom = values[offset + y1 * SIDE + x0] * (1 - dx) +
            values[offset + y1 * SIDE + x1] * dx
        return top * (1 - dy) + bottom * dy
    }
}

internal fun interface ReflectionModelRunner {
    suspend fun predictTransmission(rgbChw: FloatArray): FloatArray
}

/** Real local ONNX Runtime inference. Never downloads weights or sends photos. */
internal class ReflectionOnnxRunner(
    private val store: ReflectionDExModelStore
) : ReflectionModelRunner {
    override suspend fun predictTransmission(rgbChw: FloatArray): FloatArray {
        require(rgbChw.size == ReflectionRemovalEngine.PIXELS * 3)
        val modelFile = store.requireInstalled()
        currentCoroutineContext().ensureActive()
        val env = OrtEnvironment.getEnvironment()
        return OrtSessions.dexnet.use(modelFile) { session ->
                if (!session.inputNames.contains("image") ||
                    !session.outputNames.contains("transmission"))
                    throw IOException("Формат модели DExNet несовместим с приложением.")
                OnnxTensor.createTensor(env, FloatBuffer.wrap(rgbChw),
                    longArrayOf(1, 3,
                        ReflectionRemovalEngine.SIDE.toLong(),
                        ReflectionRemovalEngine.SIDE.toLong())).use { input ->
                    session.run(mapOf("image" to input)).use { outputs ->
                        currentCoroutineContext().ensureActive()
                        // Named output, NEVER assume output ordering in an ONNX exporter.
                        val value = outputs.get("transmission").orElseThrow {
                            IOException("Модель не вернула transmission.")
                        }
                        val tensor = value as? OnnxTensor
                            ?: throw IOException("Выход transmission не тензор.")
                        if (!tensor.info.shape.contentEquals(longArrayOf(1, 3, 128, 128)))
                            throw IOException("Неподдерживаемая форма выхода DExNet.")
                        val data = FloatArray(ReflectionRemovalEngine.PIXELS * 3)
                        tensor.floatBuffer.apply { rewind(); get(data) }
                        if (data.any { !it.isFinite() })
                            throw IOException("Некорректные значения DExNet.")
                        data
                    }
                }
        }
    }
}
