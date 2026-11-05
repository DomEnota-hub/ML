package com.domenota.medialoader.ui.screens.gallery

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.google.mlkit.vision.common.InputImage
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.tasks.Task
import com.domenota.medialoader.core.logging.AppLog
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** Keeps exported permissions restricted to a cache copy; never modifies the source. */
internal suspend fun galleryToolUri(context: Context, source: Uri): Uri = withContext(Dispatchers.IO) {
    val mime = context.contentResolver.getType(source)
    val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
        ?: source.lastPathSegment?.substringAfterLast('.', "")?.takeIf { it.matches(Regex("[a-zA-Z0-9]{1,8}")) }
        ?: "bin"
    val file = File(photoToolDirectory(context), "original-${UUID.randomUUID()}.$extension")
    try {
        requireNotNull(context.contentResolver.openInputStream(source)) { "Не удалось открыть файл" }.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        currentCoroutineContext().ensureActive()
        photoToolFileUri(context, file)
    } catch (error: Throwable) { file.delete(); throw error }
}

internal object GalleryPhotoTools {
    data class Cutout(val foreground: Bitmap, val subjects: List<Bitmap>)

    /** Bounds memory use; rotation from EXIF is applied before OCR/segmentation. */
    suspend fun decode(context: Context, uri: Uri, maxEdge: Int = 2560): Bitmap = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        requireNotNull(resolver.openInputStream(uri)) { "Не удалось открыть фотографию" }.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        check(bounds.outWidth > 0 && bounds.outHeight > 0) { "Формат фотографии не поддерживается" }
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > maxEdge) inSampleSize *= 2
        }
        val decoded = requireNotNull(resolver.openInputStream(uri)).use {
            requireNotNull(BitmapFactory.decodeStream(it, null, options)) { "Не удалось прочитать фотографию" }
        }
        val orientation = resolver.openInputStream(uri)?.use {
            runCatching { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
                .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
            }
        }
        val image = if (matrix.isIdentity) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (image !== decoded) decoded.recycle()
        currentCoroutineContext().ensureActive()
        image
    }

    /**
     * The Subject Segmenter is an OptionalModuleApi. Its first process() may otherwise
     * return "Waiting for the subject segmentation optional module to be downloaded".
     * This gate completes before bitmap decoding, so network and module failures are
     * reported to the UI instead of entering native image processing.
     */
    suspend fun prepareCutoutModel(context: Context) {
        val probe = SubjectSegmentation.getClient(subjectOptions())
        try {
            val installer = ModuleInstall.getClient(context.applicationContext)
            val available = awaitGmsTask(installer.areModulesAvailable(probe)).areModulesAvailable()
            if (!available) {
                AppLog.i("PhotoCutout", "Module missing; requesting subject segmentation module")
                val request = ModuleInstallRequest.newBuilder().addApi(probe).build()
                // Installation request accepted != installed; poll real availability.
                awaitGmsTask(installer.installModules(request))
                withTimeout(90_000L) {
                    while (!awaitGmsTask(installer.areModulesAvailable(probe)).areModulesAvailable()) {
                        delay(1_000L)
                    }
                }
            }
            AppLog.i("PhotoCutout", "Subject segmentation module ready")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            AppLog.w("PhotoCutout", "Module preparation failed: ${failure.javaClass.simpleName}", failure)
            throw failure
        } finally {
            // Creating an optional-module API may start asynchronous initialization.
            // Never close a detector while its init task is still using native resources.
            val init = probe.initTask
            if (init.isComplete) probe.close() else init.addOnCompleteListener { probe.close() }
        }
    }

    /** Decoding and segmentation are separate, so on-screen Cancel cannot tear down an active native task. */
    suspend fun cutout(image: Bitmap): Cutout {
        val segmenter = SubjectSegmentation.getClient(subjectOptions())
        var processing = false
        val init = segmenter.initTask
        try {
            awaitGmsTask(init)
            currentCoroutineContext().ensureActive()
            return suspendCancellableCoroutine { continuation ->
                try {
                    val task = segmenter.process(InputImage.fromBitmap(image, 0))
                    processing = true
                    task.addOnCompleteListener { completed ->
                        try {
                            if (continuation.isActive) {
                                when {
                                    completed.isSuccessful -> {
                                        val result = completed.result
                                        val foreground = result.foregroundBitmap
                                        if (foreground == null || result.subjects.isEmpty()) {
                                            continuation.resumeWithException(IllegalStateException(
                                                "Объекты не найдены. Попробуйте фото с чётким передним планом."))
                                        } else continuation.resume(Cutout(foreground, result.subjects.mapNotNull { it.bitmap }))
                                    }
                                    completed.isCanceled -> continuation.cancel()
                                    else -> continuation.resumeWithException(
                                        completed.exception ?: IllegalStateException("Сегментация не завершилась"))
                                }
                            }
                        } catch (failure: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(failure)
                        } finally {
                            // ML Kit may outlive the dialog, activity or coroutine cancellation.
                            // The native client is closed only AFTER its processing task finishes.
                            segmenter.close()
                        }
                    }
                    continuation.invokeOnCancellation {
                        AppLog.i("PhotoCutout", "Cancellation requested; native task remains alive until completion")
                    }
                } catch (failure: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(failure)
                }
            }
        } finally {
            if (!processing) {
                if (init.isComplete) segmenter.close()
                else init.addOnCompleteListener { segmenter.close() }
            }
        }
    }

    private fun subjectOptions(): SubjectSegmenterOptions =
        SubjectSegmenterOptions.Builder().enableForegroundBitmap()
            .enableMultipleSubjects(SubjectSegmenterOptions.SubjectResultOptions.Builder()
                .enableSubjectBitmap().build())
            .build()

    suspend fun watermark(source: Bitmap, text: String): Bitmap = withContext(Dispatchers.Default) {
        require(text.isNotBlank()) { "Введите текст водяного знака" }
        val output = requireNotNull(source.copy(Bitmap.Config.ARGB_8888, true))
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = minOf(output.width, output.height) * 0.055f
            setShadowLayer(textSize * 0.1f, 1f, 1f, Color.BLACK)
        }
        val label = text.trim().take(160)
        val available = output.width * 0.88f
        if (paint.measureText(label) > available) paint.textSize *= available / paint.measureText(label)
        canvas.drawText(label, output.width - output.width * 0.06f - paint.measureText(label), output.height * 0.93f, paint)
        output
    }

    suspend fun export(context: Context, bitmap: Bitmap): Uri = withContext(Dispatchers.IO) {
        val file = File(photoToolDirectory(context), "photo-${UUID.randomUUID()}.png")
        try {
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "Не удалось создать PNG" } }
            currentCoroutineContext().ensureActive()
            photoToolFileUri(context, file)
        } catch (error: Throwable) { file.delete(); throw error }
    }

    suspend fun save(context: Context, bitmap: Bitmap): Uri = withContext(Dispatchers.IO) {
        check(Build.VERSION.SDK_INT >= 29)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Photo-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/MediaLoader")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)) { "Не удалось создать копию" }
        try {
            requireNotNull(resolver.openOutputStream(uri)).use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            currentCoroutineContext().ensureActive()
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            uri
        } catch (error: Throwable) { resolver.delete(uri, null, null); throw error }
    }
}


/** Cancellation stops waiting, not the underlying Google Play services Task. */
private suspend fun <T> awaitGmsTask(task: Task<T>): T = suspendCancellableCoroutine { continuation ->
    task.addOnCompleteListener { completed ->
        if (!continuation.isActive) return@addOnCompleteListener
        when {
            completed.isSuccessful -> continuation.resume(completed.result)
            completed.isCanceled -> continuation.cancel()
            else -> continuation.resumeWithException(
                completed.exception ?: IllegalStateException("Google Play services не завершили операцию"))
        }
    }
}

private fun photoToolDirectory(context: Context): File = File(context.cacheDir, "photo-tools").apply {
    check(isDirectory || mkdirs()) { "Не удалось создать папку экспорта" }
}

private fun photoToolFileUri(context: Context, file: File): Uri =
    FileProvider.getUriForFile(context, "${context.packageName}.photo-tools", file)
