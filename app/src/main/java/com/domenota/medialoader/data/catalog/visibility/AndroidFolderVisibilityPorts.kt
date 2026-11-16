package com.domenota.medialoader.data.catalog.visibility

import android.app.PendingIntent
import android.content.Context
import android.net.Uri
import androidx.annotation.RequiresApi
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Catalog identity layer must handle app and external members without importing
 * external files into download history. It must persist/replay any cross-store CAS
 * and reject removal if any live alias still points at the source. */
internal interface VisibilityCatalogBindings {
    suspend fun currentUri(record: VisibilityMoveRecord): String?
    suspend fun verifyMembership(batch: VisibilityBatchRecord)
    suspend fun rebind(record: VisibilityMoveRecord)
    suspend fun verifyBinding(record: VisibilityMoveRecord)
    suspend fun verifySourceUnreferenced(record: VisibilityMoveRecord)
    suspend fun externalMemberIds(folderId: String): Set<String>
    suspend fun finishBinding(record: VisibilityMoveRecord) = Unit
}

/** Android physical adapter for the existing journal engine. No destructive
 * action is reachable without verified destination bytes and catalog binding. */
@RequiresApi(30)
internal class AndroidFolderVisibilityPorts(
    context: Context,
    private val state: CatalogVisibilityState,
    private val bindings: FolderVisibilityCatalogBindings,
    private val approveCleanup: suspend (PendingIntent) -> Boolean,
) : VisibilityMovePorts, VisibilityBatchPorts {
    private val media = VisibilityMediaStore(context.applicationContext)
    private fun store() = FolderVisibilityStorageAccess.store()
    private fun origin(record: VisibilityMoveRecord) = requireNotNull(record.publicOrigin) { "Нет исходного расположения файла" }
    private fun hiddenFile(uri: String): File {
        val parsed = Uri.parse(uri)
        check(parsed.scheme == "file" && parsed.authority.isNullOrEmpty() && parsed.query == null && parsed.fragment == null)
        val file = File(requireNotNull(parsed.path))
        check(store().owns(file)) { "Файл находится вне скрытого хранилища" }
        return file
    }
    private fun publicUri(record: VisibilityMoveRecord): String? = if (record.direction == VisibilityDirection.HIDE)
        record.sourceUri else record.destinationUri
    private fun hiddenUri(record: VisibilityMoveRecord): String = if (record.direction == VisibilityDirection.HIDE)
        requireNotNull(record.destinationUri) else record.sourceUri

    override suspend fun currentUri(record: VisibilityMoveRecord) = bindings.currentUri(record)
    override suspend fun fingerprint(uri: String): VisibilityFingerprint = error("Для проверки требуется запись переноса")
    override suspend fun presence(uri: String): VisibilityPresence = error("Для проверки требуется запись переноса")
    override suspend fun fingerprint(record: VisibilityMoveRecord, uri: String): VisibilityFingerprint = withContext(Dispatchers.IO) {
        val original = origin(record)
        if (uri == publicUri(record)) {
            if (record.direction == VisibilityDirection.HIDE) HiddenFolderFileStore.fingerprint(media.openOriginal(original))
            else media.restoreFingerprint(original,record.destinationToken,uri)
        } else {
            check(uri == hiddenUri(record)); HiddenFolderFileStore.fingerprint(hiddenFile(uri).inputStream())
        }
    }
    override suspend fun presence(record: VisibilityMoveRecord, uri: String): VisibilityPresence = withContext(Dispatchers.IO) {
        val original = origin(record)
        if (record.direction == VisibilityDirection.HIDE && uri == record.sourceUri) media.originalPresence(original)
        else if (record.direction == VisibilityDirection.SHOW && uri == record.destinationUri)
            media.restorePresence(original,record.destinationToken,uri)
        else try {
            check(uri == hiddenUri(record)); val file = hiddenFile(uri)
            when {
                Files.isRegularFile(file.toPath(),NOFOLLOW_LINKS) -> VisibilityPresence.PRESENT
                Files.notExists(file.toPath(),NOFOLLOW_LINKS) && file.parentFile?.canRead() == true -> VisibilityPresence.MISSING
                else -> VisibilityPresence.UNKNOWN
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { VisibilityPresence.UNKNOWN }
    }
    override suspend fun allocate(record: VisibilityMoveRecord): String = withContext(Dispatchers.IO) {
        val original = origin(record)
        if (record.direction == VisibilityDirection.HIDE) Uri.fromFile(store().target(record.destinationToken,original.name)).toString()
        else media.allocateRestore(original,record.destinationToken)
    }
    override suspend fun copyAndSync(record: VisibilityMoveRecord) = withContext(Dispatchers.IO) {
        val original = origin(record)
        if (record.direction == VisibilityDirection.HIDE) {
            val file = hiddenFile(hiddenUri(record))
            check(file == store().target(record.destinationToken,original.name))
            store().copyVerified(file,record.fingerprint,record.sourceModifiedMillis) { media.openOriginal(original) }
        } else {
            val file = hiddenFile(record.sourceUri)
            media.restoreBytes(original,record.destinationToken,requireNotNull(record.destinationUri),record.fingerprint) { file.inputStream() }
        }
    }
    override suspend fun verifyDestination(record: VisibilityMoveRecord) = withContext(Dispatchers.IO) {
        val original = origin(record)
        if (record.direction == VisibilityDirection.HIDE) {
            val file = hiddenFile(hiddenUri(record)); check(file == store().target(record.destinationToken,original.name))
            store().verifyLocation(file,record.sourceModifiedMillis)
            media.verifyHiddenNotIndexed(file,original.mime)
        } else media.publishRestore(original,record.destinationToken,requireNotNull(record.destinationUri),record.fingerprint)
    }
    override suspend fun rebind(record: VisibilityMoveRecord) = bindings.rebind(record)
    override suspend fun verifyBinding(record: VisibilityMoveRecord) = bindings.verifyBinding(record)
    override suspend fun removeSource(record: VisibilityMoveRecord) {
        // The engine checks the exact source hash immediately before this call.
        bindings.verifyBinding(record); bindings.verifySourceUnreferenced(record)
        val original = origin(record)
        if (record.direction == VisibilityDirection.HIDE) {
            if (media.isOwnedOriginal(original)) media.removeOwnedOriginal(original,record.fingerprint)
            else check(withContext(Dispatchers.Main.immediate) { approveCleanup(media.cleanupRequest(original,record.fingerprint)) }) {
                "Android не подтвердил удаление исходного экземпляра; проверенная копия и журнал сохранены"
            }
        } else withContext(Dispatchers.IO) {
            val file = hiddenFile(record.sourceUri)
            check(HiddenFolderFileStore.fingerprint(file.inputStream()) == record.fingerprint)
            store().removeVerified(file)
        }
    }
    override suspend fun verifyFinalVisibility(record: VisibilityMoveRecord) {
        check(record.phase == VisibilityMovePhase.CLEANED)
        check(presence(record,record.sourceUri) == VisibilityPresence.MISSING)
        verifyDestination(record); bindings.verifyBinding(record); bindings.verifySourceUnreferenced(record)
        state.rememberCompleted(record)
        bindings.finishBinding(record)
    }
    override suspend fun preflight(batch: VisibilityBatchRecord) = withContext(Dispatchers.IO) {
        check(FolderVisibilityStorageAccess.granted()) { "Не выдан доступ к скрытому хранилищу" }
        bindings.batchContext(batch,preflight=true)
        if(batch.membership!=null) bindings.verifyMembershipIntent(batch,state,true)
        else check(state.isHidden(batch.folderId) == (batch.direction == VisibilityDirection.SHOW)) { "Режим папки уже изменился" }
        bindings.verifyMembership(batch)
        val root = store().ensureRoot()
        // The verified destination and original coexist; check actual free space
        // before the batch begins. No total-size allocation into RAM.
        val total = batch.members.fold(0L) { sum, member -> Math.addExact(sum,member.fingerprint.bytes) }
        if (batch.direction == VisibilityDirection.HIDE) check(root.usableSpace >= total) { "Недостаточно свободного места" }
        for (member in batch.members) {
            check(currentUri(member) == member.sourceUri && presence(member,member.sourceUri) == VisibilityPresence.PRESENT &&
                fingerprint(member,member.sourceUri) == member.fingerprint) { "Файл изменился до начала переноса" }
            val previous = state.receipt(member.downloadId)
            if (member.direction == VisibilityDirection.SHOW) check(previous?.direction == VisibilityDirection.HIDE &&
                previous.destinationUri == member.sourceUri && previous.publicOrigin == member.publicOrigin && previous.folderId == batch.folderId)
            else check(previous == null || previous.direction == VisibilityDirection.SHOW && previous.destinationUri == member.logicalSourceUri)
        }
    }
    override suspend fun prepareMembership(batch:VisibilityBatchRecord,pending:VisibilityMoveRecord?) {
        bindings.prepareMembership(batch,state,pending)
    }
    override suspend fun completed(member: VisibilityMoveRecord): Boolean {
        val receipt = state.receipt(member.downloadId)
        if (!VisibilityReceiptPolicy.confirms(receipt,member)) return false
        checkNotNull(receipt)
        check(currentUri(receipt) == receipt.destinationUri && presence(receipt,receipt.sourceUri) == VisibilityPresence.MISSING &&
            fingerprint(receipt,requireNotNull(receipt.destinationUri)) == receipt.fingerprint) { "Состояние ранее перенесённого файла изменилось" }
        verifyDestination(receipt); bindings.verifyBinding(receipt); bindings.verifySourceUnreferenced(receipt)
        return true
    }
    override suspend fun commitVisibility(batch: VisibilityBatchRecord) {
        bindings.verifyMembership(batch)
        if(batch.membership!=null) bindings.commitMembership(batch,state)
        else state.commitFolder(batch,bindings.externalMemberIds(batch.folderId))
    }
}
