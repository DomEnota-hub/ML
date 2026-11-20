package com.domenota.medialoader.data.privatevault

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal enum class VaultOrigin { ML_DOWNLOAD, EXTERNAL_MEDIA, EXTERNAL_DOCUMENT }
internal enum class VaultImportPhase {
    /** Private copy is verified; PUBLIC ORIGINAL STILL EXISTS. */
    COPY_VERIFIED_PUBLIC_PRESENT,
    /** Stage 4 must use visible Android consent and check its result. */
    AWAITING_PUBLIC_REMOVAL,
    /** Android removal refused, timed out or source changed; not hidden. */
    PUBLIC_REMOVAL_FAILED,
    /** Only after independent confirmation that the public source was removed. */
    FULLY_HIDDEN,
    RESTORE_PENDING,
}

/** No status transition here may itself delete, move or modify public originals. */
internal object VaultImportPolicy {
    fun canRequestRemoval(phase:VaultImportPhase, userConfirmed:Boolean, evidenceVerified:Boolean,
        recordDurable:Boolean):Boolean =
        phase in setOf(VaultImportPhase.COPY_VERIFIED_PUBLIC_PRESENT,VaultImportPhase.PUBLIC_REMOVAL_FAILED) &&
        userConfirmed && evidenceVerified && recordDurable

    fun advance(current:VaultImportPhase,next:VaultImportPhase,systemConfirmed:Boolean=false):Boolean = when(current) {
        VaultImportPhase.COPY_VERIFIED_PUBLIC_PRESENT ->
            next==VaultImportPhase.AWAITING_PUBLIC_REMOVAL
        VaultImportPhase.AWAITING_PUBLIC_REMOVAL ->
            next==VaultImportPhase.PUBLIC_REMOVAL_FAILED ||
                (next==VaultImportPhase.FULLY_HIDDEN && systemConfirmed)
        VaultImportPhase.PUBLIC_REMOVAL_FAILED ->
            next==VaultImportPhase.AWAITING_PUBLIC_REMOVAL
        VaultImportPhase.FULLY_HIDDEN -> next==VaultImportPhase.RESTORE_PENDING
        VaultImportPhase.RESTORE_PENDING -> next==VaultImportPhase.FULLY_HIDDEN
    }
}

internal data class VaultIndexSnapshot(
    val items: List<VaultImportRecord>,
    val unreadableRecords: Int,
)

internal data class VaultImportRecord(
    val id:String,
    val origin:VaultOrigin,
    val originalUri:String,
    val displayName:String,
    val privateFileId:String,
    val bytes:Long,
    val sha256:String,
    val phase:VaultImportPhase,
    val importedAt:Long,
)

/** Atomic app-private metadata. Each record is encrypted; user names and source URIs
 * never appear in plaintext preferences or public media indexes. Records are NOT
 * automatically hidden: the source remains until Android confirms its removal.
 */
internal class VaultImportIndex(context:Context,private val key:()->javax.crypto.SecretKey=VaultKeyManager::key) {
    private val root=File(context.applicationContext.noBackupFilesDir,"vault-index-v1").canonicalFile
    private val lock=Any()
    private fun target(id:String):AtomicFile {
        require(id.matches(Regex("[a-f0-9]{32}")))
        if(!root.isDirectory && !root.mkdirs()) error("Cannot create vault index")
        val file=File(root,"$id.record")
        require(file.canonicalFile.parentFile==root && file.canonicalFile==file.absoluteFile)
        return AtomicFile(file)
    }
    fun persist(record:VaultImportRecord, systemConfirmed:Boolean=false) = synchronized(lock) {
        require(record.bytes>=0L && record.sha256.matches(Regex("[a-f0-9]{64}")))
        require(record.privateFileId.matches(Regex("[a-f0-9]{32}")))
        require(record.displayName.length in 1..256)
        require(Uri.parse(record.originalUri).scheme=="content")
        val file=target(record.id)
        val previous=runCatching{read(record.id)}.getOrNull()
        if(previous!=null) {
            check(previous.copy(phase=record.phase)==record) { "Source identity changed" }
            check(previous.phase==record.phase ||
                VaultImportPolicy.advance(previous.phase,record.phase,systemConfirmed)) { "Invalid vault transition" }
        } else check(record.phase==VaultImportPhase.COPY_VERIFIED_PUBLIC_PRESENT) {
            "Initial record must retain the public original"
        }
        val data=JSONObject()
            .put("schema",1).put("id",record.id).put("origin",record.origin.name)
            .put("uri",record.originalUri).put("name",record.displayName)
            .put("private",record.privateFileId).put("bytes",record.bytes)
            .put("sha",record.sha256).put("phase",record.phase.name).put("at",record.importedAt)
            .toString().toByteArray(Charsets.UTF_8)
        val stream=file.startWrite()
        try {
            VaultCipher.encrypt(ByteArrayInputStream(data),stream,key())
            stream.fd.sync()
            file.finishWrite(stream)
        } catch(t:Throwable) { file.failWrite(stream);throw t }
    }
    /** List metadata without showing protected names while the access gate is locked.
     * Invalid/corrupt records are counted, not automatically deleted or overwritten. */
    fun snapshot():VaultIndexSnapshot = synchronized(lock) {
        if(!root.exists()) return@synchronized VaultIndexSnapshot(emptyList(),0)
        val results=mutableListOf<VaultImportRecord>()
        var unreadable=0
        root.listFiles().orEmpty().filter { it.name.matches(Regex("[a-f0-9]{32}\\.record")) }
            .forEach { file ->
                val record=runCatching { read(file.name.removeSuffix(".record")) }.getOrNull()
                if(record==null) unreadable++ else results+=record
            }
        VaultIndexSnapshot(results.sortedByDescending { it.importedAt },unreadable)
    }

    /** Only after a verified external copy and verified disposal of encrypted content. */
    fun eraseRestored(id:String) = synchronized(lock) {
        val record=read(id)
        check(record.phase==VaultImportPhase.RESTORE_PENDING) { "Restore was not verified" }
        target(id).delete()
        check(!target(id).baseFile.exists()) { "Protected index deletion failed" }
    }

    fun read(id:String):VaultImportRecord = synchronized(lock) {
        val bytes=java.io.ByteArrayOutputStream()
        target(id).openRead().use{VaultCipher.decrypt(it,bytes,key())}
        check(bytes.size()<=8192) { "Oversized vault metadata" }
        val obj=JSONObject(bytes.toString(Charsets.UTF_8.name()))
        require(obj.getInt("schema")==1 && obj.getString("id")==id)
        VaultImportRecord(id,VaultOrigin.valueOf(obj.getString("origin")),
            obj.getString("uri"),obj.getString("name"),obj.getString("private"),
            obj.getLong("bytes"),obj.getString("sha"),
            VaultImportPhase.valueOf(obj.getString("phase")),obj.getLong("at"))
    }
}

/** Safe preparation only. Stage 4 adds the explicit external-media UI and OS consent.
 * A verified copy alone is NOT a successful hiding operation.
 */
internal class VaultImportPreparation(context:Context) {
    private val access=PrivateAccessManager.get(context)
    private val files=VaultEncryptedFiles(context)
    private val index=VaultImportIndex(context)

    suspend fun prepare(uri:Uri,name:String,origin:VaultOrigin):VaultImportRecord =
        withContext(Dispatchers.IO) {
            access.requireUnlocked()
            require(uri.scheme=="content" && name.isNotBlank() && name.length<=256)
            val id=UUID.randomUUID().toString().replace("-","")
            val evidence=files.verifiedCopy(id,uri)
            access.requireUnlocked()
            val record=VaultImportRecord(id,origin,uri.toString(),name,id,
                evidence.bytes,evidence.sha256,VaultImportPhase.COPY_VERIFIED_PUBLIC_PRESENT,
                System.currentTimeMillis())
            index.persist(record)
            record
        }
}
