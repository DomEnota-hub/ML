package com.domenota.medialoader.data.storage

import android.content.ContentValues
import android.content.Context
import android.app.DownloadManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.IOException
import com.domenota.medialoader.core.storage.StorageNaming
import com.domenota.medialoader.core.storage.AudioThumbnailInvalidation
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

data class PublishedFile(val uri: Uri, val fileName: String)

/** All knowledge about where files are stored and how they are written lives here. */
class StorageManager(
    private val context: Context,
    private val settings: StorageSettings,
) {
    /** Called only after a successful MP3 rewrite; leaves the media file untouched. */
    fun invalidateAudioThumbnail(savedUri: String) {
        AudioThumbnailInvalidation.invalidate(context.filesDir, savedUri)
    }

    private val trash: File get() = File(context.filesDir, "trash")

    /** Copies an existing saved download into an app-private temporary file. */
    suspend fun copyDownloadToTemp(savedUri: String, suffix: String = ".mp3"): File =
        withContext(Dispatchers.IO) {
            val uri = Uri.parse(savedUri)
            val target = File.createTempFile("owned-download-", suffix, context.cacheDir)
            try {
                when (uri.scheme) {
                    "file" -> File(uri.path ?: throw IOException("Файл недоступен"))
                        .inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
                    "content" -> {
                        val input = context.contentResolver.openInputStream(uri)
                            ?: throw IOException("Файл недоступен")
                        input.use { stream -> target.outputStream().use { stream.copyTo(it) } }
                    }
                    else -> throw IOException("Файл недоступен")
                }
                target
            } catch (error: Exception) {
                target.delete()
                throw error
            }
        }

    /** Replaces bytes of an app-owned download while keeping the same Uri whenever possible. */
    suspend fun replaceDownload(savedUri: String, source: File): Boolean = withContext(Dispatchers.IO) {
        val uri = Uri.parse(savedUri)
        runCatching {
            when (uri.scheme) {
                "file" -> {
                    val target = File(uri.path ?: return@runCatching false)
                    source.copyTo(target, overwrite = true)
                    true
                }
                "content" -> {
                    val output = context.contentResolver.openOutputStream(uri, "w")
                        ?: return@runCatching false
                    output.use { out -> source.inputStream().use { it.copyTo(out) } }
                    true
                }
                else -> false
            }
        }.getOrDefault(false)
    }

    /** Copies an owned download into private trash before removing the public copy. */
    suspend fun moveToTrash(id: String, savedUri: String) = withContext(Dispatchers.IO) {
        val uri = Uri.parse(savedUri)
        if (!trash.exists() && !trash.mkdirs()) throw IOException("Не удалось создать корзину")
        val target = File(trash, id)
        val temporary = File(trash, "$id.tmp")
        try {
            val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Файл недоступен")
            input.use { stream -> temporary.outputStream().use { stream.copyTo(it) } }
            if (!temporary.renameTo(target)) throw IOException("Не удалось завершить перемещение")
            val removed = when {
                uri.scheme == "file" -> File(uri.path ?: "").delete()
                uri.authority == "downloads" || uri.authority == "com.android.providers.downloads.documents" -> {
                    val systemId = uri.lastPathSegment?.toLongOrNull()
                    val system = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    systemId != null && system.remove(systemId) > 0
                }
                uri.scheme == "content" -> context.contentResolver.delete(uri, null, null) > 0
                else -> false
            }
            if (!removed) throw IOException("Не удалось переместить файл в корзину")
        } catch (error: Exception) {
            temporary.delete()
            target.delete()
            throw error
        }
    }

    fun hasTrashed(id: String): Boolean = File(trash, id).isFile

    fun trashedUri(id: String): String? = File(trash, id).takeIf(File::isFile)
        ?.let { Uri.fromFile(it).toString() }

    suspend fun renameDownload(savedUri: String, newName: String): String? = withContext(Dispatchers.IO) {
        val uri = Uri.parse(savedUri)
        when {
            uri.scheme == "file" -> {
                val source = File(uri.path ?: return@withContext null)
                val target = File(source.parentFile, newName)
                if (!target.exists() && source.renameTo(target)) Uri.fromFile(target).toString() else null
            }
            uri.scheme == "content" && uri.authority == MediaStore.AUTHORITY ->
                if (renameOwnedDownload(savedUri, newName)) savedUri else null
            uri.scheme == "content" -> {
                val document = DocumentFile.fromSingleUri(context, uri) ?: return@withContext null
                if (document.renameTo(newName)) document.uri.toString() else null
            }
            else -> null
        }
    }

    suspend fun restoreFromTrash(id: String, name: String, mimeType: String): Pair<String, String> {
        val source = File(trash, id)
        if (!source.isFile) throw IOException("Файл в корзине не найден")
        val published = publish(source, name, mimeType)
        return published.fileName to published.uri.toString()
    }

    /**
     * Deletes one explicitly identified trash file. Never erase its database record
     * until this returns true. No directory-wide blind deletion.
     */
    suspend fun discardTrashed(id: String): Boolean = withContext(Dispatchers.IO) {
        val file = File(trash, id)
        file.isFile && file.delete()
    }
    /** For example "Download/MediaLoader/". */
    var relativeDirectory: String
        get() = settings.relativeDirectory
        set(value) { settings.relativeDirectory = value }

    /** Path below the public Downloads directory, without a trailing slash: "MediaLoader". */
    val downloadsSubPath: String get() = relativeDirectory.removePrefix("Download/").trimEnd('/')

    /** Text for Settings. */
    val displayPath: String get() = settings.treeUri?.let {
        "Выбранная папка: ${settings.treeName ?: "файлы"}"
    } ?: relativeDirectory.trimEnd('/')

    val usesSelectedFolder: Boolean get() = settings.treeUri != null

    fun selectDirectory(uri: Uri) {
        val folder = DocumentFile.fromTreeUri(context, uri)
            ?.takeIf { it.isDirectory && it.canWrite() }
            ?: throw IOException("Выбранная папка недоступна для записи")
        context.contentResolver.takePersistableUriPermission(uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        settings.treeName = folder.name ?: "файлы"
        settings.treeUri = uri.toString()
    }

    private fun selectedFolder(): DocumentFile? = settings.treeUri?.let {
        DocumentFile.fromTreeUri(context, Uri.parse(it))
    }

    /** Path handed to DownloadManager for a file name. */
    fun downloadsRelativePath(fileName: String): String =
        if (downloadsSubPath.isEmpty()) fileName else "$downloadsSubPath/$fileName"

    private fun publicFolder(): File {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return if (downloadsSubPath.isEmpty()) downloads else File(downloads, downloadsSubPath)
    }

    /** Names already present in the target folder (file system and MediaStore). */
    suspend fun existingNames(): Set<String> = withContext(Dispatchers.IO) {
        selectedFolder()?.let { folder ->
            if (!folder.canWrite()) throw IOException("Выбранная папка недоступна")
            return@withContext folder.listFiles().mapNotNull { it.name }.toSet()
        }
        val names = mutableSetOf<String>()
        publicFolder().list()?.let(names::addAll)
        if (Build.VERSION.SDK_INT >= 29) {
            context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
                arrayOf(relativeDirectory), null,
            )?.use { cursor -> while (cursor.moveToNext()) names.add(cursor.getString(0)) }
        }
        names
    }

    /** Copies a finished temp file into the download folder and returns the actual saved name. */
    suspend fun publish(source: File, fileName: String, mimeType: String): PublishedFile =
        withContext(Dispatchers.IO) {
            val initialNames = existingNames().toMutableSet()
            selectedFolder()?.let { folder ->
                if (!folder.canWrite()) throw IOException("Выбранная папка недоступна")
                val actualName = StorageNaming.availableName(fileName, initialNames)
                val document = folder.createFile(mimeType, actualName)
                    ?: throw IOException("Не удалось создать файл в выбранной папке")
                try {
                    val output = context.contentResolver.openOutputStream(document.uri, "w")
                        ?: throw IOException("Не удалось открыть выбранную папку")
                    output.use { out -> source.inputStream().use { it.copyTo(out) } }
                } catch (error: Exception) {
                    document.delete()
                    throw error
                }
                return@withContext PublishedFile(document.uri, actualName)
            }
            if (Build.VERSION.SDK_INT >= 29) {
                val resolver = context.contentResolver
                val rejectedNames = mutableSetOf<String>()
                repeat(MAX_MEDIASTORE_NAME_ATTEMPTS) {
                    val actualName = StorageNaming.availableName(fileName, initialNames + rejectedNames)
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, actualName)
                        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDirectory)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: throw IOException("Не удалось создать файл")
                    try {
                        val output = resolver.openOutputStream(uri)
                            ?: throw IOException("Не удалось открыть файл для записи")
                        output.use { out -> source.inputStream().use { it.copyTo(out) } }
                        values.clear()
                        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                        resolver.update(uri, values, null, null)
                        return@withContext PublishedFile(uri, actualName)
                    } catch (error: Exception) {
                        resolver.delete(uri, null, null)
                        val nameCollision = error is IllegalStateException &&
                            error.message?.contains("Failed to build unique file", ignoreCase = true) == true
                        if (!nameCollision) throw error
                        rejectedNames += actualName
                    }
                }
                throw IOException("Не удалось подобрать свободное имя файла")
            } else {
                val folder = publicFolder()
                if (!folder.exists() && !folder.mkdirs()) throw IOException("Не удалось создать папку")
                val actualName = StorageNaming.availableName(fileName, initialNames)
                val target = File(folder, actualName)
                source.copyTo(target, overwrite = false)
                val uri = suspendCancellableCoroutine { cont ->
                    MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(mimeType)) { _, uri ->
                        cont.resume(uri ?: Uri.fromFile(target))
                    }
                }
                PublishedFile(uri, actualName)
            }
        }

    private companion object {
        const val MAX_MEDIASTORE_NAME_ATTEMPTS = 100
    }

    /** Rename an app-owned MediaStore entry from an older release; keep history unchanged on failure. */
    suspend fun renameOwnedDownload(savedUri: String, newName: String): Boolean = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < 29) return@withContext false
        val uri = Uri.parse(savedUri)
        if (uri.scheme != "content" || uri.authority != MediaStore.AUTHORITY) return@withContext false
        runCatching {
            val folder = context.contentResolver.query(uri,
                arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: return@runCatching false
            val collision = context.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                arrayOf(folder, newName), null)?.use { it.moveToFirst() } ?: true
            if (collision) return@runCatching false
            val values = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, newName) }
            if (context.contentResolver.update(uri, values, null, null) < 1) return@runCatching false
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                null, null, null)?.use { cursor ->
                cursor.moveToFirst() && cursor.getString(0) == newName
            } == true
        }.getOrDefault(false)
    }
}
