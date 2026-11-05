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

internal suspend fun galleryWallpaper(context: Context, item: DownloadEntity) {
    val uri = exportedUri(context, item, item.mediaType == MediaType.VIDEO)
    withContext(Dispatchers.Main) {
        val intent = WallpaperManager.getInstance(context).getCropAndSetWallpaperIntent(uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(intent)
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
