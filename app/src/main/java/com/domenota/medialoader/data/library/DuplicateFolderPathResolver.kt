package com.domenota.medialoader.data.library

import android.content.Context
import android.net.Uri
import android.os.Build
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** The picker resolves one representative MediaStore row per visual bucket.
 * Paths are read-only metadata. Never infer parents from display names, UUIDs or IDs.
 * A failed query leaves the bucket ungrouped, not broadened to the whole library.
 */
internal object DuplicateFolderPathResolver {
    suspend fun resolve(context: Context, rows: List<DownloadEntity>): Map<String, String> =
        withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT < 29) return@withContext emptyMap()
            val representatives = rows.asSequence().filter(DuplicateFolderScope::eligible)
                .filter { it.mediaType != MediaType.AUDIO && DuplicateFolderScope.key(it).startsWith("bucket:") }
                .distinctBy(DuplicateFolderScope::key).toList()
            val result = linkedMapOf<String, String>()
            val projection = arrayOf("relative_path", "volume_name", "bucket_id")
            representatives.forEach { row ->
                currentCoroutineContext().ensureActive()
                val key = DuplicateFolderScope.key(row)
                val mediaUri = row.savedUri?.let(Uri::parse) ?: return@forEach
                if (mediaUri.scheme != "content" || mediaUri.authority != "media") return@forEach
                val resolved = runCatching {
                    context.contentResolver.query(mediaUri, projection, null, null, null)?.use { cursor ->
                        if (!cursor.moveToFirst() || cursor.moveToNext()) return@use null
                        if (!cursor.moveToFirst()) return@use null
                        fun value(field: String): String? {
                            val index = cursor.getColumnIndex(field)
                            return if (index < 0 || cursor.isNull(index)) null else cursor.getString(index)?.trim()
                        }
                        val volume = value("volume_name")?.takeIf { it.isNotBlank() }
                        val relative = value("relative_path")?.trim('/')?.takeIf { it.isNotBlank() }
                        val bucket = value("bucket_id")
                        if (volume == null || relative == null || bucket != row.bulkMediaKey) null
                        else if (relative.split('/').any { it == "." || it == ".." || it.isBlank() || it.contains('\\') }) null
                        else "volume:$volume|$relative"
                    }
                }.getOrNull()
                if (resolved != null) result[key] = resolved
            }
            result
        }

    fun apply(
        folders: List<DuplicateFolderOption>,
        directories: Map<String, String>,
    ): List<DuplicateFolderOption> = folders.map { folder ->
        val resolved = directories[folder.key]
        val separator = resolved?.indexOf('|') ?: -1
        val relative = if (separator > 0) resolved!!.substring(separator + 1).trim('/') else ""
        if (relative.isBlank() || relative.split('/').any { it == "." || it == ".." }) folder
        else folder.copy(name = relative.substringAfterLast('/'),
            location = relative, directoryId = resolved)
    }
}
