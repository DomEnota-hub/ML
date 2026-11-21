package com.domenota.medialoader.data.catalog.visibility

import java.io.File
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** The only supported phase is READ_ONLY. Absolutely no files are moved by this store.
 *
 * The durable checkpoint is a prerequisite for a separately verified selective
 * mover, not evidence that Android MediaStore IDs survive a .nomedia rescan.
 */
internal data class SelectiveArchiveCheckpoint(
    val folderId: String,
    val originals: List<SelectiveArchiveIdentity>,
    val createdAtMillis: Long,
) {
    init {
        require(folderId.isNotBlank() && originals.isNotEmpty() && originals.size <= 2048)
        require(originals.all { it.wellFormed() })
        require(createdAtMillis > 0L)
    }
}

internal class SelectiveArchiveCheckpointStore(private val directory: File) {
    // The proven existing journal provides durable CAS, sha256 integrity and
    // NOFOLLOW_LINKS. Keep Archive separate from the retired visibility journal.
    private val store = VisibilityBindingProofStore(directory)

    private fun key(folderId: String): String {
        require(folderId.isNotBlank())
        val digest=MessageDigest.getInstance("SHA-256")
            .digest(folderId.toByteArray(Charsets.UTF_8))
        return "selective-" + digest.joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    suspend fun read(folderId: String): SelectiveArchiveCheckpoint? {
        val raw=store.read(key(folderId)) ?: return null
        val decoded=decode(raw)
        check(decoded.folderId==folderId) { "Неверная привязка контрольной точки" }
        return decoded
    }

    /** Enumerate every durable archive checkpoint, including folders removed
     * from the current catalog. Never infer privacy solely from current UI
     * assignments. An unexpected binding makes the entire scan UNKNOWN. */
    suspend fun listAll(): List<SelectiveArchiveCheckpoint> = withContext(Dispatchers.IO) {
        val root=directory.toPath()
        if(Files.notExists(root,NOFOLLOW_LINKS))return@withContext emptyList()
        check(Files.isDirectory(root,NOFOLLOW_LINKS) && !Files.isSymbolicLink(root)) {
            "Хранилище Архива требует проверки"
        }
        val ids=mutableListOf<String>()
        Files.newDirectoryStream(root).use { entries ->
            for(entry in entries) {
                val name=entry.fileName.toString()
                // A staged temp proof is never a committed archive intent.
                if(name.startsWith(".bindings-") && name.endsWith(".tmp"))continue
                check(Files.isRegularFile(entry,NOFOLLOW_LINKS) && !Files.isSymbolicLink(entry) &&
                    name.matches(Regex("selective-[0-9a-f]{64}\\.binding"))) {
                    "Неизвестная запись в журнале Архива"
                }
                ids+=name.removeSuffix(".binding")
                check(ids.size<=4096) { "Журнал Архива превышает допустимый объём" }
            }
        }
        val checkpoints=ids.sorted().map { id ->
            val raw=checkNotNull(store.read(id)) { "Запись Архива исчезла при чтении" }
            decode(raw).also { checkpoint ->
                check(key(checkpoint.folderId)==id) {
                    "Идентификатор журнала Архива не подтверждён"
                }
            }
        }
        check(checkpoints.map { it.folderId }.distinct().size==checkpoints.size) {
            "Повтор каталога в журнале Архива"
        }
        checkpoints
    }

    /** A durable source snapshot is recorded BEFORE future file mutations.
     * Existing evidence may only be replayed identically, never overwritten.
     * No automatic commit or restore is allowed by this API.
     */
    suspend fun prepare(
        folderId: String,
        originals: List<SelectiveArchiveIdentity>,
        selected: List<NomediaMediaSignature>,
        physical: List<NomediaMediaSignature>,
        clock: () -> Long = System::currentTimeMillis,
    ): SelectiveArchiveCheckpoint {
        check(verifySelectiveArchiveSources(originals,selected,physical)==null) {
            "Точный набор исходников выборочного архива не подтверждён"
        }
        val previous=read(folderId)
        if(previous!=null) {
            check(previous.originals==originals) {
                "Контрольная точка отличается от текущих файлов. Автоматическая замена запрещена"
            }
            return previous
        }
        val snapshot=SelectiveArchiveCheckpoint(folderId,originals,clock())
        store.write(key(folderId),null,encode(snapshot))
        check(read(folderId)==snapshot) { "Контрольная точка не подтверждена после записи" }
        return snapshot
    }

    private fun encode(value:SelectiveArchiveCheckpoint):String {
        val entries=JSONArray()
        value.originals.forEach { row ->
            entries.put(JSONObject().apply {
                put("catalogId",row.catalogId);put("uri",row.originalUri)
                put("mediaId",row.mediaStoreId);put("volume",row.volume)
                put("path",row.relativePath);put("name",row.displayName)
                put("bytes",row.sizeBytes);put("sha256",row.sha256)
                put("modified",row.modifiedAtMillis);put("references",row.referencesSha256)
            })
        }
        return JSONObject().put("schema",1).put("phase","READ_ONLY")
            .put("folder",value.folderId).put("created",value.createdAtMillis)
            .put("originals",entries).toString()
    }

    private fun decode(raw:String):SelectiveArchiveCheckpoint {
        check(raw.length<=32*1024*1024) { "Журнал выборочного архива слишком велик" }
        val root=JSONObject(raw)
        check(root.getInt("schema")==1 && root.getString("phase")=="READ_ONLY") {
            "Неизвестное состояние выборочного архива. Возобновление запрещено"
        }
        val entries=root.getJSONArray("originals")
        check(entries.length() in 1..2048)
        val originals=(0 until entries.length()).map { i ->
            val o=entries.getJSONObject(i)
            SelectiveArchiveIdentity(
                catalogId=o.getString("catalogId"),originalUri=o.getString("uri"),
                mediaStoreId=o.getLong("mediaId"),volume=o.getString("volume"),
                relativePath=o.getString("path"),displayName=o.getString("name"),
                sizeBytes=o.getLong("bytes"),sha256=o.getString("sha256"),
                modifiedAtMillis=o.getLong("modified"),referencesSha256=o.getString("references")
            )
        }
        check(originals.map { it.catalogId }.distinct().size==originals.size &&
            originals.map { it.originalUri }.distinct().size==originals.size &&
            originals.map { it.volume to it.mediaStoreId }.distinct().size==originals.size) {
            "Повтор идентификатора в журнале"
        }
        return SelectiveArchiveCheckpoint(root.getString("folder"),originals,root.getLong("created"))
    }
}
