package com.domenota.medialoader.ui.screens.gallery

import android.content.Context
import com.domenota.medialoader.core.database.CatalogAssignmentEntity
import com.domenota.medialoader.core.database.DownloadEntity
import org.json.JSONArray

/**
 * Album visibility is presentation-only. Hidden albums stay readable in "Другие альбомы",
 * and hiding their contents in "Медиа" never edits, moves, or deletes source files.
 */
internal data class GalleryAlbumSettings(
    val hiddenAlbums: Set<String> = emptySet(),
    val excludedFromMedia: Set<String> = emptySet(),
    val order: List<String> = emptyList(),
)

internal class GalleryAlbumSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "gallery_album_organization_v1", Context.MODE_PRIVATE
    )

    fun load(source: String): GalleryAlbumSettings {
        val prefix = "$source:"
        val order = runCatching {
            JSONArray(preferences.getString(prefix + "order", "[]")).let { array ->
                (0 until array.length()).map { array.getString(it) }.distinct()
            }
        }.getOrDefault(emptyList())
        return GalleryAlbumSettings(
            hiddenAlbums = preferences.getStringSet(prefix + "hidden", emptySet()).orEmpty().toSet(),
            excludedFromMedia = preferences.getStringSet(prefix + "exclude", emptySet()).orEmpty().toSet(),
            order = order,
        )
    }

    fun save(source: String, settings: GalleryAlbumSettings): Boolean {
        val prefix = "$source:"
        return preferences.edit()
            .putStringSet(prefix + "hidden", settings.hiddenAlbums.toSet())
            .putStringSet(prefix + "exclude", settings.excludedFromMedia.toSet())
            .putString(prefix + "order", JSONArray(settings.order).toString())
            .commit()
    }
}

/** Each item may belong to both a manually created folder and a device album. */
internal fun galleryAlbumKeysForMedia(
    item: DownloadEntity,
    assignment: CatalogAssignmentEntity?,
    deviceAlbum: DeviceAlbum?,
): Set<String> = buildSet {
    assignment?.folderId?.let { add("folder:$it") }
    deviceAlbum?.id?.let { add("device:$it") }
}

/** Hide from the general "Медиа" grid only, not from opening its album. */
internal fun visibleInGalleryMedia(
    item: DownloadEntity,
    assignment: CatalogAssignmentEntity?,
    deviceAlbum: DeviceAlbum?,
    excluded: Set<String>,
): Boolean = galleryAlbumKeysForMedia(item, assignment, deviceAlbum).none { it in excluded }

internal fun orderedGalleryAlbums(
    albums: List<GalleryAlbum>,
    settings: GalleryAlbumSettings,
): List<GalleryAlbum> {
    val order = settings.order.withIndex().associate { (index, key) -> key to index }
    return albums.withIndex().sortedWith(compareBy<IndexedValue<GalleryAlbum>> {
        order[it.value.key] ?: Int.MAX_VALUE
    }.thenBy { it.index }).map { it.value }
}

internal fun moveGalleryAlbum(
    settings: GalleryAlbumSettings,
    allAlbums: List<GalleryAlbum>,
    from: String,
    before: String,
): GalleryAlbumSettings {
    if (from == before) return settings
    val keys = orderedGalleryAlbums(allAlbums, settings).map { it.key }.toMutableList()
    if (from !in keys || before !in keys) return settings
    val fromIndex = keys.indexOf(from)
    val targetIndex = keys.indexOf(before)
    keys.removeAt(fromIndex)
    // Dropping a card on one below it must move it after that card.
    val insertion = keys.indexOf(before) + if (fromIndex < targetIndex) 1 else 0
    keys.add(insertion.coerceIn(0, keys.size), from)
    return settings.copy(order = keys)
}

internal fun removeGalleryAlbumSettings(settings: GalleryAlbumSettings, key: String) =
    settings.copy(
        hiddenAlbums = settings.hiddenAlbums - key,
        excludedFromMedia = settings.excludedFromMedia - key,
        order = settings.order - key,
    )
