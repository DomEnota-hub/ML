package com.domenota.medialoader.data.library

import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState

/**
 * Restore the exact original download time in the unified gallery, even when
 * Android has assigned a fresh MediaStore DATE_ADDED after the 1.7.158 copy.
 *
 * We only trust a single, completed, public app-history row with an EXACT
 * savedUri match. Names, sizes, thumbnails and inferred path aliases are not
 * identities: another app could own identically named media.
 *
 * This is read-only: it never changes files, MediaStore dates, or Room history.
 */
internal object PhoneLibraryChronology {
    fun reconcile(phone: List<DownloadEntity>, app: List<DownloadEntity>): List<DownloadEntity> {
        val exactMatches = app.asSequence()
            .filter { it.state == DownloadState.COMPLETED && !it.hidden && !it.isPrivate &&
                !it.savedUri.isNullOrBlank() && it.createdAtEpochMillis > 0L }
            .groupBy { it.savedUri!! }
            .mapNotNull { (uri, records) ->
                records.singleOrNull()?.let { uri to it }
            }
            .toMap()

        if (exactMatches.isEmpty()) return phone

        return phone.map { row ->
            val appRow = row.savedUri?.let(exactMatches::get)
            if (appRow != null && appRow.mediaType == row.mediaType &&
                appRow.createdAtEpochMillis != row.createdAtEpochMillis) {
                row.copy(createdAtEpochMillis = appRow.createdAtEpochMillis)
            } else row
        }.sortedWith(compareByDescending<DownloadEntity> { it.createdAtEpochMillis }.thenBy { it.id })
    }
}
