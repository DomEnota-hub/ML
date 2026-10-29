package com.domenota.medialoader.data.editor

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
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
        OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2)
            env.createSession(model.absolutePath, options).use { session ->
                OnnxTensor.createTensor(env, FloatBuffer.wrap(image), longArrayOf(1, 3, size.toLong(), size.toLong())).use { imageInput ->
                    OnnxTensor.createTensor(env, FloatBuffer.wrap(mask), longArrayOf(1, 1, size.toLong(), size.toLong())).use { maskInput ->
                        session.run(mapOf("image" to imageInput, "mask" to maskInput)).use { outputs ->
                            currentCoroutineContext().ensureActive()
                            @Suppress("UNCHECKED_CAST")
                            val channels = (outputs[0].value as Array<Array<Array<FloatArray>>>)[0]
                            val result = FloatArray(3 * size * size)
                            for (channel in 0..2) for (y in 0 until size) {
                                channels[channel][y].copyInto(result, channel * size * size + y * size)
                            }
                            return result
                        }
                    }
                }
            }
        }
    }
}
