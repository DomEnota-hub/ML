package com.domenota.medialoader.data.storage

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.domenota.medialoader.core.database.DownloadDao
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Explicit, restartable migration of app-owned historical media into
 * Pictures/ or Music/. Never bulk-rename a filesystem folder, delete database
 * rows or modify virtual catalog_folders/catalog_assignments.
 *
 * Every eligible item:
 *   validate it belongs to this app and public Download/
 *   -> move its existing MediaStore row via RELATIVE_PATH
 *   -> recheck SAME URI, date-added, date-modified, size and SHA-256.
 *
 * Never insert a new MediaStore row, rewrite its timestamps, delete original
 * bytes, or guess a source URI. Unsupported legacy paths are safely skipped.
 * Existing 1.7.158 migrations are repaired separately in gallery ordering.
 */
/** User-visible summary of an opt-in and resumable filesystem migration. */
data class StorageMigrationProgress(
    val total: Int = 0,
    val processed: Int = 0,
    val moved: Int = 0,
    val skipped: Int = 0,
    val retainedBackups: Int = 0,
    val running: Boolean = false,
    val message: String = "",
)

internal class LegacyMediaMigration(
    private val context: Context,
    private val dao: DownloadDao,
    private val storage: StorageManager,
) {
    companion object {
        /** Only the public Download/ root, never Music/Pictures/Private. */
        fun isLegacyPath(path: String?): Boolean =
            path != null && path.startsWith("Download/") &&
                !path.contains("..") && !path.contains('\\') &&
                path.length > "Download/".length
    }

    suspend fun migrate(onProgress: (StorageMigrationProgress) -> Unit): StorageMigrationProgress =
        withContext(Dispatchers.IO) {
            check(Build.VERSION.SDK_INT >= 29) {
                "Перенос через MediaStore доступен на Android 10 и новее."
            }
            check(!storage.usesSelectedFolder) {
                "Выбрана сторонняя папка. Для переноса сначала выберите папку Pictures."
            }
            val historyRows = dao.all()
            check(historyRows.none {
                it.state == DownloadState.RUNNING || it.state == DownloadState.QUEUED
            }) { "Дождитесь завершения текущих загрузок перед переносом файлов." }
            val candidates = historyRows.filter { it.state == DownloadState.COMPLETED &&
                !it.isPrivate && !it.hidden && !it.savedUri.isNullOrBlank() &&
                it.mediaType in setOf(MediaType.PHOTO, MediaType.VIDEO, MediaType.AUDIO) }
            val total = candidates.size
            var processed = 0
            var moved = 0
            var skipped = 0
            var backups = 0
            onProgress(StorageMigrationProgress(total, 0, 0, 0, 0, true,
                "Проверяем прежние загрузки…"))
            for (item in candidates) {
                currentCoroutineContext().ensureActive()
                val saved = requireNotNull(item.savedUri)
                // Only app-owned MediaStore rows can be safely moved without
                // resetting Android DATE_ADDED. Never copy/delete old bytes.
                // file://, SAF and DownloadManager URIs are skipped deliberately.
                if (dao.countSavedUriReferences(saved) != 1 ||
                    !belongsToLegacyDownloads(saved)) {
                    skipped++
                } else {
                    val migrated = try {
                        val declaredMime = runCatching {
                            context.contentResolver.getType(Uri.parse(saved))
                        }.getOrNull()
                        val mime = MediaStoragePolicy.legacyMime(
                            item.mediaType, item.originalName, declaredMime)
                        mime != null && storage.moveLegacyMediaStoreInPlace(
                            saved, item.originalName, mime)
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (error: Exception) {
                        // Abort on an unverified partial relocation. Its URI
                        // still points at the bytes and its Room row is intact.
                        if (error.message?.startsWith("Migration stopped:") == true) throw error
                        false
                    }
                    if (migrated) moved++ else skipped++
                }
                processed++
                onProgress(StorageMigrationProgress(total, processed, moved, skipped,
                    backups, true, "Проверено $processed из $total"))
            }
            StorageMigrationProgress(total, processed, moved, skipped, backups, false,
                "Перемещено без изменения даты: $moved. " +
                    "Пропущено ради сохранности: $skipped. Исходники не удалялись.").also(onProgress)
        }

    private fun belongsToLegacyDownloads(value: String): Boolean {
        val uri = Uri.parse(value)
        when (uri.scheme) {
            "file" -> {
                val source = uri.path?.let(::File) ?: return false
                val publicRoot = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS)
                return try {
                    val base = publicRoot.canonicalPath.trimEnd('/') + "/"
                    source.canonicalPath.startsWith(base) && source.isFile
                } catch (_: Exception) { false }
            }
            "content" -> {
                // MediaStore's own content URIs can be verified by RELATIVE_PATH.
                // External document providers without path ownership evidence
                // must be left alone, not guessed from filename or media type.
                if (uri.authority == "downloads") {
                    // getUriForDownloadedFile() emits content://downloads/...
                    // for app-owned system transfers. Query only this app's
                    // DownloadManager row, require a successful transfer and a
                    // canonical file:// location under public Download/.
                    val systemId = uri.lastPathSegment?.toLongOrNull()
                        ?.takeIf { it > 0L } ?: return false
                    return try {
                        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE)
                            as DownloadManager
                        manager.query(DownloadManager.Query().setFilterById(systemId))
                            ?.use { cursor ->
                                if (!cursor.moveToFirst()) return@use false
                                val status = cursor.getInt(cursor.getColumnIndexOrThrow(
                                    DownloadManager.COLUMN_STATUS))
                                if (status != DownloadManager.STATUS_SUCCESSFUL) return@use false
                                val location = cursor.getString(cursor.getColumnIndexOrThrow(
                                    DownloadManager.COLUMN_LOCAL_URI))
                                location != null && Uri.parse(location).scheme == "file" &&
                                    belongsToLegacyDownloads(location)
                            } == true
                    } catch (_: Exception) { false }
                }
                if (uri.authority != MediaStore.AUTHORITY) return false
                return try {
                    context.contentResolver.query(
                        uri, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH),
                        null, null, null,
                    )?.use { cursor ->
                        cursor.moveToFirst() && isLegacyPath(cursor.getString(0))
                    } == true
                } catch (_: Exception) { false }
            }
            else -> return false
        }
    }
}
