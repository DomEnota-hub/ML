package com.domenota.medialoader.data.catalog.visibility

import androidx.room.withTransaction
import com.domenota.medialoader.core.database.HistoryDatabase
import com.domenota.medialoader.core.database.SettingsEntity
import kotlinx.coroutines.flow.map

/** Uses the existing durable Room settings table, not preferences/cache. No
 * destructive migration and no new meaning for the vault/trash `hidden` flag.
 * All callers must hold the visibility operation gate while changing membership. */
internal class CatalogVisibilityState(private val database: HistoryDatabase) {
    private val settings = database.settingsDao()
    private val catalog = database.catalogDao()
    // Single Room query: mode and receipt are committed/observed together.
    val snapshot = settings.observeByPrefix(PREFIX).map { rows ->
        val hidden = mutableSetOf<String>()
        val receipts = mutableListOf<VisibilityMoveRecord>()
        val retired=mutableSetOf<String>()
        for (row in rows) when {
            row.key.startsWith(MODE) -> {
                check(row.value in setOf(HIDDEN,VISIBLE))
                val id = row.key.removePrefix(MODE); check(id.isNotBlank())
                if (row.value == HIDDEN) hidden += id
            }
            row.key.startsWith(RECEIPT) -> {
                val record = VisibilityReceiptPolicy.decode(row.value)
                check(row.key == RECEIPT + record.downloadId)
                receipts += record
            }
            row.key.startsWith(RETIRED) -> retired+=decodeRetired(row.value)
            else -> error("Неизвестная запись видимости папок")
        }
        FolderVisibilitySnapshot(hidden,receipts,retired)
    }
    val hiddenFolderIds = snapshot.map { it.hiddenFolders }
    val receipts = snapshot.map { it.receipts }
    suspend fun isHidden(folderId: String): Boolean = when (settings.get(MODE + folderId)?.value) {
        HIDDEN -> true
        VISIBLE, null -> false
        else -> error("Неизвестное состояние видимости папки")
    }
    suspend fun receipt(mediaId: String): VisibilityMoveRecord? = settings.get(RECEIPT + mediaId)?.let {
        VisibilityReceiptPolicy.decode(it.value).also { record -> check(record.downloadId == mediaId) }
    }
    /** Only after byte verification, binding verification and final system index confirmation. */
    suspend fun rememberCompleted(record: VisibilityMoveRecord) = database.withTransaction {
        check(catalog.folderById(record.folderId) != null)
        val previous = receipt(record.downloadId)
        VisibilityReceiptPolicy.validateTransition(previous,record)
        if (previous != record) settings.upsert(SettingsEntity(RECEIPT + record.downloadId,VisibilityReceiptPolicy.encode(record)))
        val retiredKey=RETIRED+record.downloadId
        val existing=settings.get(retiredKey)?.value?.let(::decodeRetired).orEmpty()
        val retired=existing+listOfNotNull(previous,record).flatMap { FolderVisibilityReadPolicy.retiredLocatorsFor(it) }
        if(retired!=existing) {
            check(retired.size<=4096)
            settings.upsert(SettingsEntity(retiredKey,org.json.JSONArray(retired.sorted()).toString().also { check(it.length<=512*1024) }))
        }
    }
    /** External IDs are the exact, freshly confirmed external-catalog members.
     * The caller must hold the cross-store mutation gate until this transaction commits. */
    suspend fun commitFolder(batch: VisibilityBatchRecord, externalIds: Set<String>) = database.withTransaction {
        check(batch.next == batch.members.size)
        check(catalog.folderById(batch.folderId) != null) { "Папка исчезла" }
        val appIds = catalog.memberIds(batch.folderId).toSet()
        check(appIds.intersect(externalIds).isEmpty()) { "Неоднозначный состав папки" }
        check(appIds + externalIds == batch.members.mapTo(mutableSetOf()) { it.downloadId }) { "Состав папки изменился" }
        for (member in batch.members) {
            val record = receipt(member.downloadId)
            check(VisibilityReceiptPolicy.confirms(record,member)) { "Файл не прошёл окончательную проверку" }
            if (member.downloadId in appIds) {
                val row = database.downloadDao().byId(member.downloadId)
                check(row != null && !row.hidden && !row.isPrivate && row.savedUri == record?.destinationUri &&
                    row.state == com.domenota.medialoader.core.model.DownloadState.COMPLETED) { "История файла изменилась" }
            }
        }
        val desired = if (batch.direction == VisibilityDirection.HIDE) HIDDEN else VISIBLE
        if (settings.get(MODE + batch.folderId)?.value != desired) settings.upsert(SettingsEntity(MODE + batch.folderId,desired))
    }
    /** A controlled editor must hold the same operation gate and verify the new
     * bytes before updating its logical size and this receipt. No arbitrary rescan. */
    suspend fun rememberVerifiedHiddenEdit(expected: VisibilityMoveRecord, currentUri: String,
        actualAfter: VisibilityFingerprint, modifiedMillis: Long) = database.withTransaction {
        check(isHidden(expected.folderId) && receipt(expected.downloadId) == expected)
        val next = VisibilityReceiptPolicy.afterVerifiedEdit(expected,currentUri,expected.fingerprint,actualAfter,modifiedMillis)
        val row = database.downloadDao().byId(expected.downloadId)
        if (row != null) check(!row.hidden && !row.isPrivate && row.state == com.domenota.medialoader.core.model.DownloadState.COMPLETED &&
            row.savedUri == currentUri && row.sizeBytes == actualAfter.bytes && catalog.assignmentById(row.id)?.folderId == expected.folderId)
        settings.upsert(SettingsEntity(RECEIPT + expected.downloadId,VisibilityReceiptPolicy.encode(next)))
    }

    suspend fun relocateHiddenReceipt(expected:VisibilityMoveRecord,targetFolder:String) = database.withTransaction {
        check(expected.direction==VisibilityDirection.HIDE && isHidden(expected.folderId) && isHidden(targetFolder))
        val updated=expected.copy(folderId=targetFolder)
        val current=receipt(expected.downloadId)
        check(current==expected || current==updated) { "Состояние скрытого файла изменилось" }
        if(current!=updated) settings.upsert(SettingsEntity(RECEIPT+expected.downloadId,VisibilityReceiptPolicy.encode(updated)))
    }

    /** Never discard the only metadata that can restore a physically hidden file. */
    suspend fun forgetVisibleFolder(folderId: String) = database.withTransaction {
        check(!isHidden(folderId))
        check(settings.byPrefix(RECEIPT).none { row ->
            val record = VisibilityReceiptPolicy.decode(row.value)
            record.folderId == folderId && record.direction == VisibilityDirection.HIDE
        }) { "Сначала верните видимость файлов папки" }
        settings.remove(MODE + folderId)
    }
    private fun decodeRetired(raw:String):Set<String> {
        check(raw.length<=512*1024)
        val array=org.json.JSONArray(raw); check(array.length()<=4096)
        return (0 until array.length()).mapTo(mutableSetOf()) { array.getString(it).also { value -> check(value.isNotBlank()) } }
    }
    companion object {
        private const val PREFIX = "folder_visibility_"
        private const val RETIRED = "folder_visibility_retired:"
        private const val MODE = "folder_visibility_mode:"
        private const val RECEIPT = "folder_visibility_receipt:"
        private const val HIDDEN = "hidden"
        private const val VISIBLE = "visible"
    }
}
