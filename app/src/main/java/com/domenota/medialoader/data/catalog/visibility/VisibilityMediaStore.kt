package com.domenota.medialoader.data.catalog.visibility

import android.Manifest
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Exact MediaStore half of an ordinary-folder move. Only typed, indexed media is
 * accepted here; legacy file and SAF aliases must first be resolved explicitly.
 * All methods run under the caller's durable move plan and operation gate. */
@RequiresApi(29)
internal class VisibilityMediaStore(context: Context) {
    private val context = context.applicationContext
    private val resolver = this.context.contentResolver

    private data class Row(val uri: Uri, val volume: String, val path: String, val name: String,
        val mime: String, val data: String, val size: Long, val modified: Long, val added: Long,
        val taken: Long?, val owner: String?, val pending: Boolean, val trashed: Boolean)

    private fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    private fun requireRead(mime: String, original: Boolean = false) {
        val kind = mime.substringBefore('/')
        val permission = if (Build.VERSION.SDK_INT < 33) Manifest.permission.READ_EXTERNAL_STORAGE else when (kind) {
            "image" -> Manifest.permission.READ_MEDIA_IMAGES
            "video" -> Manifest.permission.READ_MEDIA_VIDEO
            "audio" -> Manifest.permission.READ_MEDIA_AUDIO
            else -> error("Тип файла не поддерживается")
        }
        check(granted(permission)) { "Требуется полный доступ к соответствующим медиафайлам" }
        if (original && kind != "audio") check(granted(Manifest.permission.ACCESS_MEDIA_LOCATION)) {
            "Требуется доступ к исходным метаданным фотографии или видео"
        }
    }
    private fun requireMounted(volume: String) {
        check(volume in MediaStore.getExternalVolumeNames(context)) { "Исходный накопитель недоступен" }
    }
    private fun collection(volume: String, mime: String): Uri = when (mime.substringBefore('/')) {
        "image" -> MediaStore.Images.Media.getContentUri(volume)
        "video" -> MediaStore.Video.Media.getContentUri(volume)
        "audio" -> MediaStore.Audio.Media.getContentUri(volume)
        else -> error("Тип файла не поддерживается")
    }
    private fun typed(uri: Uri, mime: String) {
        val kind = when (mime.substringBefore('/')) { "image" -> "images"; "video" -> "video"; "audio" -> "audio"; else -> error("Тип файла не поддерживается") }
        check(uri.scheme == "content" && uri.authority == MediaStore.AUTHORITY && uri.query == null && uri.fragment == null &&
            uri.pathSegments.size == 4 && uri.pathSegments[1] == kind && uri.pathSegments[2] == "media" &&
            (uri.lastPathSegment?.toLongOrNull() ?: 0L) > 0L) { "Требуется точный URI MediaStore" }
    }
    private fun projection(): Array<String> = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.VOLUME_NAME,
        MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE,
        MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED,
        MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
        MediaStore.MediaColumns.IS_PENDING) + if (Build.VERSION.SDK_INT >= 30) arrayOf(MediaStore.MediaColumns.IS_TRASHED) else emptyArray()

    @Suppress("DEPRECATION")
    private fun query(uri: Uri, columns: Array<String>, selection: String? = null, args: Array<String>? = null): Cursor {
        val cursor = if (Build.VERSION.SDK_INT >= 30) resolver.query(uri, columns, Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
            if (selection != null) { putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection); putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,args) }
        },null) else resolver.query(MediaStore.setIncludePending(uri),columns,selection,args,null)
        return checkNotNull(cursor) { "MediaStore не подтвердил состояние записи" }
    }
    private fun Cursor.row(uri: Uri): Row {
        fun str(key: String): String = getString(getColumnIndexOrThrow(key)) ?: error("Нет поля MediaStore: $key")
        fun lng(key: String) = getLong(getColumnIndexOrThrow(key))
        return Row(uri, str(MediaStore.MediaColumns.VOLUME_NAME), str(MediaStore.MediaColumns.RELATIVE_PATH),
            str(MediaStore.MediaColumns.DISPLAY_NAME), str(MediaStore.MediaColumns.MIME_TYPE), str(MediaStore.MediaColumns.DATA),
            lng(MediaStore.MediaColumns.SIZE), lng(MediaStore.MediaColumns.DATE_MODIFIED), lng(MediaStore.MediaColumns.DATE_ADDED),
            lng(MediaStore.MediaColumns.DATE_TAKEN).takeIf { it > 0 }, getString(getColumnIndexOrThrow(MediaStore.MediaColumns.OWNER_PACKAGE_NAME)),
            lng(MediaStore.MediaColumns.IS_PENDING) != 0L,
            Build.VERSION.SDK_INT >= 30 && lng(MediaStore.MediaColumns.IS_TRASHED) != 0L)
    }
    private fun read(uri: Uri): Row? = query(uri,projection()).use { c ->
        check(c.count <= 1); if (c.moveToFirst()) c.row(uri) else null
    }
    private fun matching(origin: VisibilityPublicOrigin, name: String): List<Row> {
        val collection = collection(origin.volume,origin.mime)
        return query(collection,projection(),"${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?", arrayOf(origin.relativePath,name)).use { c ->
            buildList { while (c.moveToNext()) add(c.row(ContentUris.withAppendedId(collection,c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))))) }
        }
    }
    suspend fun capture(uri: String, expectedMime: String): VisibilityPublicOrigin = withContext(Dispatchers.IO) {
        val parsed = Uri.parse(uri); typed(parsed,expectedMime); requireRead(expectedMime,original=true)
        val row = checkNotNull(read(parsed)) { "Файл отсутствует в MediaStore" }
        requireMounted(row.volume)
        check(!row.pending && !row.trashed && row.mime.substringBefore('/') == expectedMime.substringBefore('/'))
        VisibilityPublicOrigin(uri,row.volume,row.path,row.name,row.mime,row.data,row.size,row.modified,row.added,row.taken,row.owner)
    }
    /** Canonicalize only a proven exact file. No lookup by display name, size or
     * guessed MediaStore ID. A SAF/file alias must resolve to the same physical stat. */
    suspend fun captureCatalog(locator: String, mime: String): VisibilityPublicOrigin = withContext(Dispatchers.IO) {
        requireRead(mime,original=true)
        val parsed = Uri.parse(locator)
        val typedLocator = runCatching { typed(parsed,mime) }.isSuccess
        if (typedLocator) {
            val row = checkNotNull(read(parsed)) { "Исходный файл отсутствует" }
            val exact = ContentUris.withAppendedId(collection(row.volume,mime),requireNotNull(parsed.lastPathSegment).toLong())
            val origin = capture(exact.toString(),mime)
            check(origin.physicalPath == row.data && origin.name == row.name && origin.bytes == row.size && origin.modifiedSeconds == row.modified)
            return@withContext origin
        }
        val evidence = com.domenota.medialoader.data.playback.PublicMediaIdentity.capture(context,locator)
        val paths = evidence.filter { it.startsWith("file:") }.flatMap {
            val path = it.removePrefix("file:")
            // MediaStore's DATA path is normally canonical, while the catalog
            // may retain /sdcard or /storage/self/primary. This only discovers
            // candidates: the captured physical stat must still match below.
            listOfNotNull(path, runCatching { File(path).canonicalPath }.getOrNull())
        }.distinct()
        val physical = evidence.filter { it.startsWith("file-stat:") }.toSet()
        check(physical.isNotEmpty()) { "Не удалось подтвердить физический адрес файла" }
        val candidates = mutableSetOf<String>()
        for (volume in MediaStore.getExternalVolumeNames(context)) for (path in paths) {
            val root = collection(volume,mime)
            query(root,arrayOf(MediaStore.MediaColumns._ID),"${MediaStore.MediaColumns.DATA}=?",arrayOf(path)).use { cursor ->
                while (cursor.moveToNext()) candidates += ContentUris.withAppendedId(root,cursor.getLong(0)).toString()
            }
        }
        if (candidates.isEmpty() && parsed.scheme == "content") {
            val converted = runCatching { MediaStore.getMediaUri(context,parsed) }.getOrNull()
            if (converted != null && runCatching { typed(converted,mime) }.isSuccess) {
                val row = checkNotNull(read(converted))
                candidates += ContentUris.withAppendedId(collection(row.volume,mime),requireNotNull(converted.lastPathSegment).toLong()).toString()
            }
        }
        val origin = capture(candidates.singleOrNull() ?: error("Неоднозначный адрес файла в MediaStore"),mime)
        val target = com.domenota.medialoader.data.playback.PublicMediaIdentity.capture(context,origin.uri)
        check(target.any(physical::contains)) { "Адрес MediaStore относится к другому файлу" }
        origin
    }

    fun openOriginal(origin: VisibilityPublicOrigin): InputStream {
        requireRead(origin.mime,original=true); requireMounted(origin.volume)
        val uri = Uri.parse(origin.uri); typed(uri,origin.mime)
        val row = checkNotNull(read(uri))
        check(!row.pending && !row.trashed && row.volume == origin.volume && row.path == origin.relativePath &&
            row.name == origin.name && row.mime == origin.mime && row.data == origin.physicalPath && row.size == origin.bytes && row.modified == origin.modifiedSeconds)
        val readable = if (origin.mime.startsWith("audio/")) uri else MediaStore.setRequireOriginal(uri)
        return checkNotNull(resolver.openInputStream(readable)) { "Не удалось прочитать исходные байты" }
    }
    suspend fun verifyOriginal(origin: VisibilityPublicOrigin, fingerprint: VisibilityFingerprint) = withContext(Dispatchers.IO) {
        check(HiddenFolderFileStore.fingerprint(openOriginal(origin)) == fingerprint) { "Исходный файл изменился" }
    }

    /** CreateDeleteRequest performs deletion itself. Never invoke it during preflight;
     * only after verified copy and catalog CAS, using the exact journaled URI. */
    @RequiresApi(30)
    suspend fun cleanupRequest(origin: VisibilityPublicOrigin, fingerprint: VisibilityFingerprint): PendingIntent = withContext(Dispatchers.IO) {
        verifyOriginal(origin,fingerprint)
        MediaStore.createDeleteRequest(resolver,listOf(Uri.parse(origin.uri)))
    }
    suspend fun removeOwnedOriginal(origin: VisibilityPublicOrigin, fingerprint: VisibilityFingerprint) = withContext(Dispatchers.IO) {
        verifyOriginal(origin,fingerprint)
        check(read(Uri.parse(origin.uri))?.owner == context.packageName) { "Для удаления требуется системное подтверждение" }
        check(resolver.delete(Uri.parse(origin.uri),null,null) == 1) { "Android не подтвердил удаление исходника" }
    }
    suspend fun originalPresence(origin: VisibilityPublicOrigin): VisibilityPresence = withContext(Dispatchers.IO) {
        try {
            requireRead(origin.mime); requireMounted(origin.volume)
            val uri = Uri.parse(origin.uri); typed(uri,origin.mime)
            val row = read(uri)
            when {
                row != null && row.volume == origin.volume && row.data == origin.physicalPath -> VisibilityPresence.PRESENT
                row != null -> VisibilityPresence.UNKNOWN
                matching(origin,origin.name).any { it.data == origin.physicalPath } -> VisibilityPresence.UNKNOWN
                Files.notExists(File(origin.physicalPath).toPath()) && File(origin.physicalPath).parentFile?.canRead() == true -> VisibilityPresence.MISSING
                else -> VisibilityPresence.UNKNOWN
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { VisibilityPresence.UNKNOWN }
    }

    suspend fun isOwnedOriginal(origin: VisibilityPublicOrigin): Boolean = withContext(Dispatchers.IO) {
        requireRead(origin.mime); requireMounted(origin.volume)
        read(Uri.parse(origin.uri))?.owner == context.packageName
    }

    suspend fun restorePresence(origin: VisibilityPublicOrigin, token: String, destination: String): VisibilityPresence = withContext(Dispatchers.IO) {
        try {
            val uri = Uri.parse(destination); typed(uri,origin.mime); requireRead(origin.mime); requireMounted(origin.volume)
            if (read(uri) == null) VisibilityPresence.MISSING
            else { reserved(origin,token,uri,allowPublished=true); VisibilityPresence.PRESENT }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { VisibilityPresence.UNKNOWN }
    }
    suspend fun restoreFingerprint(origin: VisibilityPublicOrigin, token: String, destination: String): VisibilityFingerprint = withContext(Dispatchers.IO) {
        val uri = Uri.parse(destination); val row = reserved(origin,token,uri,allowPublished=true)
        val readable = if (!row.pending && !origin.mime.startsWith("audio/")) MediaStore.setRequireOriginal(uri) else uri
        HiddenFolderFileStore.fingerprint(checkNotNull(resolver.openInputStream(readable)))
    }

    /** A Files row beneath .nomedia is not necessarily a gallery-visible file.
     * Permit only non-media Files classification, then independently verify that
     * no published image, video or audio URI exists at the exact destination.
     * Never delete indexed rows, which could also delete the verified copy.
     */
    @RequiresApi(30)
    suspend fun verifyHiddenNotIndexed(file: File, mime: String) = withContext(Dispatchers.IO) {
        requireRead(mime); requireMounted(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val exactPath = arrayOf(file.absolutePath)
        val selection = "${MediaStore.MediaColumns.DATA}=?"
        val files = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val columns = arrayOf(MediaStore.MediaColumns._ID, MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.IS_TRASHED)
        var rowCount = 0
        query(files, columns, selection, exactPath).use { cursor ->
            while (cursor.moveToNext()) {
                rowCount++
                check(rowCount == 1) { "Несколько записей скрытого файла в MediaStore" }
                val kind = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE))
                check(kind == MediaStore.Files.FileColumns.MEDIA_TYPE_NONE) {
                    "Системная медиатека всё ещё распознаёт скрытый файл как медиа"
                }
            }
        }
        val publicArgs = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, exactPath)
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_EXCLUDE)
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_EXCLUDE)
        }
        val galleries = listOf(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY))
        for (uri in galleries) {
            val cursor = checkNotNull(resolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), publicArgs, null)) {
                "Системная медиатека не ответила на проверку скрытого файла"
            }
            cursor.use {
                check(it.count == 0) { "Скрытый файл всё ещё доступен в публичной медиатеке" }
            }
        }
    }

    /** Reserve with an operation-specific name, so insert-before-journal recovery
     * never finds a row by the user's filename. Only an exact owned pending row is reusable. */
    suspend fun allocateRestore(origin: VisibilityPublicOrigin, token: String): String = withContext(Dispatchers.IO) {
        requireRead(origin.mime); requireMounted(origin.volume)
        val temporary = origin.reservationName(token)
        check(matching(origin,origin.name).isEmpty()) { "Исходное имя уже занято" }
        val found = matching(origin,temporary)
        check(found.size <= 1)
        if (found.isNotEmpty()) {
            val row = found.single(); check(row.pending && !row.trashed && row.owner == context.packageName && row.mime == origin.mime && row.path == origin.relativePath)
            return@withContext row.uri.toString()
        }
        val uri = checkNotNull(resolver.insert(collection(origin.volume,origin.mime),ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME,temporary); put(MediaStore.MediaColumns.MIME_TYPE,origin.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH,origin.relativePath); put(MediaStore.MediaColumns.IS_PENDING,1)
        })) { "Android не создал место для восстановления" }
        reserved(origin,token,uri,allowPublished=false)
        uri.toString()
    }
    private fun reserved(origin: VisibilityPublicOrigin, token: String, uri: Uri, allowPublished: Boolean): Row {
        typed(uri,origin.mime); requireMounted(origin.volume); requireRead(origin.mime)
        val row = checkNotNull(read(uri))
        check(row.volume == origin.volume && row.path == origin.relativePath && row.mime == origin.mime &&
            row.owner == context.packageName && !row.trashed)
        val validName = if (row.pending) row.name == origin.reservationName(token) || allowPublished && row.name == origin.name
            else allowPublished && row.name == origin.name
        check(validName && (row.pending || allowPublished)) { "Назначение восстановления изменилось" }
        return row
    }
    suspend fun restoreBytes(origin: VisibilityPublicOrigin, token: String, destination: String,
        expected: VisibilityFingerprint, open: () -> InputStream) = withContext(Dispatchers.IO) {
        val uri = Uri.parse(destination); val row = reserved(origin,token,uri,allowPublished=true)
        // A crash after rename/publication but before the move journal advanced
        // must never truncate the already published verified destination.
        if (!row.pending || row.name == origin.name) {
            val readable = if (!row.pending && !origin.mime.startsWith("audio/")) MediaStore.setRequireOriginal(uri) else uri
            check(HiddenFolderFileStore.fingerprint(checkNotNull(resolver.openInputStream(readable))) == expected)
            return@withContext
        }
        checkNotNull(resolver.openFileDescriptor(uri,"rwt")).use { fd ->
            open().use { input -> FileOutputStream(fd.fileDescriptor).use { output ->
                VisibilityVerifiedCopy.writeAndSync(input,output,expected)
            } }
        }
        check(HiddenFolderFileStore.fingerprint(checkNotNull(resolver.openInputStream(uri))) == expected)
    }
    suspend fun publishRestore(origin: VisibilityPublicOrigin, token: String, destination: String,
        expected: VisibilityFingerprint) = withContext(Dispatchers.IO) {
        val uri = Uri.parse(destination); val before = reserved(origin,token,uri,allowPublished=true)
        requireRead(origin.mime,original=true)
        val readable = if (!before.pending && !origin.mime.startsWith("audio/")) MediaStore.setRequireOriginal(uri) else uri
        check(HiddenFolderFileStore.fingerprint(checkNotNull(resolver.openInputStream(readable))) == expected)
        if (before.pending) {
            check(matching(origin,origin.name).all { it.uri == uri }) { "Исходное имя уже занято" }
            if (before.name != origin.name) check(resolver.update(uri,ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME,origin.name)
            },null,null) == 1)
            val pending = reserved(origin,token,uri,allowPublished=true); check(pending.pending && pending.name == origin.name)
            check(resolver.update(uri,ContentValues().apply {
                if (origin.takenMillis != null && !origin.mime.startsWith("audio/")) put(MediaStore.MediaColumns.DATE_TAKEN,origin.takenMillis)
                put(MediaStore.MediaColumns.IS_PENDING,0)
            },null,null) == 1)
        }
        val row = reserved(origin,token,uri,allowPublished=true); check(!row.pending && row.name == origin.name && matching(origin,origin.name).map { it.uri } == listOf(uri))
        val file = File(row.data).canonicalFile
        check(file.path == File(origin.physicalPath).canonicalPath && file.isFile && file.length() == expected.bytes)
        if (file.lastModified() != origin.modifiedMillis) check(file.setLastModified(origin.modifiedMillis))
        check(file.lastModified() == origin.modifiedMillis)
        fun observed(current: Row): VisibilityRestoreMetadataStatus =
            VisibilityRestoreMetadataPolicy.observe(
                expected.bytes,origin.modifiedSeconds,origin.takenMillis,
                current.size,current.modified,current.taken)
        // Android can round DATE_TAKEN to seconds. Never confuse a harmless
        // <=1s provider rounding with changed content; all byte/owner/path
        // proofs above and in FolderVisibilityMove still apply.
        if (observed(row).confirmed) return@withContext
        MediaScannerConnection.scanFile(context,arrayOf(file.path),arrayOf(origin.mime),null)
        var lastStatus = observed(row)
        repeat(12) { attempt ->
            val scanned = reserved(origin,token,uri,allowPublished=true)
            lastStatus = observed(scanned)
            if (!scanned.pending && scanned.name == origin.name && lastStatus.confirmed) {
                val original = if (origin.mime.startsWith("audio/")) uri else MediaStore.setRequireOriginal(uri)
                check(HiddenFolderFileStore.fingerprint(checkNotNull(resolver.openInputStream(original))) == expected)
                return@withContext
            }
            // Some Android providers accept a file timestamp on disk but leave
            // their indexed DATE_MODIFIED/DATE_TAKEN stale indefinitely. Repair
            // only the EXACT owned journal destination after verifying its path,
            // size, physical mtime and SHA. Never alter the old hidden source.
            if (attempt in setOf(0,4,8) && (!lastStatus.modifiedConfirmed || !lastStatus.takenConfirmed)) {
                check(file.canonicalPath == File(origin.physicalPath).canonicalPath &&
                    file.isFile && file.length() == expected.bytes && file.lastModified() == origin.modifiedMillis)
                val corrections = ContentValues().apply {
                    if (!lastStatus.modifiedConfirmed)
                        put(MediaStore.MediaColumns.DATE_MODIFIED,origin.modifiedSeconds)
                    if (!lastStatus.takenConfirmed && origin.takenMillis != null &&
                        !origin.mime.startsWith("audio/"))
                        put(MediaStore.MediaColumns.DATE_TAKEN,origin.takenMillis)
                }
                if (corrections.size() > 0) {
                    val updated = resolver.update(uri,corrections,null,null)
                    check(updated in 0..1) { "MediaStore вернул неоднозначный результат исправления даты" }
                }
            }
            if (attempt != 11) delay(400)
        }
        // Fail closed and retain the old SHA-verified hidden source + journals.
        // A published public file may coexist with it; do not try another
        // allocation or silently discard the unconfirmed restored metadata.
        com.domenota.medialoader.core.logging.AppLog.w("FolderVisibility",
            "RESTORE_METADATA_PENDING code=${lastStatus.diagnosticCode} attempts=12 originalRetained=true")
        error("Дата или размер восстановленного файла не подтверждены: ${lastStatus.diagnosticCode}. Исходник и журнал сохранены")
    }
}
