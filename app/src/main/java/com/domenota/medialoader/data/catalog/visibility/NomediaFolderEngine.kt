package com.domenota.medialoader.data.catalog.visibility

/** 1.7.206: metadata-only visibility. No content URI rebind, physical media copy or delete. */
internal enum class NomediaMarkerState {
    PENDING_HIDE, PENDING_SHOW, OWN_MARKER, FOREIGN_MARKER, NO_MARKER, FAILED
}

internal data class NomediaFolderRecord(
    val folderId: String,
    val treeUri: String,
    val mlHidden: Boolean,
    val markerState: NomediaMarkerState,
    val ownedDocumentId: String? = null,
    val errorCode: String? = null,
    val updatedAt: Long = 0L,
) {
    init {
        require(folderId.isNotBlank() && treeUri.isNotBlank())
        require(ownedDocumentId == null || ownedDocumentId.isNotBlank())
        require(errorCode == null || (errorCode.length <= 64 && errorCode.all { it.isUpperCase() || it.isDigit() || it == '_' }))
    }
    /** A crash between durable intent and marker handling must not expose a folder in ML. */
    val excludedFromMl: Boolean get() = mlHidden || markerState == NomediaMarkerState.PENDING_HIDE ||
        markerState == NomediaMarkerState.PENDING_SHOW
    /** An owned marker only proves a file identity, not Android/Xiaomi scanner state. */
    val markerVerified: Boolean get() = markerState == NomediaMarkerState.OWN_MARKER ||
        markerState == NomediaMarkerState.FOREIGN_MARKER
}

internal data class NomediaFolderSnapshot(
    val folders: Map<String, NomediaFolderRecord> = emptyMap(),
    val unknown: Boolean = false,
) {
    val hiddenFolderIds: Set<String> get() = folders.values.filter { it.excludedFromMl }.mapTo(mutableSetOf()) { it.folderId }
}

internal data class NomediaMarkerProof(val documentId: String, val bytes: Long) {
    init { require(documentId.isNotBlank() && bytes >= 0) }
}

/** Deliberately cannot read, rename, copy or delete a media file. Only .nomedia. */
internal interface NomediaMarkerPort {
    suspend fun inspect(treeUri: String): NomediaMarkerProof?
    suspend fun createEmpty(treeUri: String): NomediaMarkerProof
    suspend fun deleteVerified(treeUri: String, documentId: String): Boolean
}
internal interface NomediaFolderStore {
    fun snapshot(): NomediaFolderSnapshot
    fun write(record: NomediaFolderRecord)
}

/** Synchronous durable intent is written before any SAF marker mutation. */
internal class NomediaFolderEngine(
    private val store: NomediaFolderStore,
    private val port: NomediaMarkerPort,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun hide(folderId: String, treeUri: String): NomediaFolderRecord {
        require(folderId.isNotBlank() && treeUri.isNotBlank())
        val previous = store.snapshot()
        check(!previous.unknown) { "Реестр скрытия повреждён. Действие заблокировано" }
        val old = previous.folders[folderId]
        check(previous.folders.values.none { other ->
            other.folderId != folderId && other.treeUri == treeUri &&
                (other.excludedFromMl || other.markerState == NomediaMarkerState.OWN_MARKER)
        }) { "Физический SAF-каталог уже связан с другой скрытой папкой" }
        check(old == null || !old.excludedFromMl && old.markerState !in
            setOf(NomediaMarkerState.FAILED, NomediaMarkerState.PENDING_HIDE, NomediaMarkerState.PENDING_SHOW)) {
            "Состояние скрытия требует проверки"
        }
        // A previously owned marker may never be silently adopted from another tree.
        check(old?.ownedDocumentId == null) { "Ранее созданный маркер требует проверки" }
        val existing = port.inspect(treeUri) // permission and tree preflight, no changes
        val pending = NomediaFolderRecord(folderId,treeUri,true,NomediaMarkerState.PENDING_HIDE,updatedAt=clock())
        store.write(pending)
        return try {
            val appeared = if (existing == null) port.inspect(treeUri) else existing
            val created = if (appeared == null) port.createEmpty(treeUri) else null
            val verified = existing ?: appeared ?: checkNotNull(created)
            if (created != null) check(verified.bytes == 0L) { "Созданный маркер не является пустым" }
            // If it appeared during inspection it is foreign, not ours.
            val createdByUs = created != null
            val result = pending.copy(
                markerState = if (createdByUs) NomediaMarkerState.OWN_MARKER else NomediaMarkerState.FOREIGN_MARKER,
                ownedDocumentId = if (createdByUs) verified.documentId else null,updatedAt=clock())
            store.write(result)
            result
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (error: Exception) {
            // Remain conservatively hidden: the marker might have been created
            // before the failure and must never be guessed to belong to ML.
            val result = pending.copy(markerState=NomediaMarkerState.FAILED,
                errorCode="NOMEDIA_HIDE_INCOMPLETE", updatedAt=clock())
            runCatching { store.write(result) }
            throw error
        }
    }

    /** Explicit read-only reconciliation for a crashed/failed marker operation.
     * Unknown ownership is always treated as foreign and never deleted. */
    suspend fun reconcile(folderId:String):NomediaFolderRecord {
        val snapshot=store.snapshot()
        check(!snapshot.unknown) { "Реестр скрытия повреждён" }
        val current=checkNotNull(snapshot.folders[folderId]) { "Папка не найдена" }
        val observed=port.inspect(current.treeUri)
        val owned=current.ownedDocumentId
        check(owned==null || observed==null || (observed.documentId==owned && observed.bytes==0L)) {
            "Контрольная точка не совпадает с маркером"
        }
        val result = when {
            owned!=null && observed!=null -> current.copy(mlHidden=true,
                markerState=NomediaMarkerState.OWN_MARKER,errorCode=null,updatedAt=clock())
            owned!=null -> current.copy(mlHidden=false,ownedDocumentId=null,
                markerState=NomediaMarkerState.NO_MARKER,errorCode=null,updatedAt=clock())
            observed!=null -> current.copy(mlHidden=current.mlHidden,
                markerState=NomediaMarkerState.FOREIGN_MARKER,errorCode=null,updatedAt=clock())
            else -> current.copy(mlHidden=current.mlHidden,
                markerState=NomediaMarkerState.NO_MARKER,errorCode=null,updatedAt=clock())
        }
        store.write(result)
        return result
    }

    suspend fun show(folderId: String): NomediaFolderRecord {
        val snapshot = store.snapshot()
        check(!snapshot.unknown) { "Реестр скрытия повреждён" }
        val current = checkNotNull(snapshot.folders[folderId]) { "Папка не связана с SAF" }
        check(current.mlHidden && current.markerState in setOf(NomediaMarkerState.OWN_MARKER,
            NomediaMarkerState.FOREIGN_MARKER, NomediaMarkerState.NO_MARKER)) {
            "Сначала проверьте незавершённое скрытие"
        }
        val observed = port.inspect(current.treeUri)
        if(current.ownedDocumentId != null) {
            check(observed?.documentId == current.ownedDocumentId && observed.bytes == 0L) {
                "Служебный маркер изменён. Удаление запрещено"
            }
        }
        val pending = current.copy(markerState=NomediaMarkerState.PENDING_SHOW,updatedAt=clock())
        store.write(pending)
        return try {
            val removed = if(current.ownedDocumentId != null)
                port.deleteVerified(current.treeUri,current.ownedDocumentId) else false
            check(current.ownedDocumentId == null || removed) { "Удаление маркера не подтверждено" }
            val after = port.inspect(current.treeUri)
            check(current.ownedDocumentId == null || after == null) {
                "Маркер остался после запроса удаления"
            }
            val result = pending.copy(mlHidden=false,ownedDocumentId=null,
                markerState=if(after != null) NomediaMarkerState.FOREIGN_MARKER else NomediaMarkerState.NO_MARKER,
                updatedAt=clock())
            store.write(result)
            result
        } catch (cancelled:kotlinx.coroutines.CancellationException) { throw cancelled }
        catch(error:Exception) {
            // Do not claim visible if marker removal or durable write failed.
            runCatching { store.write(pending.copy(markerState=NomediaMarkerState.FAILED,
                errorCode="NOMEDIA_SHOW_INCOMPLETE",updatedAt=clock())) }
            throw error
        }
    }
}
