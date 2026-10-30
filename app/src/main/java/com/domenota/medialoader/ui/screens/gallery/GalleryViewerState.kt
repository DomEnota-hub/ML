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
    fun queue(ids: List<String>, rows: List<DownloadEntity>): List<DownloadEntity> = GridMediaQueue.resolve(ids, rows, false)
    fun neighbor(currentId: String?, snapshot: List<String>, available: Set<String>): String? {
        if (currentId in available) return currentId
        val index = snapshot.indexOf(currentId)
        return snapshot.drop((index + 1).coerceAtLeast(0)).firstOrNull { it in available }
            ?: snapshot.take(index.coerceAtLeast(0)).lastOrNull { it in available }
    }
    fun canCreateVideo(page: Int, settledPage: Int, dragging: Boolean) = page == settledPage && !dragging
}
