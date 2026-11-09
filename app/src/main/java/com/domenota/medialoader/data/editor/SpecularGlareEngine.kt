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
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Real SRNet specular-highlight restoration, not a LaMa brush or an exposure slider.
 * The network infers a 1-channel highlight mask plus 3-channel diffuse RGB.
 * Corrective RGB residuals are upsampled onto the original pixels so all original
 * high-resolution details outside the learned highlight mask are preserved.
 *
 * Inference uses a 256-square letterbox with edge-clamped padding, preserving the
 * aspect ratio. No original pixels leave the device.
 */
internal class SpecularGlareEngine internal constructor(
    private val runner: SpecularModelRunner,
) {
    constructor(store: SpecularSrNetModelStore) : this(SpecularOnnxRunner(store))

    companion object {
        const val SIDE = 256
        const val PIXELS = SIDE * SIDE
    }

    suspend fun correct(source: Bitmap): Bitmap = withContext(Dispatchers.Default) {
        require(source.width > 0 && source.height > 0)
        val frame = SpecularFrame.forImage(source.width, source.height)
        val small = Bitmap.createScaledBitmap(source, frame.width, frame.height, true)
        val pixels = IntArray(frame.width * frame.height)
        try {
            small.getPixels(pixels, 0, frame.width, 0, 0, frame.width, frame.height)
        } finally {
            if (small !== source) small.recycle()
        }
        val input = FloatArray(3 * PIXELS)
        for (y in 0 until SIDE) {
            val sy = (y - frame.padY).coerceIn(0, frame.height - 1)
            for (x in 0 until SIDE) {
                val sx = (x - frame.padX).coerceIn(0, frame.width - 1)
                val color = pixels[sy * frame.width + sx]
                val i = y * SIDE + x
                input[i] = Color.red(color) / 255f
                input[PIXELS + i] = Color.green(color) / 255f
                input[2 * PIXELS + i] = Color.blue(color) / 255f
            }
        }
        currentCoroutineContext().ensureActive()
        val prediction = runner.predict(input)
        require(prediction.mask.size == PIXELS && prediction.diffuse.size == 3 * PIXELS) {
            "SRNet вернула маску или изображение неправильного размера."
        }
        if (prediction.mask.any { !it.isFinite() } ||
            prediction.diffuse.any { !it.isFinite() }) {
            throw IOException("SRNet вернула повреждённые данные.")
        }
        val relevant = prediction.mask.count { it >= .40f }
        if (relevant < 12) throw IOException("SRNet не нашла значимых бликов.")
        if (relevant > PIXELS * .75f) throw IOException(
            "SRNet выделила слишком большую область. Попробуйте другое фото.")
        currentCoroutineContext().ensureActive()
        val edited = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        try {
            val row = IntArray(source.width)
            for (y in 0 until source.height) {
                currentCoroutineContext().ensureActive()
                source.getPixels(row, 0, source.width, 0, y, source.width, 1)
                val yp = frame.padY + ((y + .5f) * frame.height / source.height) - .5f
                for (x in row.indices) {
                    val xp = frame.padX + ((x + .5f) * frame.width / source.width) - .5f
                    val alpha = ((sample(prediction.mask, 0, xp, yp, 1) - .10f) / .80f)
                        .coerceIn(0f, 1f)
                    if (alpha < .001f) continue
                    val orig = row[x]
                    val channels = intArrayOf(Color.red(orig), Color.green(orig), Color.blue(orig))
                    for (c in 0..2) {
                        val low = sample(input, c, xp, yp, 3)
                        val diffuse = sample(prediction.diffuse, c, xp, yp, 3)
                        val delta = (diffuse - low).coerceIn(-1f, 1f)
                        channels[c] = (channels[c] + delta * alpha * 255f)
                            .roundToInt().coerceIn(0, 255)
                    }
                    row[x] = Color.argb(Color.alpha(orig),
                        channels[0], channels[1], channels[2])
                }
                edited.setPixels(row, 0, source.width, 0, y, source.width, 1)
            }
            edited
        } catch (t: Throwable) {
            edited.recycle()
            throw t
        }
    }

    private fun sample(values: FloatArray, channel: Int, x: Float, y: Float,
                       planes: Int): Float {
        val xx = x.coerceIn(0f, (SIDE - 1).toFloat())
        val yy = y.coerceIn(0f, (SIDE - 1).toFloat())
        val x0 = xx.toInt()
        val y0 = yy.toInt()
        val x1 = (x0 + 1).coerceAtMost(SIDE - 1)
        val y1 = (y0 + 1).coerceAtMost(SIDE - 1)
        val dx = xx - x0
        val dy = yy - y0
        val offset = if (planes == 1) 0 else channel * PIXELS
        val r0 = values[offset + y0 * SIDE + x0] * (1f - dx) +
            values[offset + y0 * SIDE + x1] * dx
        val r1 = values[offset + y1 * SIDE + x0] * (1f - dx) +
            values[offset + y1 * SIDE + x1] * dx
        return r0 * (1f - dy) + r1 * dy
    }
}

internal data class SpecularFrame(
    val width: Int,
    val height: Int,
    val padX: Int,
    val padY: Int,
) {
    companion object {
        fun forImage(width: Int, height: Int): SpecularFrame {
            require(width > 0 && height > 0)
            val maxSide = max(width, height)
            val scaledW = (width.toDouble() * SpecularGlareEngine.SIDE / maxSide)
                .roundToInt().coerceIn(1, SpecularGlareEngine.SIDE)
            val scaledH = (height.toDouble() * SpecularGlareEngine.SIDE / maxSide)
                .roundToInt().coerceIn(1, SpecularGlareEngine.SIDE)
            return SpecularFrame(scaledW, scaledH,
                (SpecularGlareEngine.SIDE - scaledW) / 2,
                (SpecularGlareEngine.SIDE - scaledH) / 2)
        }
    }
}

internal data class SpecularPrediction(val mask: FloatArray, val diffuse: FloatArray)

internal fun interface SpecularModelRunner {
    /** Input RGB CHW in [0, 1]; output learned mask [0, 1] and diffuse RGB CHW. */
    suspend fun predict(image: FloatArray): SpecularPrediction
}

internal class SpecularOnnxRunner(private val store: SpecularSrNetModelStore) : SpecularModelRunner {
    override suspend fun predict(image: FloatArray): SpecularPrediction {
        val size = SpecularGlareEngine.SIDE
        require(image.size == 3 * size * size)
        val model = store.requireInstalled()
        val env = OrtEnvironment.getEnvironment()
        currentCoroutineContext().ensureActive()
        OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2)
            env.createSession(model.absolutePath, options).use { session ->
                if (!session.inputNames.contains("image") ||
                    !session.outputNames.containsAll(setOf("highlight_mask", "diffuse")))
                    throw IOException("SRNet: несовместимые имена входов/выходов ONNX.")
                OnnxTensor.createTensor(env, FloatBuffer.wrap(image),
                    longArrayOf(1, 3, size.toLong(), size.toLong())).use { input ->
                    session.run(mapOf("image" to input)).use { result ->
                        currentCoroutineContext().ensureActive()
                        val maskTensor = result[0] as? OnnxTensor
                            ?: throw IOException("SRNet: выход маски отсутствует.")
                        val rgbTensor = result[2] as? OnnxTensor
                            ?: throw IOException("SRNet: выход diffuse отсутствует.")
                        if (!maskTensor.info.shape.contentEquals(longArrayOf(1, 1, 256, 256)) ||
                            !rgbTensor.info.shape.contentEquals(longArrayOf(1, 3, 256, 256)))
                            throw IOException("SRNet: неподдерживаемые формы ONNX.")
                        val mask = FloatArray(SpecularGlareEngine.PIXELS)
                        val diffuse = FloatArray(3 * SpecularGlareEngine.PIXELS)
                        maskTensor.floatBuffer.apply { rewind(); get(mask) }
                        rgbTensor.floatBuffer.apply { rewind(); get(diffuse) }
                        return SpecularPrediction(mask, diffuse)
                    }
                }
            }
        }
    }
}
