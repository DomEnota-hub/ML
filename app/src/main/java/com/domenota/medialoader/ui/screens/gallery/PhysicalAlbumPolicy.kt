package com.domenota.medialoader.ui.screens.gallery

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.data.library.LibraryMediaRef

/** No recursive directory deletion. Verify the live MediaStore bucket before a trash or move. */
internal object PhysicalAlbumPolicy {
    data class Snapshot(val path: String, val bucket: String, val name: String,
        val size: Long, val dateAdded: Long, val volume: String)

    fun relativePath(raw: String): String {
        val path = raw.trim().trimEnd('/')
        require(path.isNotBlank() && !path.startsWith("/") && !path.contains('\\') &&
            path.split('/').none { it.isBlank() || it == "." || it == ".." }) { "Неизвестный путь папки" }
        return "$path/"
    }

    fun removableSource(raw: String): Boolean = runCatching {
        val path = relativePath(raw).lowercase(java.util.Locale.ROOT)
        val protected = setOf("dcim/", "pictures/", "movies/", "download/", "downloads/",
            "android/", "dcim/camera/", "dcim/screenshots/", "pictures/screenshots/",
            "movies/screen recordings/", "dcim/screen recordings/")
        path !in protected && !path.startsWith("android/") &&
            !path.startsWith("dcim/camera/") && !path.startsWith("dcim/screenshots/") &&
            !path.startsWith("pictures/screenshots/")
    }.getOrDefault(false)

    fun fullAccess(context: Context): Boolean {
        fun has(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        return Build.VERSION.SDK_INT >= 30 && (if (Build.VERSION.SDK_INT >= 33)
            has(Manifest.permission.READ_MEDIA_IMAGES) && has(Manifest.permission.READ_MEDIA_VIDEO)
        else has(Manifest.permission.READ_EXTERNAL_STORAGE))
    }

    fun mediaUri(ref: LibraryMediaRef): Uri {
        val saved = ref.item.savedUri?.takeIf(GalleryAlbumMovePolicy::isPhoneMedia)
        val candidates = ref.aliases.filter { it.startsWith("uri:") }.map { it.removePrefix("uri:") }
            .filter(GalleryAlbumMovePolicy::isPhoneMedia).distinct()
        return Uri.parse(saved ?: candidates.singleOrNull() ?: error("Не удалось подтвердить адрес файла в телефоне"))
    }

    fun read(context: Context, uri: Uri): Snapshot {
        require(GalleryAlbumMovePolicy.isPhoneMedia(uri.toString())) { "Файл не в MediaStore" }
        return context.contentResolver.query(uri, arrayOf("relative_path", "bucket_id", "_display_name",
            "_size", "date_added", "volume_name", "is_trashed", "is_pending"), null, null, null)?.use { c ->
            check(c.moveToFirst()) { "Файл недоступен" }
            fun str(key: String): String = c.getString(c.getColumnIndexOrThrow(key)) ?: error("Неизвестные данные файла")
            fun num(key: String): Long = c.getLong(c.getColumnIndexOrThrow(key))
            check(num("is_trashed") == 0L && num("is_pending") == 0L) { "Файл уже недоступен" }
            Snapshot(relativePath(str("relative_path")), str("bucket_id"), str("_display_name"),
                num("_size"), num("date_added"), str("volume_name"))
        } ?: error("Файл недоступен")
    }

    fun verifySource(snapshot: Snapshot, bucket: String, row: DownloadEntity) {
        check(snapshot.bucket == bucket && removableSource(snapshot.path)) { "Папка изменилась или является системной" }
        check(row.sizeBytes == null || row.sizeBytes == snapshot.size) { "Размер файла изменился" }
    }

    fun verifyDestination(before: Snapshot, after: Snapshot, destination: String): Boolean =
        after.path == relativePath(destination) && after.volume == before.volume &&
            after.name == before.name && after.size == before.size && after.dateAdded == before.dateAdded

    fun collision(context: Context, source: Snapshot, destination: String): Boolean =
        context.contentResolver.query(MediaStore.Files.getContentUri(source.volume), arrayOf("_id"),
            "relative_path = ? AND _display_name = ?", arrayOf(destination,source.name), null)?.use {
            it.moveToFirst()
        } ?: error("Не удалось проверить совпадения имён")
}
