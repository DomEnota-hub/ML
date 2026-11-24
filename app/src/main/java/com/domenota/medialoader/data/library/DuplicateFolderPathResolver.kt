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
                        val volume = value("volume_name")
                        val relative = value("relative_path")
                        val bucket = value("bucket_id")
                        if (bucket != row.bulkMediaKey) null else verifiedDirectory(volume, relative)
                    }
                }.getOrNull()
                if (resolved != null) result[key] = resolved
            }
            result
        }

    /** MediaStore RELATIVE_PATH normally ends in exactly one slash. Reject
     * absolute paths, repeated separators, traversal and untrusted volumes
     * before showing a navigable directory. Never repair a malformed path.
     */
    internal fun verifiedDirectory(volume: String?, relativePath: String?): String? {
        if (volume.isNullOrBlank() || relativePath.isNullOrBlank() ||
            volume.any { !it.isLetterOrDigit() && it != '_' && it != '-' && it != '.' }) return null
        if (relativePath.startsWith('/') || relativePath.startsWith('\\')) return null
        val path = relativePath.removeSuffix("/")
        if (path.isBlank() || path.endsWith('/')) return null
        if (path.split('/').any { it.isBlank() || it == "." || it == ".." ||
                '\\' in it || '|' in it || '\u0000' in it }) return null
        return "volume:$volume|$path"
    }

    fun apply(
        folders: List<DuplicateFolderOption>,
        directories: Map<String, String>,
    ): List<DuplicateFolderOption> = folders.map { folder ->
        val resolved = directories[folder.key]
        val separator = resolved?.indexOf('|') ?: -1
        val namespace = if (separator > 0) resolved!!.substring(0, separator) else ""
        val rawPath = if (separator > 0) resolved!!.substring(separator + 1) else ""
        val verified = if (namespace.startsWith("volume:"))
            verifiedDirectory(namespace.removePrefix("volume:"), rawPath) else null
        val trusted = folder.key.startsWith("bucket:") && folder.directoryId == null &&
            verified != null && verified == resolved
        if (!trusted) folder else folder.copy(
            name = rawPath.substringAfterLast('/'), location = rawPath, directoryId = resolved)
    }
}
