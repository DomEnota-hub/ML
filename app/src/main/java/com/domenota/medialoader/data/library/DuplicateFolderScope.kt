package com.domenota.medialoader.data.library

import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType

/** Folder identity comes from MediaStore metadata, never from a display name
 * alone. Missing folder information is displayed honestly as an unclassified
 * group rather than silently scanning the entire phone.
 */
internal data class DuplicateFolderOption(
    val key: String,
    val name: String,
    val location: String,
    val fileCount: Int,
)

internal object DuplicateFolderScope {
    fun eligible(row: DownloadEntity): Boolean =
        !row.isPrivate && !row.hidden && row.state == DownloadState.COMPLETED &&
            !row.savedUri.isNullOrBlank()

    fun key(row: DownloadEntity): String {
        val uri = row.savedUri.orEmpty()
        if (uri.startsWith("file://", ignoreCase = true))
            return "file:" + uri.substringBeforeLast('/', uri)
        if (row.mediaType == MediaType.AUDIO && !row.groupId.isNullOrBlank())
            return "audio:" + row.groupId
        if (row.mediaType != MediaType.AUDIO && !row.bulkMediaKey.isNullOrBlank())
            return "bucket:" + row.bulkMediaKey
        if (!row.groupId.isNullOrBlank())
            return "group:" + row.mediaType.name + ":" + row.groupId
        return "unspecified:" + row.providerId
    }

    fun options(rows: List<DownloadEntity>): List<DuplicateFolderOption> =
        rows.asSequence().filter(::eligible).distinctBy { it.savedUri }
            .groupBy(::key).map { (key, grouped) ->
                val row = grouped.first()
                val location = when {
                    key.startsWith("file:") -> key.removePrefix("file:")
                    row.mediaType == MediaType.AUDIO -> row.groupId.orEmpty().substringAfter(':')
                    else -> row.groupId.orEmpty()
                }.trimEnd('/')
                val name = if (key.startsWith("unspecified:")) "Папка не определена"
                    else location.substringAfterLast('/').ifBlank { "Корневая папка" }
                DuplicateFolderOption(key, name, location.ifBlank { "Путь не определён" }, grouped.size)
            }.sortedWith(compareBy<DuplicateFolderOption> { it.name.lowercase() }.thenBy { it.location })

    /** null = explicit whole library; empty set = no selection, never all. */
    fun <T> filter(items: List<T>, keys: Set<String>?, row: (T) -> DownloadEntity): List<T> =
        items.filter { item -> eligible(row(item)) && (keys == null || key(row(item)) in keys) }

    fun label(keys: Set<String>?): String =
        if (keys == null) "Все файлы" else "Выборочно: ${keys.size} папок"
}
