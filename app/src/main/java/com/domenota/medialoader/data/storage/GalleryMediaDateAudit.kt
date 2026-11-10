package com.domenota.medialoader.data.storage

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.domenota.medialoader.core.database.DownloadDao
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * A read-only audit to distinguish an on-disk mtime repair from MediaStore's
 * original insertion date. No file or provider writes. Never examines Private.
 */
internal class GalleryMediaDateAudit(
    private val context: Context,
    private val dao: DownloadDao,
) {
    data class Summary(
        val examined: Int,
        val owned: Int,
        val oldDownloadsReadded: Int,
        val mtimeRecoveredButAddedNew: Int,
        val takenMetadataPresent: Int,
        val unsupported: Int,
    ) {
        fun userMessage(): String =
            "Проверено: $examined. Свои файлы: $owned. " +
                "Новая дата добавления вместо исторической: $oldDownloadsReadded. " +
                "Дата файла исправлена, дата добавления осталась новой: " +
                "$mtimeRecoveredButAddedNew. Метаданные даты съёмки: " +
                "$takenMetadataPresent. Не удалось проверить: $unsupported. " +
                "Это только проверка MediaStore, без изменения файлов."
    }

    suspend fun inspect(): Summary = withContext(Dispatchers.IO) {
        val rows = dao.all().filter {
            !it.isPrivate && !it.hidden && it.state == DownloadState.COMPLETED &&
                it.mediaType in setOf(MediaType.PHOTO, MediaType.VIDEO) &&
                it.createdAtEpochMillis > 0L &&
                it.savedUri?.startsWith("content://media/") == true
        }
        if (Build.VERSION.SDK_INT < 29) return@withContext Summary(0, 0, 0, 0, 0, rows.size)
        val frequency = rows.mapNotNull { it.savedUri }.groupingBy { it }.eachCount()
        var seen = 0
        var owned = 0
        var readded = 0
        var repairedButAdded = 0
        var datedMedia = 0
        var skipped = 0
        for (row in rows) {
            if (frequency[row.savedUri] != 1) { skipped++; continue }
            val uri = Uri.parse(row.savedUri)
            if (uri.authority != MediaStore.AUTHORITY) { skipped++; continue }
            try {
                val columns = arrayOf(
                    MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
                    MediaStore.MediaColumns.DATE_ADDED,
                    MediaStore.MediaColumns.DATE_MODIFIED,
                    MediaStore.MediaColumns.DATE_TAKEN,
                )
                val snapshot = context.contentResolver.query(uri, columns, null, null, null)
                    ?.use { cursor ->
                        if (!cursor.moveToFirst()) return@use null
                        fun long(key: String) = cursor.getLong(cursor.getColumnIndexOrThrow(key))
                        val owner = cursor.getString(cursor.getColumnIndexOrThrow(
                            MediaStore.MediaColumns.OWNER_PACKAGE_NAME))
                        MediaStoreDateSnapshot(
                            owner = owner,
                            addedMillis = long(MediaStore.MediaColumns.DATE_ADDED) * 1000L,
                            modifiedMillis = long(MediaStore.MediaColumns.DATE_MODIFIED) * 1000L,
                            takenMillis = long(MediaStore.MediaColumns.DATE_TAKEN),
                        )
                    }
                if (snapshot == null) { skipped++; continue }
                seen++
                if (snapshot.owner != context.packageName) continue
                owned++
                val diff = MediaStoreDateAuditPolicy.analyze(
                    originalMillis = row.createdAtEpochMillis,
                    addedMillis = snapshot.addedMillis,
                    modifiedMillis = snapshot.modifiedMillis,
                    takenMillis = snapshot.takenMillis,
                )
                if (diff.addedNewerThanOriginal) readded++
                if (diff.modifiedRestoredButAddedNew) repairedButAdded++
                if (diff.hasTakenDate) datedMedia++
            } catch (_: Exception) {
                skipped++
            }
        }
        Summary(seen, owned, readded, repairedButAdded, datedMedia, skipped)
    }
}

internal data class MediaStoreDateSnapshot(
    val owner: String?,
    val addedMillis: Long,
    val modifiedMillis: Long,
    val takenMillis: Long,
)
internal data class MediaStoreDateDifference(
    val addedNewerThanOriginal: Boolean,
    val modifiedRestoredButAddedNew: Boolean,
    val hasTakenDate: Boolean,
)
internal object MediaStoreDateAuditPolicy {
    fun analyze(originalMillis: Long, addedMillis: Long,
                modifiedMillis: Long, takenMillis: Long): MediaStoreDateDifference {
        val addedNew = originalMillis > 0L &&
            addedMillis > originalMillis + 86_400_000L
        val restoredMtime = modifiedMillis > 0L &&
            abs(modifiedMillis - originalMillis) <= 60_000L
        return MediaStoreDateDifference(
            addedNewerThanOriginal = addedNew,
            modifiedRestoredButAddedNew = addedNew && restoredMtime,
            hasTakenDate = takenMillis > 0L,
        )
    }
}
