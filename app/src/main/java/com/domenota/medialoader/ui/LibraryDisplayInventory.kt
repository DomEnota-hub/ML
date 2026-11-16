package com.domenota.medialoader.ui

import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.data.library.ExternalMediaEntry
import com.domenota.medialoader.data.library.LibraryMediaRef
import com.domenota.medialoader.ui.screens.gallery.DeviceAlbum

/** Derived display values belong to one immutable, already guarded library generation.
 * Constructed on the index worker, not on a Compose frame. Never authorizes file access. */
internal class LibraryDisplayInventory private constructor(
    val items: List<DownloadEntity>,
    val audio: List<DownloadEntity>,
    val appItems: List<DownloadEntity>,
    val appAudio: List<DownloadEntity>,
    val deviceAlbums: Map<String, DeviceAlbum>,
) {
    companion object {
        val EMPTY = LibraryDisplayInventory(emptyList(), emptyList(), emptyList(), emptyList(), emptyMap())
        fun build(refs: List<LibraryMediaRef>, external: List<ExternalMediaEntry>): LibraryDisplayInventory {
            if (refs.isEmpty()) return EMPTY
            val byAlias = buildMap {
                external.forEach { entry -> (entry.ref.aliases + entry.ref.key).forEach { put(it, entry) } }
            }
            val items = refs.map { it.item }
            val app = refs.filter { ref -> ref.appDownloadId != null ||
                ref.aliases.any { byAlias[it]?.folderId != null } }.map { it.item }
            val albums = buildMap {
                refs.forEach { ref -> ref.deviceAlbumId?.let { put(ref.key, DeviceAlbum(it, ref.deviceAlbumName ?: "Без альбома")) } }
            }
            return LibraryDisplayInventory(items, items.filter { it.mediaType == MediaType.AUDIO },
                app, app.filter { it.mediaType == MediaType.AUDIO }, albums)
        }
    }
}
