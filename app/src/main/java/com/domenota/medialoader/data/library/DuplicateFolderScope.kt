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
    val sizeBytes: Long? = null,
    /** Verified on-device directory identity; a bucket display name is not a path. */
    val directoryId: String? = null,
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
        // Unknown directory identity must NEVER collapse unrelated files into
        // a provider-wide pseudo-folder. Exact URI only, with a numbered UI label.
        return "unspecified:" + uri
    }

    fun options(rows: List<DownloadEntity>): List<DuplicateFolderOption> =
        rows.asSequence().filter(::eligible).distinctBy { it.savedUri }
            .groupBy(::key).map { (key, grouped) ->
                val row = grouped.first()
                val location = when {
                    key.startsWith("file:") -> key.removePrefix("file:").removePrefix("file://")
                    row.mediaType == MediaType.AUDIO -> row.groupId.orEmpty().substringAfter(':')
                    else -> row.groupId.orEmpty()
                }.trimEnd('/')
                val name = if (key.startsWith("unspecified:")) "Папка не определена"
                    else location.substringAfterLast('/').ifBlank { "Корневая папка" }
                val bytes = if(grouped.all{it.sizeBytes!=null}) grouped.sumOf{it.sizeBytes!!} else null
                val directory = when {
                    key.startsWith("file:") -> "file|" + location
                    row.mediaType == MediaType.AUDIO && !row.groupId.isNullOrBlank() &&
                        row.groupId.contains(':') -> {
                        val volume = row.groupId.substringBefore(':')
                        val relative = row.groupId.substringAfter(':')
                        if(volume.isNotBlank() && relative.isNotBlank() &&
                            !relative.startsWith('/') && relative.split('/').none{it==".."||it=="."})
                            "volume:$volume|$relative" else null
                    }
                    else -> null // Gallery bucket names alone are not reliable hierarchy.
                }
                DuplicateFolderOption(key, name, location.ifBlank { "Путь не определён" }, grouped.size, bytes, directory)
            }.sortedWith(compareBy<DuplicateFolderOption> { it.name.lowercase() }.thenBy { it.location })
            .let { ordered ->
                // Several unrelated MediaStore rows may have no verifiable
                // directory. Number only their display labels; preserve exact
                // keys and never merge these buckets or broaden scan scope.
                val unknown = ordered.filter { it.name == "Папка не определена" }
                    .sortedBy { it.key }.mapIndexed { index, option ->
                        option.key to (index + 1)
                    }.toMap()
                ordered.map { option ->
                    val number = unknown[option.key]
                    if (number == null) option else option.copy(
                        name = "Папка без пути №$number",
                        location = "Расположение не определено"
                    )
                }
            }

    /** null = explicit whole library; empty set = no selection, never all. */
    fun <T> filter(items: List<T>, keys: Set<String>?, row: (T) -> DownloadEntity): List<T> =
        items.filter { item -> eligible(row(item)) && (keys == null || key(row(item)) in keys) }

    fun label(keys: Set<String>?): String =
        if (keys == null) "Все файлы" else "Выборочно: ${keys.size} ${folderWord(keys.size)}"

    fun folderWord(count: Int): String = when {
        count % 100 in 11..14 -> "папок"
        count % 10 == 1 -> "папка"
        count % 10 in 2..4 -> "папки"
        else -> "папок"
    }
}
