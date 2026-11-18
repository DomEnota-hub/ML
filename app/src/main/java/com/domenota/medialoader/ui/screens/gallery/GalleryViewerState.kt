package com.domenota.medialoader.ui.screens.gallery

import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.data.playback.GridMediaQueue
import com.domenota.medialoader.data.library.LibraryMediaRef
import com.domenota.medialoader.data.library.UnifiedMediaLibrary

object GalleryViewerState {
    /** Resolve historical route IDs only from the current validated public index. Never add
     * raw history rows here: they can lag behind an in-flight move to Personal. */
    fun publicCandidates(refs: List<LibraryMediaRef>, privateKeys: Set<String>?, routeIds: Set<String> = emptySet()): List<DownloadEntity> {
        if (privateKeys == null) return emptyList()
        return UnifiedMediaLibrary.merge(refs, emptyList(), privateKeys).flatMap { ref ->
            (ref.aliases.filter { it.startsWith("phone:") || it in routeIds } + ref.key).distinct().map { ref.item.copy(id = it) }
        }
    }
    /** The normal public Gallery route receives LibraryIndex.refs, already
     * grouped off-main by UnifiedMediaLibrary.merge and revalidated against
     * current privacy/visibility. Do NOT repeat that costly 11k-row merge on
     * the player/composition thread. Scoped/unmerged queues use the regular
     * publicCandidates() path instead. */
    fun publicCandidatesFromUnified(refs: List<LibraryMediaRef>, privateKeys: Set<String>?,
        routeIds: Set<String> = emptySet()): List<DownloadEntity> {
        if (privateKeys == null) return emptyList()
        return refs.asSequence()
            .filter { ref ->
                !ref.item.hidden && !ref.item.isPrivate &&
                    ref.item.state == com.domenota.medialoader.core.model.DownloadState.COMPLETED &&
                    !ref.item.savedUri.isNullOrBlank() &&
                    ref.key !in privateKeys && ref.item.id !in privateKeys &&
                    ref.aliases.none { it in privateKeys }
            }
            .flatMap { ref ->
                (ref.aliases.filter { it.startsWith("phone:") || it in routeIds } + ref.key)
                    .distinct().asSequence().map { ref.item.copy(id = it) }
            }.toList()
    }
    /** The caller supplies an already-authorized, isolated vault queue.
     * Preserve that scope through the viewer's own final queue filter. */
    fun queue(ids: List<String>, rows: List<DownloadEntity>, privateMode: Boolean = false): List<DownloadEntity> =
        GridMediaQueue.resolve(ids, rows, privateMode)
    /** A route identifies a file, never a position in a potentially incomplete queue. */
    fun exactItem(id: String?, expectedUri: String?, rows: List<DownloadEntity>): DownloadEntity? =
        rows.singleOrNull { it.id == id && (expectedUri == null || it.savedUri == expectedUri) }

    /** A freshly addressed app row can seed this route only while its live identity and
     * public access still agree. It must not become a general-purpose catalog fallback. */
    fun addressedPublic(row: DownloadEntity?, verified: LibraryMediaRef?, privateKeys: Set<String>?): DownloadEntity? {
        if (row == null || verified == null || privateKeys == null) return null
        if (row.id != verified.key || row.savedUri != verified.item.savedUri || row != verified.item ||
            row.hidden || row.isPrivate || row.state != com.domenota.medialoader.core.model.DownloadState.COMPLETED ||
            row.savedUri.isNullOrBlank() || row.mediaType == com.domenota.medialoader.core.model.MediaType.AUDIO ||
            row.id in privateKeys || verified.aliases.any(privateKeys::contains)) return null
        return row
    }

    fun neighbor(currentId: String?, snapshot: List<String>, available: Set<String>): String? {
        if (currentId in available) return currentId
        val index = snapshot.indexOf(currentId)
        return snapshot.drop((index + 1).coerceAtLeast(0)).firstOrNull { it in available }
            ?: snapshot.take(index.coerceAtLeast(0)).lastOrNull { it in available }
    }
    /** A tentative finger drag must not destroy/recreate the same playing video.
     * Release only after the pager settles on a different exact page; the
     * caller still gates the actual file against the current authorized queue. */
    fun canCreateVideo(page: Int, settledPage: Int, dragging: Boolean) = page == settledPage
}
