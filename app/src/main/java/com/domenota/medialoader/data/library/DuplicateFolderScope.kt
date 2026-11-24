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

    /** Null and invalid sizes are unknown, never zero. Overflow cannot wrap to a fake total. */
    internal fun totalKnownBytes(sizes: Iterable<Long?>): Long? {
        var total = 0L
        for (size in sizes) {
            if (size == null || size < 0L || total > Long.MAX_VALUE - size) return null
            total += size
        }
        return total
    }

    /** A directory may have several bucket keys; the visible folder is counted once,
     * while the exact per-key selection and the sum of unique files are preserved.
     */
    internal data class SelectionSummary(val folderCount: Int, val fileCount: Int, val sizeBytes: Long?)

    fun selectionSummary(options: List<DuplicateFolderOption>, selected: Set<String>): SelectionSummary {
        val chosen = options.filter { it.key in selected }.distinctBy { it.key }
        return SelectionSummary(
            folderCount = chosen.distinctBy { it.directoryId ?: "key:${it.key}" }.size,
            fileCount = chosen.sumOf { it.fileCount },
            sizeBytes = totalKnownBytes(chosen.map { it.sizeBytes }),
        )
    }

    fun options(rows: List<DownloadEntity>): List<DuplicateFolderOption> =
        rows.asSequence().filter(::eligible).distinctBy { it.savedUri }
            .groupBy(::key).map { (key, grouped) ->
                val row = grouped.first()
                // Only on-device directory evidence may become a navigable parent.
                // Visual bucket/group names are display hints, not verified paths.
                val directory = when {
                    key.startsWith("file:") -> {
                        val path = key.removePrefix("file:").removePrefix("file://").trimEnd('/')
                        val segments = path.split('/').filter { it.isNotBlank() }
                        if (segments.isNotEmpty() && segments.none {
                                it == "." || it == ".." || '\\' in it || '|' in it
                            }) "file|$path" else null
                    }
                    row.mediaType == MediaType.AUDIO && !row.groupId.isNullOrBlank() &&
                        row.groupId.contains(':') -> {
                        val volume = row.groupId.substringBefore(':')
                        val relative = row.groupId.substringAfter(':').trimEnd('/')
                        val segments = relative.split('/')
                        if (volume.isNotBlank() && volume.none { it == '/' || it == '\\' || it == '|' } &&
                            relative.isNotBlank() && !relative.startsWith('/') &&
                            segments.all { it.isNotBlank() && it != "." && it != ".." &&
                                '\\' !in it && '|' !in it }) "volume:$volume|$relative" else null
                    }
                    else -> null
                }
                val location = directory?.substringAfter('|') ?: "Расположение не определено"
                val hint = row.groupId.orEmpty().trimEnd('/').substringAfterLast('/')
                    .substringAfterLast(':')
                val name = if (directory != null) location.substringAfterLast('/')
                    .ifBlank { "Корневая папка" }
                    else hint.ifBlank { "Папка не определена" }
                DuplicateFolderOption(
                    key, name, location, grouped.size, totalKnownBytes(grouped.map { it.sizeBytes }),
                    directory,
                )
            }.sortedWith(compareBy<DuplicateFolderOption> { it.name.lowercase() }
                .thenBy { it.location }.thenBy { it.key })
            .let { ordered ->
                // Identical names can belong to unrelated MediaStore buckets.
                // Suffix *labels*, never identities, and never create a common parent.
                val disambiguated = ordered.filter { it.directoryId == null }
                    .groupBy { it.name }.flatMap { (name, group) ->
                        group.sortedBy { it.key }.mapIndexed { index, option ->
                            val numbered = name == "Папка не определена" || group.size > 1
                            option.key to if (numbered) {
                                if (name == "Папка не определена") "Папка без пути №${index + 1}"
                                else "$name №${index + 1}"
                            } else name
                        }
                    }.toMap()
                ordered.map { option -> option.copy(name = disambiguated[option.key] ?: option.name) }
            }

    /** null = explicit whole library; empty set = no selection, never all. */
    fun <T> filter(items: List<T>, keys: Set<String>?, row: (T) -> DownloadEntity): List<T> =
        items.filter { item -> eligible(row(item)) && (keys == null || key(row(item)) in keys) }

    fun label(keys: Set<String>?): String =
        if (keys == null) "Все файлы" else "Выборочно: ${keys.size} ${folderWord(keys.size)}"

    fun fileWord(count: Int): String = when {
        count % 100 in 11..14 -> "файлов"
        count % 10 == 1 -> "файл"
        count % 10 in 2..4 -> "файла"
        else -> "файлов"
    }

    fun folderWord(count: Int): String = when {
        count % 100 in 11..14 -> "папок"
        count % 10 == 1 -> "папка"
        count % 10 in 2..4 -> "папки"
        else -> "папок"
    }
}
