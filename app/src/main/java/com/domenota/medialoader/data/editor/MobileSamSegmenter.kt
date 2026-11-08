package com.domenota.medialoader.data.editor

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.graphics.Bitmap
import android.graphics.Color
import java.io.IOException
import java.nio.FloatBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Local ONNX MobileSAM. The Acly encoder takes RGB float32 [H,W,3] in 0..255
 * (its normalization/padding are INSIDE the exported model), NOT NCHW nor
 * pre-normalized ImageNet input. Its output is [1,256,64,64].
 *
 * Decoder receives point_coords in resized-image pixels and orig_im_size [H,W].
 * The resulting logits are cropped to the resized image, then converted to the
 * existing source-relative LaMa mask strokes. No image leaves the device.
 */
internal class MobileSamSegmenter(private val store: MobileSamModelStore) {
    private val mutex = Mutex()
    private var imageCache: Bitmap? = null
    private var featureCache: FloatArray? = null
    private var frameCache: MobileSamFrame? = null

    suspend fun segment(source: Bitmap, tap: MaskPoint): List<MaskStroke> =
        withContext(Dispatchers.Default) {
            mutex.withLock {
                currentCoroutineContext().ensureActive()
                val (encoderFile, decoderFile) = store.requireInstalled()
                val frame = MobileSamGeometry.frame(source.width, source.height)
                val features = if (imageCache === source && frameCache == frame && featureCache != null) {
                    requireNotNull(featureCache)
                } else {
                    val computed = encode(encoderFile.absolutePath, source, frame)
                    imageCache = source
                    featureCache = computed
                    frameCache = frame
                    computed
                }
                currentCoroutineContext().ensureActive()
                val prompt = MobileSamGeometry.prompt(tap, frame)
                val logits = decode(decoderFile.absolutePath, features, frame, prompt)
                currentCoroutineContext().ensureActive()
                MobileSamGeometry.strokesFromLogits(logits, frame.width, frame.height, tap)
            }
        }

    /** Releasing a photo also releases its cached embedding (~4 MiB). */
    fun clearCache() {
        imageCache = null
        featureCache = null
        frameCache = null
    }

    private suspend fun encode(path: String, source: Bitmap, frame: MobileSamFrame): FloatArray {
        val env = OrtEnvironment.getEnvironment()
        val resized = Bitmap.createScaledBitmap(source, frame.width, frame.height, true)
        try {
            val pixels = IntArray(frame.width * frame.height)
            resized.getPixels(pixels, 0, frame.width, 0, 0, frame.width, frame.height)
            val rgb = FloatArray(pixels.size * 3)
            for (i in pixels.indices) {
                val argb = pixels[i]
                rgb[i * 3] = Color.red(argb).toFloat()
                rgb[i * 3 + 1] = Color.green(argb).toFloat()
                rgb[i * 3 + 2] = Color.blue(argb).toFloat()
            }
            currentCoroutineContext().ensureActive()
            OrtSession.SessionOptions().use { opt ->
                opt.setIntraOpNumThreads(2)
                env.createSession(path, opt).use { encoder ->
                    val info = (encoder.inputInfo["input_image"]?.info as? TensorInfo)
                        ?: throw IOException("MobileSAM: вход encoder input_image отсутствует.")
                    val shape = info.shape
                    if (shape.size != 3 || shape[2] != 3L)
                        throw IOException("MobileSAM: неподдерживаемая форма входа encoder.")
                    OnnxTensor.createTensor(env, FloatBuffer.wrap(rgb),
                        longArrayOf(frame.height.toLong(), frame.width.toLong(), 3)).use { input ->
                        encoder.run(mapOf("input_image" to input)).use { result ->
                            val output = result[0] as? OnnxTensor
                                ?: throw IOException("MobileSAM: отсутствует выход encoder.")
                            val actual = output.info.shape
                            if (!actual.contentEquals(longArrayOf(1, 256, 64, 64)))
                                throw IOException("MobileSAM: несовместимый размер image_embeddings.")
                            val values = FloatArray(MobileSamGeometry.EMBEDDING_ELEMENTS)
                            val buf = output.floatBuffer
                            buf.rewind()
                            buf.get(values)
                            return values
                        }
                    }
                }
            }
        } finally {
            if (resized !== source) resized.recycle()
        }
    }

    private suspend fun decode(
        path: String,
        features: FloatArray,
        frame: MobileSamFrame,
        pointCoords: FloatArray,
    ): FloatArray {
        val env = OrtEnvironment.getEnvironment()
        OrtSession.SessionOptions().use { opt ->
            opt.setIntraOpNumThreads(2)
            env.createSession(path, opt).use { decoder ->
                val names = setOf("image_embeddings", "point_coords", "point_labels",
                    "mask_input", "has_mask_input", "orig_im_size")
                if (!decoder.inputNames.containsAll(names))
                    throw IOException("MobileSAM: несовместимые входы decoder.")
                val tensors = mutableListOf<OnnxTensor>()
                try {
                    fun tensor(values: FloatArray, dims: LongArray): OnnxTensor =
                        OnnxTensor.createTensor(env, FloatBuffer.wrap(values), dims).also { tensors.add(it) }
                    val inputs = mapOf(
                        "image_embeddings" to tensor(features, longArrayOf(1, 256, 64, 64)),
                        "point_coords" to tensor(pointCoords, longArrayOf(1, 1, 2)),
                        "point_labels" to tensor(floatArrayOf(1f), longArrayOf(1, 1)),
                        "mask_input" to tensor(FloatArray(256 * 256), longArrayOf(1, 1, 256, 256)),
                        "has_mask_input" to tensor(floatArrayOf(0f), longArrayOf(1)),
                        "orig_im_size" to tensor(
                            floatArrayOf(frame.height.toFloat(), frame.width.toFloat()),
                            longArrayOf(2)),
                    )
                    currentCoroutineContext().ensureActive()
                    decoder.run(inputs).use { result ->
                        val mask = result[0] as? OnnxTensor
                            ?: throw IOException("MobileSAM: decoder не вернул маску.")
                        val shape = mask.info.shape
                        if (!shape.contentEquals(longArrayOf(1, 1,
                                frame.height.toLong(), frame.width.toLong())))
                            throw IOException("MobileSAM: неверный размер выходной маски.")
                        val values = FloatArray(frame.width * frame.height)
                        val buffer = mask.floatBuffer
                        buffer.rewind()
                        buffer.get(values)
                        return values
                    }
                } finally {
                    tensors.asReversed().forEach { it.close() }
                }
            }
        }
    }
}
