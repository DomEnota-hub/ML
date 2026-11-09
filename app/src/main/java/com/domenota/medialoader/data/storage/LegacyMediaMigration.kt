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
 * Every item:
 *   validate source is under Download/ -> publish to correct MediaStore table
 *   -> compare actual contents/size via SHA-256 -> CAS-update only the savedUri
 *   -> delete old only after the new row is durably saved.
 *
 * Failures keep the original. A crash after CAS can leave a safe duplicate in
 * Download/, but the history points to the verified replacement. Re-running
 * safely skips already-moved rows.
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
                // Another catalog/download/private record may reference the same
                // bytes. Never delete a shared source behind other history rows.
                if (dao.countSavedUriReferences(saved) != 1 ||
                    !belongsToLegacyDownloads(saved)) {
                    skipped++
                } else {
                    var dest: PublishedFile? = null
                    var committed = false
                    try {
                        val declaredMime = runCatching {
                            context.contentResolver.getType(Uri.parse(saved))
                        }.getOrNull()
                        val mime = MediaStoragePolicy.legacyMime(
                            item.mediaType, item.originalName, declaredMime,
                        ) ?: throw IllegalArgumentException(
                            "Неизвестный или противоречивый MIME-тип старого файла."
                        )
                        val published = storage.publishVerifiedLegacyCopy(
                            saved, item.originalName, mime)
                        dest = published
                        currentCoroutineContext().ensureActive()
                        // No new history entry. Download ID, folder assignment,
                        // favorite, date and source metadata are untouched.
                        val changed = dao.moveSavedUriWhenCurrent(item.id, saved,
                            published.uri.toString(), published.fileName)
                        if (changed == 1) {
                            committed = true
                            moved++
                            // Old files are removed ONLY after a verified copy
                            // has replaced the address in persistent Room history.
                            // Failed deletion retains a backup and never reverts
                            // a correctly migrated history row.
                            val deleted = runCatching {
                                storage.deleteDownloadPermanently(saved)
                            }.getOrDefault(false)
                            if (!deleted) backups++
                        } else {
                            // The row changed concurrently (renamed/deleted/
                            // moved). Preserve the original and remove the
                            // newly copied unused MediaStore entry.
                            storage.deleteDownloadPermanently(dest.uri.toString())
                            skipped++
                        }
                    } catch (cancel: CancellationException) {
                        if (!committed && dest != null) runCatching {
                            storage.deleteDownloadPermanently(dest.uri.toString())
                        }
                        throw cancel
                    } catch (_: Exception) {
                        if (!committed && dest != null) runCatching {
                            storage.deleteDownloadPermanently(dest.uri.toString())
                        }
                        skipped++
                    }
                }
                processed++
                onProgress(StorageMigrationProgress(total, processed, moved, skipped,
                    backups, true, "Проверено $processed из $total"))
            }
            StorageMigrationProgress(total, processed, moved, skipped, backups, false,
                "Перенесено: $moved. Пропущено: $skipped. " +
                    "Сохранено резервных копий: $backups.").also(onProgress)
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
