package com.domenota.medialoader.data.catalog.visibility

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.os.Bundle
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Android-specific port for a precise existing MediaStore record.
 *
 * No MediaStore INSERT/DELETE, no direct relocation via Files.move(), no
 * replacement of a missing _ID, no trash/pending flags. The only media
 * mutation is UPDATE RELATIVE_PATH on the SAME verified _ID, after a durable
 * HIDE/RESTORE_INTENT has been saved by SelectiveArchiveMoveEngine.
 *
 * Not wired into the public UI before completed device QA and reference
 * integration. On an unknown provider state this port refuses mutation.
 */
internal class AndroidSelectiveArchiveMovePort(
    context:Context,
    private val referenceDigest:suspend (SelectiveArchiveIdentity)->String?,
    private val target:SelectiveArchiveLocalTarget=SelectiveArchiveLocalTarget(),
):SelectiveArchiveMovePort {
    private val app=context.applicationContext
    private val resolver=app.contentResolver

    private data class Row(
        val id:Long,val volume:String,val path:String,val name:String,val size:Long,
        val mime:String,val pending:Boolean,val trashed:Boolean,
    )

    private fun requireSupported(original:SelectiveArchiveIdentity,r:SelectiveArchiveMoveReceipt) {
        check(Build.VERSION.SDK_INT>=30 && FolderVisibilityStorageAccess.granted()) {
            "Выборочный архив требует Android 11+ и разрешение управления файлами"
        }
        check(original.wellFormed() &&
            original.volume==MediaStore.VOLUME_EXTERNAL_PRIMARY &&
            r.originalUri==original.originalUri && r.originalSha256==original.sha256 &&
            r.targetRelativePath==target.relative(original,r.token)) {
            "Исходная привязка или целевой каталог не подтверждены"
        }
        val uri=Uri.parse(original.originalUri)
        check(uri.scheme=="content" && uri.authority==MediaStore.AUTHORITY &&
            uri.query==null && uri.fragment==null && uri.pathSegments.size==4 &&
            uri.pathSegments[2]=="media" && uri.lastPathSegment?.toLongOrNull()==original.mediaStoreId &&
            uri.pathSegments[1] in setOf("images","video","audio")) {
            "Некорректный исходный типизированный URI"
        }
    }

    private fun fileUri(original:SelectiveArchiveIdentity):Uri =
        ContentUris.withAppendedId(MediaStore.Files.getContentUri(original.volume),original.mediaStoreId)

    private fun columns()=arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.VOLUME_NAME,
        MediaStore.MediaColumns.RELATIVE_PATH,
        MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.SIZE,
        MediaStore.MediaColumns.MIME_TYPE,
        MediaStore.MediaColumns.IS_PENDING,
        MediaStore.MediaColumns.IS_TRASHED,
    )

    private fun read(uri:Uri):Row? {
        val query=Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING,MediaStore.MATCH_INCLUDE)
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED,MediaStore.MATCH_INCLUDE)
        }
        val cursor=resolver.query(uri,columns(),query,null) ?: return null
        return cursor.use {
            if(!it.moveToFirst())null
            else {
                check(it.count==1) { "MediaStore вернул несколько записей одного ID" }
                check(!it.isNull(6) && !it.isNull(7)) { "Неизвестный статус MediaStore" }
                Row(it.getLong(0),it.getString(1) ?: "",it.getString(2) ?: "",
                    it.getString(3) ?: "",if(it.isNull(4))-1 else it.getLong(4),
                    it.getString(5) ?: "",it.getInt(6)!=0,it.getInt(7)!=0)
            }
        }
    }

    private fun publicIndexed(original:SelectiveArchiveIdentity):Boolean? {
        val uri=Uri.parse(original.originalUri)
        return try {
            resolver.query(uri,arrayOf(MediaStore.MediaColumns._ID),null,null,null)?.use {
                if(it.moveToFirst()) {
                    check(it.count==1 && it.getLong(0)==original.mediaStoreId)
                    true
                } else false
            }
        } catch(_:SecurityException) { null }
    }

    private suspend fun fingerprint(file:File):SelectiveArchivePayloadDigest {
        check(Files.isRegularFile(file.toPath(),NOFOLLOW_LINKS) &&
            !Files.isSymbolicLink(file.toPath()))
        val sha=MessageDigest.getInstance("SHA-256")
        var size=0L
        file.inputStream().use { input ->
            val buffer=ByteArray(64*1024)
            while(true) {
                currentCoroutineContext().ensureActive()
                val n=input.read(buffer)
                if(n<0)break
                if(n>0){sha.update(buffer,0,n);size=Math.addExact(size,n.toLong())}
            }
        }
        return SelectiveArchivePayloadDigest(size,
            sha.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
    }

    override suspend fun observe(original:SelectiveArchiveIdentity,
        receipt:SelectiveArchiveMoveReceipt):SelectiveArchiveMoveObservation =
        withContext(Dispatchers.IO) {
            requireSupported(original,receipt)
            fun unknown()=SelectiveArchiveMoveObservation(
                SelectiveArchiveLocation.UNKNOWN,null,null,null,null)
            val files=runCatching { read(fileUri(original)) }.getOrNull()
            val typed=runCatching { read(Uri.parse(original.originalUri)) }.getOrNull()
            val row=when {
                files!=null && typed!=null && files!=typed -> return@withContext unknown()
                files!=null -> files
                typed!=null -> typed
                else -> return@withContext unknown()
            }
            if(row.id!=original.mediaStoreId || row.volume!=original.volume ||
                row.name!=original.displayName || row.pending || row.trashed ||
                row.size<0 || row.path !in setOf(original.relativePath,receipt.targetRelativePath)) {
                return@withContext unknown()
            }
            val place=if(row.path==original.relativePath)
                SelectiveArchiveLocation.SOURCE else SelectiveArchiveLocation.HIDDEN
            val owned=if(place==SelectiveArchiveLocation.HIDDEN)
                target.inspect(original,receipt) else null
            if(place==SelectiveArchiveLocation.HIDDEN && owned!=true) return@withContext unknown()
            val file=runCatching { if(place==SelectiveArchiveLocation.SOURCE)
                target.sourceFile(original) else target.hiddenFile(original,receipt)
            }.getOrNull() ?: return@withContext unknown()
            // Compare file identity before/after streamed SHA, including exact
            // physical mtime, and requery the SAME MediaStore _ID.
            val length=Files.size(file.toPath())
            val modified=Files.getLastModifiedTime(file.toPath(),NOFOLLOW_LINKS).toMillis()
            val sha=fingerprint(file)
            val mtimeAfter=Files.getLastModifiedTime(file.toPath(),NOFOLLOW_LINKS).toMillis()
            val rowAfter=runCatching { read(fileUri(original)) ?: read(Uri.parse(original.originalUri)) }
                .getOrNull()
            if(rowAfter!=row || row.size!=length || sha.bytes!=length ||
                modified!=mtimeAfter || modified<0L) return@withContext unknown()
            val digest=referenceDigest(original)
            val public=publicIndexed(original)
            val observed=original.copy(relativePath=row.path,displayName=row.name,
                sizeBytes=length,modifiedAtMillis=modified,sha256=sha.sha256,
                referencesSha256=digest ?: original.referencesSha256)
            SelectiveArchiveMoveObservation(place,observed,public,
                digest==original.referencesSha256,owned)
        }

    override suspend fun ensureTarget(original:SelectiveArchiveIdentity,
        receipt:SelectiveArchiveMoveReceipt)=withContext(Dispatchers.IO) {
        requireSupported(original,receipt)
        target.ensure(original,receipt)
    }

    override suspend fun changeRelativePath(original:SelectiveArchiveIdentity,
        receipt:SelectiveArchiveMoveReceipt,toHidden:Boolean) = withContext(Dispatchers.IO) {
        requireSupported(original,receipt)
        val state=observe(original,receipt)
        val wanted=if(toHidden)SelectiveArchiveLocation.SOURCE else SelectiveArchiveLocation.HIDDEN
        check(state.location==wanted && state.identity?.mediaStoreId==original.mediaStoreId &&
            state.identity.sha256==original.sha256 &&
            state.identity.modifiedAtMillis==original.modifiedAtMillis &&
            state.referencesUnchanged==true &&
            state.visibleInAndroid==toHidden &&
            (!toHidden || target.inspect(original,receipt)) &&
            (toHidden || state.targetOwned==true)) {
            "Исходная запись или приватность изменились перед переносом"
        }
        val destination=if(toHidden)target.hiddenFileIfAbsent(original,receipt)
            else target.sourceFileIfAbsent(original)
        // The destination MUST NOT exist. Never overwrite a bystander.
        check(Files.notExists(destination.toPath(),NOFOLLOW_LINKS)) {
            "Целевой путь уже занят. Перемещение запрещено"
        }
        val uri=fileUri(original)
        val values=ContentValues().apply { put(MediaStore.MediaColumns.RELATIVE_PATH,
            if(toHidden)receipt.targetRelativePath else original.relativePath) }
        check(resolver.update(uri,values,null,null)==1) {
            "MediaStore не подтвердил изменение существующей записи"
        }
        // Scan the exact moved file; unknown scanner state is NOT treated as
        // success. The receipt keeps the previous intent for crash recovery.
        val after=if(toHidden)target.hiddenFile(original,receipt)
            else target.sourceFile(original)
        withTimeout(20_000L) {
            suspendCancellableCoroutine<Unit> { continuation ->
                MediaScannerConnection.scanFile(app,arrayOf(after.absolutePath),null) { _,_ ->
                    if(continuation.isActive)continuation.resume(Unit)
                }
            }
        }
    }
}
