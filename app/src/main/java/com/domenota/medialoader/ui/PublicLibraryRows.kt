package com.domenota.medialoader.ui

import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.data.library.LibraryMediaRef

/** Keep history precedence and old phone aliases, with one immutable result per generation.
 * The captured predicate is only for display; opening a file still checks live access. */
internal object PublicLibraryRows {
    fun indexed(downloads: List<DownloadEntity>, refs: List<LibraryMediaRef>,
        allowed: (DownloadEntity) -> Boolean): List<DownloadEntity> {
        val knownIds = downloads.mapTo(mutableSetOf()) { it.id }
        return buildList {
            downloads.forEach { if (!it.isPrivate && allowed(it)) add(it) }
            refs.forEach { ref ->
                (ref.aliases.filter { it.startsWith("phone:") } + ref.key).distinct().forEach { id ->
                    if (knownIds.add(id)) {
                        val row = ref.item.copy(id = id)
                        if (!row.isPrivate && allowed(row)) add(row)
                    }
                }
            }
        }
    }
    fun mergeSession(indexed: List<DownloadEntity>, session: List<DownloadEntity>,
        allowed: (DownloadEntity) -> Boolean): List<DownloadEntity> {
        if (session.isEmpty()) return indexed
        val knownIds = indexed.mapTo(mutableSetOf()) { it.id }
        val additions = session.filter { !it.isPrivate && allowed(it) && knownIds.add(it.id) }
        return if (additions.isEmpty()) indexed else indexed + additions
    }
}
