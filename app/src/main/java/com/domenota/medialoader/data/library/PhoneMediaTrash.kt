package com.domenota.medialoader.data.library

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import com.domenota.medialoader.ui.model.DownloadUiItem
import com.domenota.medialoader.ui.model.DownloadUiState
import com.domenota.medialoader.ui.model.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Read-only view of Android's MediaStore trash. Bytes stay with the system provider;
 * only the user's system confirmation can restore or permanently delete them.
 * The app's own downloaded files continue to use its existing private trash.
 */
internal object PhoneMediaTrash {
    const val ID_PREFIX = "phone-trash:"
    fun isPhoneTrashId(id: String): Boolean = id.startsWith(ID_PREFIX)

    suspend fun list(context: Context): List<DownloadUiItem> = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@withContext emptyList()
        val resolver = context.contentResolver
        val args = Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
        }
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_EXPIRES,
            MediaStore.MediaColumns.DATE_MODIFIED,
        )
        val sources = listOf(
            Triple("image", MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaKind.IMAGE),
            Triple("video", MediaStore.Video.Media.EXTERNAL_CONTENT_URI, MediaKind.VIDEO),
            // Music uses the same shared phone deletion host; do not orphan trashed audio.
            Triple("audio", MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, MediaKind.AUDIO),
        )
        sources.flatMap { (type, collection, kind) ->
            val result = mutableListOf<DownloadUiItem>()
            try {
                resolver.query(collection, projection, args, null)?.use { cursor ->
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val expiresColumn = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_EXPIRES)
                    val modifiedColumn = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                    val nameColumn = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    while (cursor.moveToNext()) {
                        val mediaId = cursor.getLong(idColumn)
                        val uri = ContentUris.withAppendedId(collection, mediaId)
                        val displayName = nameColumn.takeIf { it >= 0 && !cursor.isNull(it) }
                            ?.let(cursor::getString).orEmpty().ifBlank { "Медиа $mediaId" }
                        result += DownloadUiItem(
                            id = "$ID_PREFIX$type:$mediaId",
                            title = displayName,
                            subtitle = "Файл телефона · корзина Android",
                            sizeLabel = null,
                            state = DownloadUiState.COMPLETED,
                            savedUri = uri.toString(),
                            kind = kind,
                            providerId = "device",
                            trashOrderMs = (expiresColumn.takeIf { it >= 0 && !cursor.isNull(it) }
                                ?: modifiedColumn.takeIf { it >= 0 && !cursor.isNull(it) })?.let { cursor.getLong(it) * 1000L } ?: 0L,
                        )
                    }
                }
            } catch (_: SecurityException) {
                // Missing photos/videos permission must not hide the app's own trash.
            } catch (_: IllegalArgumentException) {
                // Some third-party media providers do not implement MATCH_ONLY.
            }
            result
        }.sortedByDescending { it.trashOrderMs }
    }

    fun authorizedUris(ids: Set<String>, items: List<DownloadUiItem>): List<Uri> =
        items.asSequence().filter { it.id in ids && isPhoneTrashId(it.id) }
            .mapNotNull { it.savedUri?.let(Uri::parse) }
            .filter { it.scheme == "content" && it.authority == "media" }
            .distinct().toList()
}
