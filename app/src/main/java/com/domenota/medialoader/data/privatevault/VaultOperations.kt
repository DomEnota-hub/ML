package com.domenota.medialoader.data.privatevault

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.database.Cursor
import android.os.Build
import android.content.Intent
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A private authenticated copy is never presented as fully hidden until the
 * *original* has been removed with owner consent. No broad storage permissions. */
/** A verified public export exists. It must never be discarded because
 * protected cleanup or index finalization failed afterwards. */
internal class VaultExportCleanupPendingException(cause:Throwable):IllegalStateException(
    "Файл проверен и сохранён в выбранной папке. Очистка Сейфа завершилась с ошибкой. Не удаляйте публичную копию.",cause
)

internal class VaultOperations(context:Context) {
    private val app=context.applicationContext
    private val access=PrivateAccessManager.get(app)
    private val files=VaultEncryptedFiles(app)
    private val index=VaultImportIndex(app)

    fun snapshot():VaultIndexSnapshot {
        access.requireUnlocked()
        return index.snapshot()
    }

    suspend fun importSelected(uris:List<Uri>):List<Result<VaultImportRecord>> =
        withContext(Dispatchers.IO) {
            access.requireUnlocked()
            val limited=uris.distinct()
            require(limited.size<=200) { "За одну операцию можно добавить не более 200 файлов; ни один файл не изменён" }
            limited.map { uri ->
                runCatching {
                    access.requireUnlocked()
                    require(uri.scheme=="content") { "A file picker URI is required" }
                    val displayName=app.contentResolver.query(uri,
                        arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { cursor ->
                        if(cursor.moveToFirst())cursor.getString(0) else null
                    }?.takeIf{it.isNotBlank()}?.take(256) ?: "Без названия"
                    val origin=if(uri.authority==MediaStore.AUTHORITY)
                        VaultOrigin.EXTERNAL_MEDIA else VaultOrigin.EXTERNAL_DOCUMENT
                    VaultImportPreparation(app).prepare(uri,displayName,origin)
                }
            }
        }

    suspend fun verifyCurrent(record:VaultImportRecord):Boolean=withContext(Dispatchers.IO) {
        access.requireUnlocked()
        val expected=VaultCopyEvidence(record.bytes,record.sha256)
        if(!files.verify(record.privateFileId,expected))return@withContext false
        // Source might have changed between the initial copy and public deletion.
        runCatching {
            val hash=MessageDigest.getInstance("SHA-256")
            var count=0L
            (app.contentResolver.openInputStream(Uri.parse(record.originalUri))
                ?: return@runCatching false).use { input ->
                val buffer=ByteArray(65536)
                while(true) {
                    val length=input.read(buffer)
                    if(length<0) break
                    if(length>0) {hash.update(buffer,0,length);count+=length}
                }
            }
            val sha=hash.digest().joinToString(""){"%02x".format(it)}
            count==expected.bytes && sha==expected.sha256
        }.getOrDefault(false)
    }

    suspend fun requestRemoval(recordId:String):VaultImportRecord=withContext(Dispatchers.IO) {
        access.requireUnlocked()
        val record=index.read(recordId)
        require(VaultImportPolicy.canRequestRemoval(record.phase,true,
            verifyCurrent(record),true)) { "Source changed or protected copy is unavailable" }
        val pending=record.copy(phase=VaultImportPhase.AWAITING_PUBLIC_REMOVAL)
        index.persist(pending)
        pending
    }

    /** Android 11+ MediaStore.createDeleteRequest performs the deletion on system consent.
     * Verify item absence rather than trusting Activity.RESULT_OK alone. Permission errors
     * or unknown provider state must never be interpreted as an absent file. */
    suspend fun completeMediaConsent(id:String,approved:Boolean):VaultImportRecord =
        withContext(Dispatchers.IO) {
            access.requireUnlocked()
            val record=index.read(id)
            check(record.phase==VaultImportPhase.AWAITING_PUBLIC_REMOVAL)
            val absent=approved && mediaItemAbsent(Uri.parse(record.originalUri))==true
            val next=record.copy(phase=if(absent)VaultImportPhase.FULLY_HIDDEN
                else VaultImportPhase.PUBLIC_REMOVAL_FAILED)
            index.persist(next,systemConfirmed=absent)
            next
        }

    /** SAF providers delete documents only when they advertise DELETE capability. The
     * explicit in-app confirmation precedes this call; absence/failed deletion remains
     * a partial import without touching any other document. */
    suspend fun removeSafOriginal(id:String):VaultImportRecord=withContext(Dispatchers.IO) {
        access.requireUnlocked()
        val record=index.read(id)
        check(record.phase==VaultImportPhase.AWAITING_PUBLIC_REMOVAL)
        val uri=Uri.parse(record.originalUri)
        val success=runCatching {
            require(DocumentsContract.isDocumentUri(app,uri))
            require(supportsDelete(uri)) { "Provider does not permit document deletion" }
            require(verifyCurrent(record)) { "Public source changed" }
            DocumentsContract.deleteDocument(app.contentResolver,uri)
        }.getOrDefault(false)
        val next=record.copy(phase=if(success)VaultImportPhase.FULLY_HIDDEN
            else VaultImportPhase.PUBLIC_REMOVAL_FAILED)
        index.persist(next,systemConfirmed=success)
        next
    }

    private fun supportsDelete(uri:Uri):Boolean {
        val projection=arrayOf(DocumentsContract.Document.COLUMN_FLAGS)
        return app.contentResolver.query(uri,projection,null,null,null)?.use { cursor ->
            if(!cursor.moveToFirst())false
            else cursor.getLong(0) and DocumentsContract.Document.FLAG_SUPPORTS_DELETE.toLong()!=0L
        }==true
    }

    private fun mediaItemAbsent(uri:Uri):Boolean? = runCatching {
        require(uri.authority==MediaStore.AUTHORITY)
        app.contentResolver.query(uri, arrayOf(android.provider.BaseColumns._ID),
            null,null,null)?.use{cursor -> !cursor.moveToFirst()}
    }.getOrNull()

    suspend fun markAwaitingAsFailed(id:String):VaultImportRecord=withContext(Dispatchers.IO) {
        access.requireUnlocked()
        val old=index.read(id)
        if(old.phase!=VaultImportPhase.AWAITING_PUBLIC_REMOVAL)return@withContext old
        val next=old.copy(phase=VaultImportPhase.PUBLIC_REMOVAL_FAILED)
        index.persist(next)
        next
    }

    /** Export creates a new user-selected Android document. The private file stays
     * until the saved copy passes an independent readback test. */
    suspend fun export(id:String,destination:Uri,removeFromVault:Boolean):Boolean =
        withContext(Dispatchers.IO) {
            access.requireUnlocked()
            val record=index.read(id)
            check(record.phase!=VaultImportPhase.RESTORE_PENDING)
            val evidence=VaultCopyEvidence(record.bytes,record.sha256)
            val verified=files.exportVerified(record.privateFileId,destination,evidence)
            if(!verified)return@withContext false
            access.requireUnlocked()
            if(removeFromVault) {
                // At this point the *public* copy has passed independent SHA256
                // readback. Any later failure must preserve it.
                try {
                    index.persist(record.copy(phase=VaultImportPhase.RESTORE_PENDING))
                    if(files.deleteVerified(record.privateFileId,evidence)) index.eraseRestored(id)
                    else error("Private cleanup needs a retry")
                } catch(error:Throwable) {
                    if(error is kotlinx.coroutines.CancellationException)throw error
                    throw VaultExportCleanupPendingException(error)
                }
            }
            true
        }

    suspend fun finishRestoredCleanup(id:String):Boolean=withContext(Dispatchers.IO) {
        access.requireUnlocked()
        val record=index.read(id)
        if(record.phase!=VaultImportPhase.RESTORE_PENDING)return@withContext false
        // Index phase RESTORE_PENDING is written only after a public export passed
        // an independent SHA-256 readback. A process crash between file deletion
        // and index cleanup must not strand a phantom private file forever.
        if(!files.exists(record.privateFileId)) {
            index.eraseRestored(id)
            return@withContext true
        }
        val evidence=VaultCopyEvidence(record.bytes,record.sha256)
        if(!files.deleteVerified(record.privateFileId,evidence))return@withContext false
        index.eraseRestored(id)
        true
    }

    fun mediaDeleteIntent(id:String):android.content.IntentSender {
        access.requireUnlocked()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            throw UnsupportedOperationException("Удаление MediaStore с подтверждением доступно с Android 11")
        }
        val record=index.read(id)
        check(record.phase==VaultImportPhase.AWAITING_PUBLIC_REMOVAL)
        val uri=Uri.parse(record.originalUri)
        require(uri.authority==MediaStore.AUTHORITY)
        return MediaStore.createDeleteRequest(app.contentResolver,listOf(uri)).intentSender
    }
}
