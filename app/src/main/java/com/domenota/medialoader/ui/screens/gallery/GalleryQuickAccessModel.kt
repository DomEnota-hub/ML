package com.domenota.medialoader.ui.screens.gallery

import com.domenota.medialoader.core.database.DownloadEntity
import java.util.Locale

/** The fixed shortcuts mirror phone Gallery categories; custom albums follow them. */
internal enum class GalleryQuickKind { ALL, CAMERA, SCREENSHOTS, VIDEO, FAVORITES, ALBUM }

internal data class GalleryQuickItem(
    val key: String,
    val title: String,
    val kind: GalleryQuickKind,
    val count: Int,
    val cover: DownloadEntity?,
)

private fun normalizedAlbumName(name: String): String = name.trim()
    .lowercase(Locale.ROOT).replace('ё', 'е')
    .replace(Regex("[_\\s-]+"), " ")

internal fun galleryStandardAlbumKind(album: GalleryAlbum): GalleryQuickKind? {
    // User-created app folders named "Camera" must not be mistaken for system albums.
    if (!album.key.startsWith("device:")) return null
    val name = normalizedAlbumName(album.name)
    return when {
        name in setOf("camera", "камера", "camera roll") -> GalleryQuickKind.CAMERA
        name in setOf("screenshots", "screenshot", "скриншоты", "снимки экрана",
            "снимки и записи экрана", "screen captures") -> GalleryQuickKind.SCREENSHOTS
        else -> null
    }
}

internal fun galleryStandardAlbums(albums: List<GalleryAlbum>): List<GalleryAlbum> =
    listOfNotNull(
        albums.firstOrNull { galleryStandardAlbumKind(it) == GalleryQuickKind.CAMERA },
        albums.firstOrNull { galleryStandardAlbumKind(it) == GalleryQuickKind.SCREENSHOTS },
    )

/** Only the handful of visible shortcut images are requested; no full-library thumbnail scan. */
internal fun galleryDefaultQuickItems(
    albums: List<GalleryAlbum>,
    visibleAll: List<DownloadEntity>,
    videos: List<DownloadEntity>,
    favorites: List<DownloadEntity>,
): List<GalleryQuickItem> {
    val standard = galleryStandardAlbums(albums)
    val camera = standard.firstOrNull { galleryStandardAlbumKind(it) == GalleryQuickKind.CAMERA }
    val screenshots = standard.firstOrNull { galleryStandardAlbumKind(it) == GalleryQuickKind.SCREENSHOTS }
    return buildList {
        add(GalleryQuickItem("all", "Все файлы", GalleryQuickKind.ALL, visibleAll.size,
            visibleAll.maxByOrNull { it.createdAtEpochMillis }))
        if (camera != null) add(GalleryQuickItem(camera.key, "Камера", GalleryQuickKind.CAMERA,
            camera.count, camera.cover))
        if (screenshots != null) add(GalleryQuickItem(screenshots.key, "Снимки экрана",
            GalleryQuickKind.SCREENSHOTS, screenshots.count, screenshots.cover))
        add(GalleryQuickItem("videos", "Видео", GalleryQuickKind.VIDEO, videos.size,
            videos.maxByOrNull { it.createdAtEpochMillis }))
        add(GalleryQuickItem("favorites", "Избранное", GalleryQuickKind.FAVORITES, favorites.size,
            favorites.maxByOrNull { it.createdAtEpochMillis }))
    }
}
