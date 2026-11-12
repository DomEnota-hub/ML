package com.domenota.medialoader.ui.screens.gallery

import com.domenota.medialoader.core.database.*
import com.domenota.medialoader.core.model.*

data class DeviceAlbum(val id: String, val name: String)
data class GalleryAlbum(val key: String, val name: String, val origin: String, val count: Int, val cover: DownloadEntity?)

data class GalleryAlbumLink(val key: String, val title: String)
data class GalleryAlbumRequest(val album: GalleryAlbumLink, val itemId: String)

object GalleryLibraryState {
    /** A real catalog assignment and/or indexed device album, never a guessed folder name. */
    fun albumLinks(row: DownloadEntity, folders: List<CatalogFolderEntity>, assignments: List<CatalogAssignmentEntity>,
                   deviceAlbums: Map<String, DeviceAlbum>, preferred: String? = null): List<GalleryAlbumLink> {
        if (row.hidden || row.isPrivate || row.state != DownloadState.COMPLETED || row.mediaType !in setOf(MediaType.PHOTO, MediaType.VIDEO)) return emptyList()
        val result = buildList {
            val ids = assignments.filter { it.downloadId == row.id }.mapNotNull { it.folderId }.toSet()
            folders.filter { it.id in ids }.forEach { add(GalleryAlbumLink("folder:${it.id}", it.name)) }
            deviceAlbums[row.id]?.let { add(GalleryAlbumLink("device:${it.id}", it.name)) }
        }.distinctBy { it.key }
        return result.sortedBy { it.key != preferred }
    }
    fun gridIndex(groups: List<List<String>>, id: String, grouped: Boolean): Int? {
        var index = 1 // controls cell
        for (group in groups) {
            if (grouped) index++ // date header
            val found = group.indexOf(id)
            if (found >= 0) return index + found
            index += group.size
        }
        return null
    }

    fun filter(items: List<DownloadEntity>, assignments: List<CatalogAssignmentEntity>, selection: String,
               query: String, deviceAlbums: Map<String, DeviceAlbum>): List<DownloadEntity> {
        val catalog = assignments.associateBy { it.downloadId }
        return items.filter { row ->
            !row.hidden && !row.isPrivate && row.state == DownloadState.COMPLETED &&
                row.mediaType in setOf(MediaType.PHOTO, MediaType.VIDEO) && row.originalName.contains(query.trim(), true) &&
                when {
                    selection == "favorites" -> catalog[row.id]?.isFavorite == true
                    selection == "videos" -> row.mediaType == MediaType.VIDEO
                    selection.startsWith("folder:") -> catalog[row.id]?.folderId == selection.removePrefix("folder:")
                    selection.startsWith("device:") -> deviceAlbums[row.id]?.id == selection.removePrefix("device:")
                    else -> true
                }
        }
    }
    fun albums(items: List<DownloadEntity>, folders: List<CatalogFolderEntity>, assignments: List<CatalogAssignmentEntity>,
               deviceAlbums: Map<String, DeviceAlbum>): List<GalleryAlbum> {
        val media = filter(items, assignments, "all", "", deviceAlbums)
        val catalog = assignments.associateBy { it.downloadId }
        val app = folders.map { folder ->
            val rows = media.filter { catalog[it.id]?.folderId == folder.id }
            GalleryAlbum("folder:${folder.id}", folder.name, "Приложение", rows.size, rows.firstOrNull())
        }
        val device = media.groupBy { deviceAlbums[it.id]?.id }.mapNotNull { (id, rows) ->
            if (id == null) null else GalleryAlbum("device:$id", deviceAlbums.getValue(rows.first().id).name,
                "Телефон", rows.size, rows.first())
        }.sortedBy { it.name.lowercase() }
        return app + device
    }
}
