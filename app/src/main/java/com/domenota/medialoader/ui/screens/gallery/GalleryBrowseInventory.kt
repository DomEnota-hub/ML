package com.domenota.medialoader.ui.screens.gallery

import com.domenota.medialoader.core.database.*
import com.domenota.medialoader.data.library.LibrarySource
import com.domenota.medialoader.data.library.LibraryMediaRef

/** Mode-independent prepared gallery snapshot. All indexes and album summaries
 * come from the same generation; the caller gates retention on access and visibility. */
internal class GalleryBrowseInventory private constructor(
    val items: List<DownloadEntity>,
    val assignments: List<CatalogAssignmentEntity>,
    val catalog: Map<String, CatalogAssignmentEntity>,
    val buckets: Map<String, DeviceAlbum>,
    val media: List<DownloadEntity>,
    val albums: List<GalleryAlbum>,
    val videos: List<DownloadEntity>,
    val favorites: List<DownloadEntity>,
    private val hiddenFolders: Set<String>,
) {
    fun select(selection: String, search: String): List<DownloadEntity> {
        if (search.isBlank()) when (selection) {
            "all" -> return media
            "videos" -> return videos
            "favorites" -> return favorites
        }
        return GalleryLibraryState.filter(items, assignments, selection, search, buckets, hiddenFolders)
    }
    companion object {
        val EMPTY = build(emptyList(), emptyList(), emptyList(), LibrarySource.APP, emptyMap(), emptySet())
        /** Missing or stale assignment must not turn a verified hidden row into an unassigned public row. */
        fun withScopedItems(publicItems: List<DownloadEntity>, hiddenRefs: List<LibraryMediaRef>,
            assignments: List<CatalogAssignmentEntity>, hiddenFolders: Set<String>,
            explicitFolder: (LibraryMediaRef) -> String?): List<DownloadEntity> {
            if (hiddenRefs.isEmpty()) return publicItems
            val byId = assignments.associateBy { it.downloadId }
            val scoped = hiddenRefs.filter { ref ->
                val folder = explicitFolder(ref)
                folder != null && folder in hiddenFolders && byId[ref.item.id]?.folderId == folder
            }.map { it.item }
            return if (scoped.isEmpty()) publicItems else publicItems + scoped
        }
        fun build(items: List<DownloadEntity>, folders: List<CatalogFolderEntity>,
            assignments: List<CatalogAssignmentEntity>, source: LibrarySource,
            deviceAlbums: Map<String, DeviceAlbum>, hiddenFolders: Set<String>): GalleryBrowseInventory {
            val catalog = assignments.associateBy { it.downloadId }
            val buckets = if (source == LibrarySource.APP) emptyMap() else if (deviceAlbums.isNotEmpty()) deviceAlbums
                else items.mapNotNull { row -> row.groupId?.let { row.id to DeviceAlbum(it, it) } }.toMap()
            val media = GalleryLibraryState.filter(items, assignments, "all", "", buckets, hiddenFolders)
            return GalleryBrowseInventory(items, assignments, catalog, buckets, media,
                GalleryLibraryState.albums(items, folders, assignments, buckets, hiddenFolders),
                GalleryLibraryState.filter(media, assignments, "videos", "", buckets),
                GalleryLibraryState.filter(media, assignments, "favorites", "", buckets), hiddenFolders)
        }
    }
}
