package com.domenota.medialoader.data.editor

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import java.io.FileInputStream
import java.io.IOException
import java.nio.channels.FileChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Real CPU inference through MediaPipe Tasks and a downloadable EfficientDet Lite0.
 * ObjectDetector returns independent person bounding boxes; it does NOT generate
 * segmentation masks. MobileSAM must generate masks for each detection afterward.
 */
internal class PersonDetector(
    private val context: Context,
    private val store: PersonDetectorModelStore,
) {
    suspend fun detect(source: Bitmap): List<PersonDetection> = withContext(Dispatchers.Default) {
        currentCoroutineContext().ensureActive()
        val model = store.requireInstalled()
        FileInputStream(model).use { stream ->
            val buffer = stream.channel.map(FileChannel.MapMode.READ_ONLY, 0, model.length())
            val base = BaseOptions.builder().setModelAssetBuffer(buffer).build()
            val options = ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(base)
                .setRunningMode(RunningMode.IMAGE)
                .setCategoryAllowlist(listOf("person"))
                .setScoreThreshold(.35f)
                .setMaxResults(PersonInstancePolicy.MAX_PEOPLE)
                .build()
            val detector = try {
                ObjectDetector.createFromOptions(context.applicationContext, options)
            } catch (e: Exception) {
                throw IOException("EfficientDet не открылась. Проверьте совместимость весов.", e)
            }
            try {
                val result = detector.detect(BitmapImageBuilder(source).build())
                currentCoroutineContext().ensureActive()
                val detections = result.detections().mapIndexedNotNull { index, detection ->
                    val person = detection.categories().firstOrNull {
                        it.categoryName().equals("person", ignoreCase = true)
                    } ?: return@mapIndexedNotNull null
                    val rect = detection.boundingBox()
                    val width = source.width.toFloat()
                    val height = source.height.toFloat()
                    PersonDetection(index, PersonBox(
                        (rect.left / width).coerceIn(0f, 1f),
                        (rect.top / height).coerceIn(0f, 1f),
                        (rect.right / width).coerceIn(0f, 1f),
                        (rect.bottom / height).coerceIn(0f, 1f),
                    ), person.score())
                }
                PersonInstancePolicy.validated(detections)
            } finally {
                detector.close()
            }
        }
    }
}
