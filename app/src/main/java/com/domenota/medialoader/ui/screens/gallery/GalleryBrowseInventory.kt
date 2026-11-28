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
    // Index constructed with the browse generation, normally on the projection worker.
    private val allowedRows = items.associate { it.id to it.savedUri }
    fun containsAddress(row:DownloadEntity):Boolean =
        allowedRows.containsKey(row.id) && allowedRows[row.id]==row.savedUri

    /** Content equivalence across routine MediaStore/catalog re-emissions.
     * Preserve the previously verified grid and date-group cache if the actual
     * public album/media identity has not changed. Checked on Default, off UI. */
    fun equivalentContent(other: GalleryBrowseInventory): Boolean =
        items == other.items && assignments == other.assignments &&
        catalog == other.catalog && buckets == other.buckets &&
        media == other.media && albums == other.albums &&
        videos == other.videos && favorites == other.favorites &&
        hiddenFolders == other.hiddenFolders

    // Bounded per-generation cache: returning to Gallery does not reconstruct
    // dates/labels/queue. New browse/visibility generations own a separate cache.
    private val gridCache=object:LinkedHashMap<GalleryGridKey,GalleryGridInventory>(2,.75f,true){
        override fun removeEldestEntry(eldest:MutableMap.MutableEntry<GalleryGridKey,GalleryGridInventory>?):Boolean=size>2
    }
    fun cachedGrid(key:GalleryGridKey):GalleryGridInventory?=synchronized(gridCache){gridCache[key]}
    fun prepareGrid(key:GalleryGridKey):GalleryGridInventory {
        cachedGrid(key)?.let{return it}
        val built=com.domenota.medialoader.core.logging.DetailedPerformance.measure(com.domenota.medialoader.core.logging.PerfWork.GALLERY_DATES){GalleryGridInventory.build(this,key)}
        return synchronized(gridCache){gridCache[key]?:built.also{gridCache[key]=it}}
    }
    /** Visibility-only transition: remove rows, preserving ordering and warm grids.
     * No provider lookup, identity resolution, sort, decode or bitmap copy. */
    fun retainAllowed(allowed: (DownloadEntity) -> Boolean): GalleryBrowseInventory {
        if (items.all(allowed)) return this
        val kept = items.filter(allowed)
        val ids = kept.mapTo(HashSet()) { it.id }
        val byFolder = GalleryLibraryState.filter(kept, assignments, "all", "", buckets).groupBy { catalog[it.id]?.folderId }
        val byDevice = media.filter { it.id in ids }.groupBy { buckets[it.id]?.id }
        val nextAlbums = albums.map { album ->
            val rows = if (album.key.startsWith("folder:")) byFolder[album.key.removePrefix("folder:")].orEmpty()
                else byDevice[album.key.removePrefix("device:")].orEmpty()
            album.copy(count = rows.size, cover = rows.firstOrNull().takeIf { album.cover != null })
        }
        val next = GalleryBrowseInventory(kept, assignments.filter { it.downloadId in ids },
            catalog.filterKeys { it in ids }, buckets.filterKeys { it in ids }, media.filter { it.id in ids },
            nextAlbums, videos.filter { it.id in ids }, favorites.filter { it.id in ids }, hiddenFolders)
        synchronized(gridCache) {
            gridCache.forEach { (key, value) -> next.gridCache[key] = value.retainAllowed(key) { it.id in ids } }
        }
        return next
    }
    fun select(selection: String, search: String, noteMatches: Set<String> = emptySet()): List<DownloadEntity> {
        if (search.isBlank()) when (selection) {
            "all" -> return media
            "videos" -> return videos
            "favorites" -> return favorites
        }
        return GalleryLibraryState.filter(items, assignments, selection, search, buckets, hiddenFolders, noteMatches)
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
