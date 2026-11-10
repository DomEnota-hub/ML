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
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
        HistoricalRecoverySignals.requireIdle()
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
        HistoricalRecoverySignals.requireIdle()
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

    /** Removes only the saved file selected by the caller; no private trash copy is made. */
    suspend fun deleteDownloadPermanently(savedUri: String): Boolean = withContext(Dispatchers.IO) {
        HistoricalRecoverySignals.requireIdle()
        val uri = Uri.parse(savedUri)
        when {
            uri.scheme == "file" -> {
                val file = File(uri.path ?: return@withContext false)
                file.isFile && file.delete()
            }
            uri.scheme != "content" -> false
            DocumentsContract.isDocumentUri(context, uri) -> DocumentsContract.deleteDocument(context.contentResolver, uri)
            uri.authority == "downloads" -> {
                val systemId = uri.lastPathSegment?.toLongOrNull() ?: return@withContext false
                val system = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                system.remove(systemId) > 0
            }
            else -> context.contentResolver.delete(uri, null, null) > 0
        }
    }

    fun hasTrashed(id: String): Boolean = File(trash, id).isFile

    fun trashedUri(id: String): String? = File(trash, id).takeIf(File::isFile)
        ?.let { Uri.fromFile(it).toString() }

    suspend fun renameDownload(savedUri: String, newName: String): String? = withContext(Dispatchers.IO) {
        HistoricalRecoverySignals.requireIdle()
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
        HistoricalRecoverySignals.requireIdle()
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
        HistoricalRecoverySignals.requireIdle()
        val file = File(trash, id)
        file.isFile && file.delete()
    }
    /** For example "Pictures/MediaLoader/". Original history URIs remain untouched. */
    var relativeDirectory: String
        get() = settings.relativeDirectory
        set(value) { settings.relativeDirectory = value }

    /** The public root depends on the media MIME. Android stores audio in Music/. */
    fun publicRootFor(mimeType: String): String =
        MediaStoragePolicy.root(MediaStoragePolicy.route(relativeDirectory, mimeType))

    val downloadsSubPath: String get() = relativeDirectory.substringAfter('/').trimEnd('/')

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

    /** Path relative to the chosen public root for the system DownloadManager. */
    fun downloadsRelativePath(fileName: String, mimeType: String = "image/jpeg"): String =
        MediaStoragePolicy.subPath(MediaStoragePolicy.route(relativeDirectory, mimeType), fileName)

    private fun publicFolder(path: String = relativeDirectory): File {
        val root = Environment.getExternalStoragePublicDirectory(MediaStoragePolicy.root(path))
        val sub = path.substringAfter('/').trim('/')
        return if (sub.isEmpty()) root else File(root, sub)
    }

    /** Names already present in the target folder (file system and MediaStore). */
    suspend fun existingNames(): Set<String> = withContext(Dispatchers.IO) {
        selectedFolder()?.let { folder ->
            if (!folder.canWrite()) throw IOException("Выбранная папка недоступна")
            return@withContext folder.listFiles().mapNotNull { it.name }.toSet()
        }
        val names = mutableSetOf<String>()
        val targets = listOf(
            relativeDirectory,
            MediaStoragePolicy.route(relativeDirectory, "audio/mpeg"),
            MediaStoragePolicy.route(relativeDirectory, "application/octet-stream"),
        ).distinct()
        for (path in targets) publicFolder(path).list()?.let(names::addAll)
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val collections = listOf(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            )
            for (collection in collections) for (path in targets) {
                // On Android 13+ a full-library permission is not required for
                // writing app-owned media, but querying other owners may be denied.
                try {
                    resolver.query(collection,
                        arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                        "${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
                        arrayOf(path), null,
                    )?.use { cursor ->
                        while (cursor.moveToNext()) cursor.getString(0)?.let(names::add)
                    }
                } catch (_: SecurityException) {
                    // MediaStore itself handles collisions on insert; do not
                    // request broad photo permissions merely to list names.
                }
            }
        }
        names
    }

    /** Copies a finished temp file into the download folder and returns the actual saved name. */
    suspend fun publish(source: File, fileName: String, mimeType: String): PublishedFile =
        withContext(Dispatchers.IO) {
            HistoricalRecoverySignals.requireIdle()
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
            val targetPath = MediaStoragePolicy.route(relativeDirectory, mimeType)
            if (Build.VERSION.SDK_INT >= 29) {
                val resolver = context.contentResolver
                val collection = collectionFor(mimeType)
                val rejectedNames = mutableSetOf<String>()
                repeat(MAX_MEDIASTORE_NAME_ATTEMPTS) {
                    val actualName = StorageNaming.availableName(fileName, initialNames + rejectedNames)
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, actualName)
                        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, targetPath)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    val uri = resolver.insert(collection, values)
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
                val folder = publicFolder(targetPath)
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

    /**
     * Safe replacement for legacy copy/delete migration. A MediaStore move of
     * the SAME app-owned row retains its ID and DATE_ADDED: no new gallery item
     * is created and no old file is removed. Historical file:// and document
     * URIs are deliberately skipped until they have a verified owner mapping.
     *
     * Returns false when the file cannot be proven safe to relocate.
     */
    internal suspend fun moveLegacyMediaStoreInPlace(
        savedUri: String, originalName: String, mimeType: String,
    ): Boolean = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < 29 || usesSelectedFolder) return@withContext false
        val uri = Uri.parse(savedUri)
        if (uri.scheme != "content" || uri.authority != MediaStore.AUTHORITY) {
            return@withContext false
        }
        val resolver = context.contentResolver
        data class Metadata(
            val name: String, val mime: String, val path: String,
            val owner: String?, val bytes: Long,
            val addedSeconds: Long, val modifiedSeconds: Long,
        )
        fun read(): Metadata? = resolver.query(uri, arrayOf(
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED,
        ), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            fun str(name: String) = cursor.getString(cursor.getColumnIndexOrThrow(name))
            fun lng(name: String) = cursor.getLong(cursor.getColumnIndexOrThrow(name))
            Metadata(str(MediaStore.MediaColumns.DISPLAY_NAME),
                str(MediaStore.MediaColumns.MIME_TYPE),
                str(MediaStore.MediaColumns.RELATIVE_PATH),
                str(MediaStore.MediaColumns.OWNER_PACKAGE_NAME),
                lng(MediaStore.MediaColumns.SIZE),
                lng(MediaStore.MediaColumns.DATE_ADDED),
                lng(MediaStore.MediaColumns.DATE_MODIFIED))
        }
        val before = try { read() } catch (_: Exception) { null }
            ?: return@withContext false
        val proposed = LegacyRelocationPolicy.targetPath(before.path, before.owner,
            context.packageName, before.name, originalName,
            before.mime, mimeType, relativeDirectory,
            targetNameTaken = originalName in existingNames())
            ?: return@withContext false
        if (before.bytes <= 0L || before.addedSeconds <= 0L ||
            before.modifiedSeconds <= 0L) return@withContext false

        suspend fun sha256(): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
            resolver.openInputStream(uri)?.use { stream ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = stream.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            } ?: throw IOException("MediaStore file cannot be read")
            return digest.digest()
        }
        val oldHash = try { sha256() } catch (_: Exception) {
            return@withContext false
        }
        val change = ContentValues().apply {
            put(MediaStore.MediaColumns.RELATIVE_PATH, proposed)
        }
        val updated = try { resolver.update(uri, change, null, null) }
            catch (_: Exception) { 0 }
        if (updated != 1) return@withContext false

        // Filesystem/storage changes can be interrupted. Never remove bytes.
        // Restore the original location if a provider did not retain metadata.
        val after = try { read() } catch (_: Exception) { null }
        val preserved = after?.path == proposed &&
            LegacyRelocationPolicy.unchangedMetadata(
                before.name, after.name, before.bytes, after.bytes,
                before.addedSeconds, after.addedSeconds,
                before.modifiedSeconds, after.modifiedSeconds) &&
            try { oldHash.contentEquals(sha256()) } catch (_: Exception) { false }
        if (!preserved) {
            val restore = ContentValues().apply {
                put(MediaStore.MediaColumns.RELATIVE_PATH, before.path)
            }
            val restored = try { resolver.update(uri, restore, null, null) == 1 &&
                read()?.path == before.path } catch (_: Exception) { false }
            if (!restored) throw IOException(
                "Migration stopped: MediaStore move could not be verified or reversed; " +
                    "original URI remains in history and no file was deleted.")
            return@withContext false
        }
        true
    }

    /**
     * Copy ONLY an explicitly approved legacy history URI into a new,
     * proper MediaStore Images/Video/Audio collection. SHA-256 is compared
     * after reopening the new file. The old source is NEVER removed here;
     * LegacyMediaMigration commits the exact-URI Room update first.
     *
     * No temporary multi-GB cache file is needed for large videos.
     */
    internal suspend fun publishVerifiedLegacyCopy(
        oldSavedUri: String, fileName: String, mimeType: String,
    ): PublishedFile = withContext(Dispatchers.IO) {
        require(Build.VERSION.SDK_INT >= 29)
        require(!usesSelectedFolder)
        val kind = MediaStoragePolicy.kind(mimeType)
        require(kind != MediaStoragePolicy.Kind.OTHER)
        val oldUri = Uri.parse(oldSavedUri)
        val resolver = context.contentResolver
        val relativePath = MediaStoragePolicy.route(relativeDirectory, mimeType)
        val existing = existingNames().toMutableSet()
        val collisions = mutableSetOf<String>()
        repeat(MAX_MEDIASTORE_NAME_ATTEMPTS) {
            currentCoroutineContext().ensureActive()
            val actual = StorageNaming.availableName(fileName, existing + collisions)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, actual)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val newUri = resolver.insert(collectionFor(mimeType), values)
                ?: throw IOException("Не удалось создать файл в новой медиатеке.")
            try {
                var copied = 0L
                val sourceHash = MessageDigest.getInstance("SHA-256")
                val source = resolver.openInputStream(oldUri)
                    ?: throw IOException("Исходный файл недоступен.")
                source.use { input ->
                    val output = resolver.openOutputStream(newUri, "w")
                        ?: throw IOException("Новый файл не открылся для записи.")
                    output.use { target ->
                        val bytes = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(bytes)
                            if (count < 0) break
                            target.write(bytes, 0, count)
                            sourceHash.update(bytes, 0, count)
                            copied += count
                        }
                        target.flush()
                    }
                }
                if (copied <= 0L) throw IOException("Исходный файл пустой.")
                val verifiedHash = MessageDigest.getInstance("SHA-256")
                var verifiedSize = 0L
                val verifiedStream = resolver.openInputStream(newUri)
                    ?: throw IOException("Невозможно проверить новый файл.")
                verifiedStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        verifiedHash.update(buffer, 0, read)
                        verifiedSize += read
                    }
                }
                if (copied != verifiedSize ||
                    !sourceHash.digest().contentEquals(verifiedHash.digest()))
                    throw IOException("Контрольная сумма перенесённого файла не совпала.")
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                if (resolver.update(newUri, values, null, null) != 1)
                    throw IOException("Не удалось опубликовать проверенную копию.")
                return@withContext PublishedFile(newUri, actual)
            } catch (error: Exception) {
                resolver.delete(newUri, null, null)
                if (error is IllegalStateException &&
                    error.message?.contains("Failed to build unique file",
                        ignoreCase = true) == true) {
                    collisions.add(actual)
                } else throw error
            }
        }
        throw IOException("Не удалось подобрать свободное имя в новой папке.")
    }

    private fun collectionFor(mimeType: String): Uri = when (MediaStoragePolicy.kind(mimeType)) {
        MediaStoragePolicy.Kind.IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        MediaStoragePolicy.Kind.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        MediaStoragePolicy.Kind.AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        MediaStoragePolicy.Kind.OTHER -> if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        } else {
            // Old Android releases use the filesystem-based publish path.
            throw IllegalStateException("MediaStore.Downloads requires Android 10 or newer")
        }
    }

    private companion object {
        const val MAX_MEDIASTORE_NAME_ATTEMPTS = 100
    }

    /** Rename an app-owned MediaStore entry from an older release; keep history unchanged on failure. */
    suspend fun renameOwnedDownload(savedUri: String, newName: String): Boolean = withContext(Dispatchers.IO) {
        HistoricalRecoverySignals.requireIdle()
        if (Build.VERSION.SDK_INT < 29) return@withContext false
        val uri = Uri.parse(savedUri)
        if (uri.scheme != "content" || uri.authority != MediaStore.AUTHORITY) return@withContext false
        runCatching {
            val location = context.contentResolver.query(uri,
                arrayOf(MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.MIME_TYPE),
                null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) to cursor.getString(1) else null
            } ?: return@runCatching false
            val folder = location.first
            val collection = if (folder.startsWith("Download/"))
                MediaStore.Downloads.EXTERNAL_CONTENT_URI
            else collectionFor(location.second ?: "application/octet-stream")
            val collision = context.contentResolver.query(collection,
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
