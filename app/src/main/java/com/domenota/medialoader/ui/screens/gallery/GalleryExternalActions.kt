package com.domenota.medialoader.ui.screens.gallery

import android.app.WallpaperManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.content.FileProvider
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.MediaType
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Give other applications a read grant without exposing private filesystem paths. */
private suspend fun exportedUri(context: Context, item: DownloadEntity, videoFrame: Boolean): Uri = withContext(Dispatchers.IO) {
    if (!videoFrame) {
        val source = Uri.parse(requireNotNull(item.savedUri))
        return@withContext if (source.scheme == "content") source else galleryToolUri(context, source)
    }
    val directory = File(context.cacheDir, "photo-tools").apply { mkdirs() }
    val file = File.createTempFile("gallery-", if (videoFrame) ".jpg" else if (item.mediaType == MediaType.VIDEO) ".mp4" else ".jpg", directory)
    try {
        val source = Uri.parse(requireNotNull(item.savedUri))
        if (videoFrame) {
            val retriever = MediaMetadataRetriever()
            val bitmap = try { retriever.setDataSource(context, source); retriever.getFrameAtTime(0) }
                finally { retriever.release() }
            checkNotNull(bitmap) { "Не удалось получить кадр видео" }
            try { file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) } }
            finally { bitmap.recycle() }
        } else {
            val input = context.contentResolver.openInputStream(source) ?: error("Файл недоступен")
            input.use { stream -> file.outputStream().use { stream.copyTo(it) } }
        }
        FileProvider.getUriForFile(context, "${context.packageName}.photo-tools", file)
    } catch (e: Exception) { file.delete(); throw e }
}

internal suspend fun copyGalleryFile(context: Context, item: DownloadEntity) {
    val uri = exportedUri(context, item, false)
    withContext(Dispatchers.Main) {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newUri(context.contentResolver, item.originalName, uri))
        android.widget.Toast.makeText(context, "Файл скопирован", android.widget.Toast.LENGTH_SHORT).show()
    }
}

/**
 * WallpaperManager requires a URI whose ContentProvider reports a valid image MIME type.
 * Downloads may expose application/octet-stream, so use a dedicated PNG copy.
 * Never modify the original image or hand the wallpaper app an ungranted URI.
 */
internal suspend fun prepareGalleryWallpaperUri(context: Context, item: DownloadEntity): Uri =
    withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "photo-tools").apply { check(isDirectory || mkdirs()) }
        val file = File.createTempFile("wallpaper-", ".png", directory)
        try {
            val source = Uri.parse(requireNotNull(item.savedUri))
            val bitmap = if (item.mediaType == MediaType.VIDEO) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, source)
                    checkNotNull(retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)) {
                        "Не удалось получить кадр видео для обоев"
                    }
                } finally { retriever.release() }
            } else {
                GalleryPhotoTools.decode(context, source, maxEdge = 2560)
            }
            try {
                file.outputStream().use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                        "Не удалось подготовить изображение для обоев"
                    }
                }
            } finally { bitmap.recycle() }
            check(file.length() > 0) { "Изображение для обоев пустое" }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.photo-tools", file)
            val mime = context.contentResolver.getType(uri)
            check(mime?.startsWith("image/") == true) {
                "Система не распознаёт изображение для обоев: тип ${mime ?: "не определён"}"
            }
            check(context.contentResolver.openInputStream(uri)?.use { it.read() >= 0 } == true) {
                "Система не может открыть изображение для обоев"
            }
            com.domenota.medialoader.core.logging.AppLog.i(
                "GalleryWallpaper", "Wallpaper prepared · source=${item.mediaType} · mime=$mime · bytes=${file.length()}"
            )
            uri
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            file.delete()
            throw cancelled
        } catch (failure: Exception) {
            file.delete()
            com.domenota.medialoader.core.logging.AppLog.w("GalleryWallpaper", "Wallpaper preparation failed", failure)
            throw failure
        }
    }

internal suspend fun galleryWallpaper(context: Context, item: DownloadEntity) {
    val uri = prepareGalleryWallpaperUri(context, item)
    withContext(Dispatchers.Main) {
        val resolver = context.contentResolver
        // ClipData grants access on Android 13+ and Xiaomi's wallpaper-picker process.
        val grant = ClipData.newUri(resolver, "Изображение для обоев", uri)
        val intent = try {
            WallpaperManager.getInstance(context).getCropAndSetWallpaperIntent(uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .also { it.clipData = grant }
        } catch (failure: Exception) {
            com.domenota.medialoader.core.logging.AppLog.w(
                "GalleryWallpaper", "System crop intent unavailable, using ACTION_ATTACH_DATA", failure
            )
            Intent(Intent.ACTION_ATTACH_DATA).apply {
                setDataAndType(uri, "image/png")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = grant
            }
        }
        try {
            context.startActivity(intent)
        } catch (failure: android.content.ActivityNotFoundException) {
            com.domenota.medialoader.core.logging.AppLog.w(
                "GalleryWallpaper", "Wallpaper crop activity unavailable; opening image chooser", failure
            )
            val fallback = Intent(Intent.ACTION_ATTACH_DATA).apply {
                setDataAndType(uri, "image/png")
                clipData = grant
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(fallback, "Установить как обои"))
        }
    }
}

/**
 * On Xiaomi/Android 16 the OEM crop activity may need a second confirmation,
 * or may not resolve our FileProvider at all. Use Android's wallpaper service
 * directly after one explicit user choice (lock/home/both).
 *
 * This sets a STATIC bitmap: for a video the first sync frame is extracted.
 */
internal fun galleryWallpaperTarget(which: Int): Int {
    require(which == WallpaperManager.FLAG_SYSTEM ||
        which == WallpaperManager.FLAG_LOCK ||
        which == (WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK))
    return which
}

internal suspend fun galleryApplyWallpaper(context: Context, item: DownloadEntity, which: Int) {
    val flag = galleryWallpaperTarget(which)
    withContext(Dispatchers.IO) {
        val original = Uri.parse(requireNotNull(item.savedUri) { "Исходный файл не найден" })
        val bitmap = if (item.mediaType == MediaType.VIDEO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, original)
                checkNotNull(retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)) {
                    "Не удалось извлечь кадр видео"
                }
            } finally { retriever.release() }
        } else {
            GalleryPhotoTools.decode(context, original, maxEdge = 2560)
        }
        try {
            val wallpaper = WallpaperManager.getInstance(context)
            check(wallpaper.isWallpaperSupported && wallpaper.isSetWallpaperAllowed) {
                "Система запрещает изменять обои"
            }
            val id = wallpaper.setBitmap(bitmap, null, false, flag)
            check(id != 0) { "Система не подтвердила установку обоев" }
            com.domenota.medialoader.core.logging.AppLog.i(
                "GalleryWallpaper", "Applied directly · source=${item.mediaType} · which=$flag · wallpaperId=$id"
            )
        } catch (error: Exception) {
            com.domenota.medialoader.core.logging.AppLog.e(
                "GalleryWallpaper", "Direct wallpaper apply failed · which=$flag", error
            )
            throw error
        } finally {
            bitmap.recycle()
        }
    }
    withContext(Dispatchers.Main) {
        android.widget.Toast.makeText(context, "Обои установлены", android.widget.Toast.LENGTH_SHORT).show()
    }
}

internal suspend fun galleryCast(context: Context, item: DownloadEntity) {
    val uri = exportedUri(context, item, false)
    withContext(Dispatchers.Main) {
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, if (item.mediaType == MediaType.VIDEO) "video/*" else "image/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.clipData = ClipData.newRawUri(item.originalName, uri)
        context.startActivity(Intent.createChooser(intent, "Выберите приложение с поддержкой трансляции"))
    }
}
