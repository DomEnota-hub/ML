package com.domenota.medialoader.data.catalog.visibility

import java.io.File
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Independent selective-archive journal. This never reads or rewrites the
 * retired physical-transfer v1/v2 receipts.
 *
 * A phase is written BEFORE a MediaStore mutation. An interrupted operation
 * remains recoverable by exact ID / exact fingerprint observation, never a
 * guessed filename, a reused row ID or an automatic public reinsertion.
 */
internal enum class SelectiveArchiveMovePhase {
    PREPARED,
    HIDE_INTENT,
    HIDDEN_VERIFIED,
    RESTORE_INTENT,
    RESTORED_VERIFIED,
    NEEDS_REVIEW,
}

internal data class SelectiveArchiveMoveReceipt(
    val folderId:String,
    val catalogId:String,
    val originalUri:String,
    val originalSha256:String,
    val targetRelativePath:String,
    val token:String,
    val phase:SelectiveArchiveMovePhase,
    val sequence:Long,
) {
    init {
        require(folderId.isNotBlank() && catalogId.isNotBlank())
        require(originalUri.startsWith("content://media/") &&
            originalUri.substringAfterLast('/').toLongOrNull()?.let { it > 0 } == true)
        require(originalSha256.length==64 && originalSha256.all { it in '0'..'9' || it in 'a'..'f' })
        require(token.matches(Regex("[A-Za-z0-9_-]{24,64}")))
        require(targetRelativePath.endsWith("/") && !targetRelativePath.startsWith("/") &&
            targetRelativePath.split('/').dropLast(1).all {
                it.isNotBlank() && it != "." && it != ".." && '\\' !in it && !it.startsWith(".")
            })
        require(sequence in 0..Long.MAX_VALUE - 1)
    }
}

internal class SelectiveArchiveMoveJournal(private val directory:File) {
    private val proof=VisibilityBindingProofStore(directory)

    private fun id(folderId:String,catalogId:String):String {
        require(folderId.isNotBlank() && catalogId.isNotBlank())
        val hash=MessageDigest.getInstance("SHA-256")
            .digest((folderId.length.toString()+":"+folderId+catalogId).toByteArray(Charsets.UTF_8))
        return "sel-move-"+hash.joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    /** Proof filenames are also a privacy input. A phase without its source
     * checkpoint must block public publishing, even if the gallery membership
     * no longer contains that folder. Never discard orphaned journal bytes. */
    internal fun memberKey(folderId:String,catalogId:String):String=id(folderId,catalogId)

    suspend fun durableKeys():Set<String> = withContext(Dispatchers.IO) {
        val root=directory.toPath()
        if(Files.notExists(root,NOFOLLOW_LINKS))return@withContext emptySet()
        check(Files.isDirectory(root,NOFOLLOW_LINKS) && !Files.isSymbolicLink(root)) {
            "Хранилище журнала Архива требует проверки"
        }
        val keys=mutableSetOf<String>()
        Files.newDirectoryStream(root).use { entries ->
            for(entry in entries) {
                val name=entry.fileName.toString()
                if(name.startsWith(".bindings-") && name.endsWith(".tmp")) continue
                check(Files.isRegularFile(entry,NOFOLLOW_LINKS) &&
                    name.matches(Regex("sel-move-[0-9a-f]{64}\\.binding"))) {
                    "Незнакомая запись в журнале выборочного Архива"
                }
                check(keys.add(name.removeSuffix(".binding")) && keys.size<=8192) {
                    "Невозможно подтвердить состав журнала Архива"
                }
            }
        }
        keys
    }

    suspend fun read(folderId:String,catalogId:String):SelectiveArchiveMoveReceipt? {
        val raw=proof.read(id(folderId,catalogId)) ?: return null
        val record=decode(raw)
        check(record.folderId==folderId && record.catalogId==catalogId) {
            "Идентификатор журнала не совпадает с запросом"
        }
        return record
    }

    /** An existing operation must be resumed from its existing receipt. A new
     * request cannot replace a previous in-flight or completed operation. */
    suspend fun begin(
        checkpoint:SelectiveArchiveCheckpoint,
        original:SelectiveArchiveIdentity,
        token:String,
        targetRelativePath:String,
    ):SelectiveArchiveMoveReceipt {
        check(checkpoint.originals.singleOrNull { it.catalogId==original.catalogId }==original) {
            "Нет точной исходной контрольной точки"
        }
        val next=SelectiveArchiveMoveReceipt(
            checkpoint.folderId, original.catalogId,original.originalUri,original.sha256,
            targetRelativePath,token,SelectiveArchiveMovePhase.PREPARED,0L,
        )
        val previous=read(checkpoint.folderId,original.catalogId)
        if(previous!=null) {
            check(previous.originalUri==next.originalUri && previous.originalSha256==next.originalSha256 &&
                previous.targetRelativePath==next.targetRelativePath && previous.token==next.token) {
                "Существует другая операция. Подмена контрольной точки запрещена"
            }
            return previous
        }
        proof.write(id(checkpoint.folderId,original.catalogId),null,encode(next))
        check(read(checkpoint.folderId,original.catalogId)==next)
        return next
    }

    suspend fun transition(
        expected:SelectiveArchiveMoveReceipt,
        nextPhase:SelectiveArchiveMovePhase,
    ):SelectiveArchiveMoveReceipt {
        val permitted=when(expected.phase) {
            SelectiveArchiveMovePhase.PREPARED ->
                setOf(SelectiveArchiveMovePhase.HIDE_INTENT,
                    SelectiveArchiveMovePhase.RESTORED_VERIFIED,SelectiveArchiveMovePhase.NEEDS_REVIEW)
            SelectiveArchiveMovePhase.HIDE_INTENT ->
                setOf(SelectiveArchiveMovePhase.HIDDEN_VERIFIED,
                    SelectiveArchiveMovePhase.RESTORE_INTENT,
                    SelectiveArchiveMovePhase.RESTORED_VERIFIED,SelectiveArchiveMovePhase.NEEDS_REVIEW)
            SelectiveArchiveMovePhase.HIDDEN_VERIFIED ->
                setOf(SelectiveArchiveMovePhase.RESTORE_INTENT,SelectiveArchiveMovePhase.NEEDS_REVIEW)
            SelectiveArchiveMovePhase.RESTORE_INTENT ->
                setOf(SelectiveArchiveMovePhase.RESTORED_VERIFIED,SelectiveArchiveMovePhase.NEEDS_REVIEW)
            SelectiveArchiveMovePhase.RESTORED_VERIFIED ->
                setOf(SelectiveArchiveMovePhase.HIDE_INTENT)
            SelectiveArchiveMovePhase.NEEDS_REVIEW -> emptySet()
        }
        check(nextPhase in permitted) { "Некорректный переход выборочного архива" }
        val previous=read(expected.folderId,expected.catalogId)
        check(previous==expected) { "Журнал изменился. Операция остановлена" }
        val next=expected.copy(phase=nextPhase,sequence=Math.addExact(expected.sequence,1L))
        proof.write(id(expected.folderId,expected.catalogId),encode(expected),encode(next))
        check(read(expected.folderId,expected.catalogId)==next) {
            "Не удалось проверить записанную фазу"
        }
        return next
    }

    private fun encode(r:SelectiveArchiveMoveReceipt):String =
        JSONObject().put("schema",1).put("folder",r.folderId).put("catalog",r.catalogId)
            .put("uri",r.originalUri).put("sha256",r.originalSha256)
            .put("target",r.targetRelativePath).put("token",r.token)
            .put("phase",r.phase.name).put("sequence",r.sequence).toString()

    private fun decode(data:String):SelectiveArchiveMoveReceipt {
        check(data.length in 1..16_384) { "Неверный размер журнала выборочного архива" }
        val value=JSONObject(data)
        check(value.getInt("schema")==1) { "Неизвестная версия журнала" }
        return SelectiveArchiveMoveReceipt(
            value.getString("folder"),value.getString("catalog"),
            value.getString("uri"),value.getString("sha256"),
            value.getString("target"),value.getString("token"),
            SelectiveArchiveMovePhase.valueOf(value.getString("phase")),
            value.getLong("sequence"),
        )
    }
}
