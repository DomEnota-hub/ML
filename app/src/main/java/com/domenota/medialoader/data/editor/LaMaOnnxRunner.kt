package com.domenota.medialoader.data.editor

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import java.io.IOException
import java.nio.FloatBuffer
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal fun interface InpaintingModelRunner {
    /** RGB CHW in [0,1], binary mask; returns RGB CHW in [0,255]. */
    suspend fun predict(image: FloatArray, mask: FloatArray): FloatArray
}

internal class LaMaOnnxRunner(private val models: LaMaModelStore) : InpaintingModelRunner {
    override suspend fun predict(image: FloatArray, mask: FloatArray): FloatArray {
        val size = LaMaInpaintingEngine.SIZE
        require(image.size == 3 * size * size && mask.size == size * size)
        val model = models.requireInstalled()
        val env = OrtEnvironment.getEnvironment()
        currentCoroutineContext().ensureActive()
        return OrtSessions.lama.use(model, LaMaModelStore.SHA256) { session ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(image),
                longArrayOf(1, 3, size.toLong(), size.toLong())).use { imageInput ->
                OnnxTensor.createTensor(env, FloatBuffer.wrap(mask),
                    longArrayOf(1, 1, size.toLong(), size.toLong())).use { maskInput ->
                    session.run(mapOf("image" to imageInput, "mask" to maskInput)).use { outputs ->
                        currentCoroutineContext().ensureActive()
                        val tensor = outputs[0] as? OnnxTensor
                            ?: throw IOException("LaMa: модель не вернула выходной тензор.")
                        if (!tensor.info.shape.contentEquals(
                                longArrayOf(1, 3, size.toLong(), size.toLong())))
                            throw IOException("LaMa: неожиданная форма выходного тензора.")
                        val result = FloatArray(3 * size * size)
                        // NCHW output is already contiguous; avoid allocating
                        // thousands of nested FloatArray rows per inference.
                        val buffer = tensor.floatBuffer
                        buffer.rewind()
                        if (buffer.remaining() != result.size)
                            throw IOException("LaMa: неверная длина выходного буфера.")
                        buffer.get(result)
                        result
                    }
                }
            }
        }
    }
}
