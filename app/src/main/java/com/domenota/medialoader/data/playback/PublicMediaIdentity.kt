package com.domenota.medialoader.data.playback

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** References to a public file, never its contents. Capture before a private move deletes it. */
internal object PublicMediaIdentity {
    const val EXTRA_KEYS = "public_media_identity_keys"

    /** Cheap identities can also protect old/restored queues without reopening their sources. */
    fun uriKeys(value: String): Set<String> {
        val uri = Uri.parse(value)
        val keys = mutableSetOf("uri:${uri.normalizeScheme()}")
        if (uri.scheme == "file") uri.path?.let { keys += fileKey(it) }
        if (uri.scheme != "content") return keys
        val segments = uri.pathSegments
        if (uri.authority == "media" && segments.size >= 3 && segments.last().toLongOrNull() != null) {
            val collection = segments.drop(1).dropLast(1).joinToString("/")
            if (collection in setOf("audio/media", "video/media", "images/media", "downloads", "file")) {
                keys += mediaKey(segments.first(), segments.last())
            }
        }
        val documentIndex = segments.indexOf("document")
        val documentId = segments.getOrNull(documentIndex + 1).takeIf { documentIndex >= 0 }
        when (uri.authority) {
            "com.android.providers.media.documents" -> {
                val type = documentId?.substringBefore(':')
                val id = documentId?.substringAfter(':', "")
                if (type in setOf("audio", "video", "image") && id?.toLongOrNull() != null) keys += mediaKey("external", id)
            }
            "com.android.providers.downloads.documents" -> {
                if (documentId?.startsWith("msf:") == true && documentId.substringAfter(':').toLongOrNull() != null)
                    keys += mediaKey("external", documentId.substringAfter(':'))
                if (documentId?.startsWith("raw:") == true) keys += fileKey(documentId.removePrefix("raw:"))
            }
            "com.android.externalstorage.documents" -> {
                val volume = documentId?.substringBefore(':')
                val relative = documentId?.substringAfter(':', "")
                if (volume != null && relative != null) {
                    val root = if (volume.equals("primary", true)) Environment.getExternalStorageDirectory().path else "/storage/$volume"
                    keys += fileKey("$root/$relative")
                }
            }
        }
        return keys
    }

    /** MediaStore already supplies this path in the collection cursor. Stat metadata only;
     * do not open/canonicalize thousands of individual provider URIs during indexing. */
    internal fun pathKeys(path: String): Set<String> = buildSet {
        add(fileKey(path))
        try {
            val stat = Os.stat(path)
            if (OsConstants.S_ISREG(stat.st_mode) && stat.st_ino != 0L)
                add("file-stat:${stat.st_dev}:${stat.st_ino}:${stat.st_size}:${stat.st_mtime}")
        } catch (_: Exception) { /* Scoped storage may deny the raw path. */ }
    }

    suspend fun capture(context: Context, value: String): Set<String> = withContext(Dispatchers.IO) {
        val keys = uriKeys(value).toMutableSet()
        val uri = Uri.parse(value)
        // Resolve a provider's path before canonicalize/openFileDescriptor. The latter can
        // invoke cloud/transcoding providers and must not be the normal indexing path.
        val paths = keys.filter { it.startsWith("file:") }.map { it.removePrefix("file:") }.toMutableList()
        if (uri.authority == "media") try {
            context.contentResolver.query(uri, arrayOf("_data"), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getColumnIndex("_data").takeIf { it >= 0 && !cursor.isNull(it) }
                    ?.let(cursor::getString)?.takeIf { it.isNotBlank() }?.let(paths::add)
            }
        } catch (_: Exception) { /* Documents and remote providers may have no path. */ }
        paths.forEach { keys += pathKeys(it) }
        if (keys.any { it.startsWith("file-stat:") }) return@withContext keys
        try {
            context.contentResolver.canonicalize(uri)?.let { keys += uriKeys(it.toString()) }
        } catch (_: Exception) { /* Providers need not support canonicalization. */ }
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                val stat = Os.fstat(descriptor.fileDescriptor)
                // Pipe providers cannot establish physical file identity. Include size and mtime
                // to avoid treating an inode subsequently reused for a different file as an alias.
                if (OsConstants.S_ISREG(stat.st_mode) && stat.st_ino != 0L) {
                    keys += "file-stat:${stat.st_dev}:${stat.st_ino}:${stat.st_size}:${stat.st_mtime}"
                }
            }
        } catch (_: Exception) { /* URI identities still cover inaccessible/deleted sources. */ }
        keys
    }

    private fun fileKey(path: String) = "file:${File(path).toPath().normalize()}"
    private fun mediaKey(volume: String, id: String): String {
        val normalizedVolume = if (volume == "external_primary") "external" else volume
        return "media:$normalizedVolume:${id.toLong()}"
    }
}
