package com.domenota.medialoader.ui

import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.data.library.LibraryMediaRef
import com.domenota.medialoader.data.library.collectionRefBlocked
import com.domenota.medialoader.data.storage.SavedFileStatus

/** Semantic access keys, separate from ordinary library revisions. */
internal data class CollectionAccessGuard(val privacy: Set<String>, val blocked: Set<String>,
    val galleryEpoch: Long, val musicEpoch: Long)
internal data class CollectionDetailGuard(val access: CollectionAccessGuard, val id: String)
internal data class RecentAccessGuard(val access: AudioCatalogGuard, val blocked: Set<String>, val source: com.domenota.medialoader.data.library.LibrarySource)
internal data class RouteMediaCatalog(val refs: List<LibraryMediaRef>) {
    val byKey = refs.associateBy { it.key }
    val items = refs.map { it.item }
    companion object { val EMPTY = RouteMediaCatalog(emptyList()) }
}

/** Filter a generation with current history/status on the background projection, not
 * in root composition. Status from an old URI cannot suppress a recovered file. */
internal fun filterRouteCatalog(refs: List<LibraryMediaRef>, blocked: Set<String>, history: Map<String, DownloadEntity>,
    fileStatuses: Map<String, SavedFileStatus>, fileStatusUris: Map<String, String?>): List<LibraryMediaRef> = refs.filter { ref ->
    !collectionRefBlocked(ref, blocked) &&
        (ref.appDownloadId == null || history[ref.appDownloadId]?.let { current ->
            !current.isPrivate && !current.hidden && current.savedUri == ref.item.savedUri && current.state == DownloadState.COMPLETED
        } == true) && (fileStatusUris[ref.key] != ref.item.savedUri ||
        (fileStatuses[ref.key] != SavedFileStatus.MISSING && fileStatuses[ref.key] != SavedFileStatus.INACCESSIBLE))
}
