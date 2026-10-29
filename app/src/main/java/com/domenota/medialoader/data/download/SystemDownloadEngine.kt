package com.domenota.medialoader.data.download

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import com.domenota.medialoader.core.model.DownloadTask
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.data.storage.StorageManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Photo and video transfer through the system DownloadManager. Nothing outside this class touches it. */
class SystemDownloadEngine(
    context: Context,
    private val storage: StorageManager,
) : DownloadEngine {
    private val preferences = context.applicationContext.getSharedPreferences("ui", Context.MODE_PRIVATE)
    private val system = context.applicationContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    private class Snapshot(val status: Int, val downloaded: Long, val total: Long, val reason: Int)

    override suspend fun download(
        task: DownloadTask,
        onProgress: suspend (bytesDownloaded: Long, totalBytes: Long?) -> Unit,
    ): DownloadResult {
        val request = DownloadManager.Request(Uri.parse(task.item.downloadUrl))
            .setTitle(task.fileName)
            .setDescription("Загрузчик")
            .setMimeType(if (task.item.type == MediaType.VIDEO) "video/mp4" else "image/jpeg")
            .setNotificationVisibility(if (preferences.getBoolean("notifications", true))
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                else DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                storage.downloadsRelativePath(task.fileName),
            )
        request.addRequestHeader("Referer", "https://www.instagram.com/")
        val systemId = system.enqueue(request)
        try {
            while (true) {
                val snapshot = query(systemId) ?: throw DownloadFailure("Загрузка была удалена в системе.")
                when (snapshot.status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        val uri = system.getUriForDownloadedFile(systemId)?.toString()
                            ?: throw DownloadFailure("Файл сохранён, но система не вернула его адрес.")
                        return DownloadResult(uri, snapshot.total.takeIf { it > 0 })
                    }
                    DownloadManager.STATUS_FAILED -> throw DownloadFailure(
                        if (snapshot.reason == DownloadManager.ERROR_INSUFFICIENT_SPACE)
                            "Недостаточно места на устройстве. Освободите память и повторите."
                        else "Не удалось скачать файл. Проверьте соединение и повторите анализ ссылки.",
                    )
                }
                onProgress(snapshot.downloaded, snapshot.total.takeIf { it > 0 })
                delay(POLL_INTERVAL_MS)
            }
        } catch (cancelled: CancellationException) {
            system.remove(systemId)
            throw cancelled
        }
    }

    private fun query(systemId: Long): Snapshot? =
        system.query(DownloadManager.Query().setFilterById(systemId))?.use { cursor ->
            if (!cursor.moveToFirst()) null
            else Snapshot(
                status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
                reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)),
            )
        }

    private companion object { const val POLL_INTERVAL_MS = 500L }
}
