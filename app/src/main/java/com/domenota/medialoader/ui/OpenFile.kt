package com.domenota.medialoader.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.domenota.medialoader.ui.components.AppNotice
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.logging.AppLog
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.data.storage.SavedFilePresence
import com.domenota.medialoader.data.storage.SavedFileStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun DownloadEntity.mimeType(): String = when (mediaType) {
    MediaType.VIDEO -> "video/mp4"
    MediaType.AUDIO -> if (originalName.endsWith(".m4a", ignoreCase = true)) "audio/mp4" else "audio/mpeg"
    MediaType.PHOTO -> "image/jpeg"
}

/** Verify existence on IO on every user action; the screen's cached status may be stale. */
private fun withAvailableFile(
    context: Context,
    entity: DownloadEntity,
    action: String,
    block: (Uri) -> Unit,
) {
    CoroutineScope(Dispatchers.Main.immediate).launch {
        val status = withContext(Dispatchers.IO) {
            SavedFilePresence.check(context.applicationContext, entity.savedUri)
        }
        if (status != SavedFileStatus.PRESENT) {
            AppLog.w("History", "$action aborted · id=${entity.id} · status=$status")
            AppNotice.post(context,
                if (status == SavedFileStatus.MISSING) "Файл отсутствует на устройстве"
                else "Не удалось получить доступ к файлу")
            return@launch
        }
        val uri = entity.savedUri?.let(Uri::parse) ?: return@launch
        try {
            val shareUri=if(uri.scheme=="file" && com.domenota.medialoader.data.catalog.visibility.FolderVisibilityStorageAccess.granted() &&
                withContext(Dispatchers.IO) { runCatching { com.domenota.medialoader.data.catalog.visibility.FolderVisibilityStorageAccess.store().owns(java.io.File(requireNotNull(uri.path))) }.getOrDefault(false) })
                androidx.core.content.FileProvider.getUriForFile(context,"${context.packageName}.photo-tools",java.io.File(requireNotNull(uri.path))) else uri
            block(shareUri)
        } catch (error: Exception) {
            AppLog.w("History",
                "$action failed · id=${entity.id} · ${error.javaClass.simpleName}: ${error.message.orEmpty().take(160)}")
            AppNotice.post(context, when (error) {
                is ActivityNotFoundException -> "Не найдено приложение для открытия файла"
                else -> "Не удалось ${if (action == "Open") "открыть" else "отправить"} файл"
            })
        }
    }
}

fun openSavedFile(context: Context, entity: DownloadEntity) {
    withAvailableFile(context, entity, "Open") { uri ->
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, entity.mimeType())
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }
}

fun shareSavedFile(context: Context, entity: DownloadEntity) {
    withAvailableFile(context, entity, "Share") { uri ->
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = entity.mimeType()
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Поделиться файлом"))
    }
}
