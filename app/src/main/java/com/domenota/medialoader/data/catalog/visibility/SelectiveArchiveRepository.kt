package com.domenota.medialoader.data.catalog.visibility

import android.content.Context
import com.domenota.medialoader.core.database.HistoryDatabase
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.data.library.PlaylistRepository
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Complete proof of a partially interrupted batch's restoration. */
internal data class SelectiveArchiveBatchRestoreResult(
    val restored:List<SelectiveArchiveMoveReceipt>,
    val unchangedPublicIds:Set<String>,
    val expectedCount:Int,
) {
    init {
        require(expectedCount>0 && restored.size+unchangedPublicIds.size==expectedCount)
        require(restored.all { it.phase==SelectiveArchiveMovePhase.RESTORED_VERIFIED })
        require(restored.map { it.catalogId }.distinct().size==restored.size)
        require(restored.none { it.catalogId in unchangedPublicIds })
    }
}

/** A read-only, preliminary QA inventory. The real mover must inspect again. */
internal data class SelectiveArchiveQaPreview(
    val folderId:String,
    val selectedCount:Int,
    val physicalCount:Int,
    val treeUri:String,
) {
    init { require(folderId.isNotBlank() && selectedCount in 1..12 && physicalCount>selectedCount) }
}

/** Durable recovery UI must not assume a hidden entry is safe to restore. */
internal data class SelectiveArchiveQaEntry(
    val folderId:String,
    val total:Int,
    val hidden:Int,
    val needsReview:Boolean,
    val restored:Boolean,
)

/**
 * Stage 3 selective archive backend. The caller supplies a user-selected
 * physical-folder plan. Never converts a virtual collection into a parent
 * .nomedia and never calls RetiredFolderVisibilityTransfer.
 *
 * Integration with ML's public index and the final UI is a separate guarded
 * step: do not expose a user action before archive privacy gating is wired.
 */
internal class SelectiveArchiveRepository(
    context:Context,
    private val db:HistoryDatabase,
) {
    private val app=context.applicationContext
    private val mutex=Mutex()
    private val playlists=PlaylistRepository(app)
    private val references=SelectiveArchiveReferenceGuard(db,playlists)
    private val mediaProbe=AndroidSelectiveArchiveMediaProbe(app)
    private val inspector=SelectiveArchiveSourceInspector(mediaProbe)
    private val unjournaled=SelectiveArchiveUnjournaledProof(mediaProbe,references::sha256)
    private val checkpoints=SelectiveArchiveCheckpointStore(
        File(app.noBackupFilesDir,"selective-archive-checkpoints-v1"))
    private val journal=SelectiveArchiveMoveJournal(
        File(app.noBackupFilesDir,"selective-archive-phases-v1"))
    private val target=SelectiveArchiveLocalTarget()
    private val engine=SelectiveArchiveMoveEngine(journal,
        AndroidSelectiveArchiveMovePort(app,{original->references.sha256(original)},target))

    // Public Gallery/Music must remain CLOSED while the durable archive
    // receipts are unexamined, unreadable, or a MediaStore mutation is active.
    private val privacyState=MutableStateFlow(SelectiveArchivePrivacySnapshot())
    val privacy=privacyState.asStateFlow()

    suspend fun refreshPrivacy():SelectiveArchivePrivacySnapshot = mutex.withLock {
        refreshPrivacyLocked()
    }

    private suspend fun refreshPrivacyLocked():SelectiveArchivePrivacySnapshot =
        withContext(Dispatchers.IO) {
            val verified=runCatching {
                val saved=checkpoints.listAll()
                val expectedKeys=saved.flatMap { checkpoint ->
                    checkpoint.originals.map { journal.memberKey(checkpoint.folderId,it.catalogId) }
                }.toSet()
                val durableKeys=journal.durableKeys()
                // Missing or orphaned mutation phases are not harmless.
                // Orphans mean a checkpoint may have disappeared after a move.
                check(durableKeys.all(expectedKeys::contains)) {
                    "Существует журнал Архива без подтверждённой контрольной точки"
                }
                val entries=saved.flatMap { checkpoint ->
                    checkpoint.originals.map { original ->
                        original to journal.read(checkpoint.folderId,original.catalogId)?.phase
                    }
                }
                archivePrivacyProjection(entries)
            }.getOrElse { SelectiveArchivePrivacySnapshot() }
            privacyState.value=verified
            verified
        }



    /** QA-only read-only preflight. No system file is modified here. User sees
     * the precise 6 of 806 difference BEFORE consenting to test copies.
     * The full identity + references + provider verification happens again
     * in hide() after user approval. */
    suspend fun previewForQa(plan:NomediaAutoFolderPlan):SelectiveArchiveQaPreview =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                check(FolderVisibilityStorageAccess.granted()) {
                    "Для проверки нужен разрешённый Android доступ к файлам"
                }
                check(plan.expected.size in 1..12) {
                    "Первый тестовый запуск допускает от 1 до 12 файлов"
                }
                check(refreshPrivacyLocked().verified) {
                    "Журнал Архива требует проверки. Новые операции запрещены"
                }
                val actual=ManagedLocalNomediaPort().listMedia(plan.treeUri.toString())
                    .map { NomediaMediaSignature(it.displayName,it.byteCount ?: -1L) }
                val verdict=archiveInventoryVerdict(plan.expected,actual)
                check(verdict is ArchiveInventoryVerdict.RequiresSelectiveArchive) {
                    "Нет подтверждённой выборки из общей физической папки"
                }
                SelectiveArchiveQaPreview(plan.folderId,verdict.selected,
                    verdict.physical,plan.treeUri.toString())
            }
        }

    /** Recovery entries are read from the durable archive journal, not from
     * the gallery's currently visible rows. Hidden rows can disappear from
     * MediaStore's public typed collection and still need a Restore button. */
    suspend fun qaRecoveryEntries():List<SelectiveArchiveQaEntry> = mutex.withLock {
        withContext(Dispatchers.IO) {
            check(refreshPrivacyLocked().verified) {
                "Архив недоступен: журнал повреждён или контрольные точки не подтверждены"
            }
            checkpoints.listAll().map { cp ->
                val phases=cp.originals.map { journal.read(cp.folderId,it.catalogId)?.phase }
                SelectiveArchiveQaEntry(cp.folderId,cp.originals.size,
                    phases.count { it==SelectiveArchiveMovePhase.HIDDEN_VERIFIED },
                    phases.any { it==SelectiveArchiveMovePhase.NEEDS_REVIEW },
                    phases.all { it==SelectiveArchiveMovePhase.RESTORED_VERIFIED })
            }
        }
    }

    suspend fun hide(plan:NomediaAutoFolderPlan):List<SelectiveArchiveMoveReceipt> =
        mutex.withLock {
            privacyState.value=SelectiveArchivePrivacySnapshot()
            try {
                withContext(Dispatchers.IO) {
                check(FolderVisibilityStorageAccess.granted()) {
                    "Для выборочного архива нужен доступ к файлам Android"
                }
                val cp=checkpoints.read(plan.folderId) ?: prepare(plan)
                // Never replay a saved plan for a collection whose membership
                // or selected physical originals have changed in the meantime.
                val assigned=db.catalogDao().memberIds(plan.folderId)
                check(selectiveArchivePlanMatches(cp,plan.folderId,assigned,plan.expected) &&
                    cp.originals.all { nomediaSuggestedTree(it.volume,it.relativePath)==plan.treeUri }) {
                    "Выбранная коллекция изменилась после создания контрольной точки. Перенос остановлен"
                }
                val completed=mutableListOf<SelectiveArchiveMoveReceipt>()
                try {
                    for(original in cp.originals) {
                        val old=journal.read(cp.folderId,original.catalogId)
                        val token=old?.token ?: UUID.randomUUID().toString().replace("-","")
                        val relative=target.relative(original,token)
                        completed+=engine.hide(cp,original,token,relative)
                    }
                } catch(error:Exception) {
                    // Best-effort rollback is restricted to exact same-ID
                    // observations, never delete or reinsert. Unknown records
                    // stay durable for a later explicit recovery.
                    var incomplete=false
                    val verifiedRestored=mutableSetOf<String>()
                    val verifiedUntouched=mutableSetOf<String>()
                    for(original in cp.originals.asReversed()) {
                        val record=try { journal.read(cp.folderId,original.catalogId) }
                            catch(_:Exception) {
                                incomplete=true // Corrupt/unknown journal: never assume original is safe.
                                continue
                            }
                        if(record==null) {
                            // A batch interruption can precede this member's
                            // PREPARED receipt. No action was authorized for it,
                            // but check actual identity, bytes, date and links.
                            val untouched=try { unjournaled.publicUnchanged(original) }
                                catch(_:Exception) { false }
                            if(untouched) verifiedUntouched+=original.catalogId
                            else incomplete=true
                            continue
                        }
                        if(record.phase==SelectiveArchiveMovePhase.NEEDS_REVIEW) {
                            incomplete=true
                            continue
                        }
                        try {
                            // RESTORED_VERIFIED must also be independently checked:
                            // a durable phase is not proof the file stayed intact.
                            val restored=engine.restore(cp,original)
                            if(restored.phase==SelectiveArchiveMovePhase.RESTORED_VERIFIED) {
                                verifiedRestored+=original.catalogId
                                if(runCatching { target.releaseEmpty(original,restored) }
                                    .getOrNull()!=true) incomplete=true
                            } else incomplete=true
                        } catch(_:Exception) { incomplete=true }
                    }
                    if(!selectiveArchiveRollbackComplete(cp.originals,
                            verifiedRestored,verifiedUntouched)) incomplete=true
                    throw IOException(if(incomplete)
                        "Выборочное скрытие не завершено. Часть файлов требует проверки журнала"
                    else "Выборочное скрытие отменено, исходные файлы проверены",
                        error)
                }
                completed
                }
            } finally {
                withContext(NonCancellable) { refreshPrivacyLocked() }
            }
        }

    suspend fun restore(folderId:String):SelectiveArchiveBatchRestoreResult =
        mutex.withLock {
            privacyState.value=SelectiveArchivePrivacySnapshot()
            try {
                withContext(Dispatchers.IO) {
                val checkpoint=checkNotNull(checkpoints.read(folderId)) {
                    "Сохранённая точка выборочного архива не найдена"
                }
                // Restore every journaled member, but an interruption may occur
                // before later members have any receipt. Independently prove
                // those originals STILL PUBLIC before changing any other file.
                val receipts=checkpoint.originals.mapNotNull { original ->
                    journal.read(folderId,original.catalogId)?.let { original.catalogId to it }
                }.toMap()
                val intact=mutableSetOf<String>()
                for(original in checkpoint.originals) {
                    if(original.catalogId !in receipts) {
                        check(unjournaled.publicUnchanged(original)) {
                            "Нет журнала и точного доказательства доступности исходного файла"
                        }
                        intact+=original.catalogId
                    }
                }
                check(selectiveArchiveRecoveryCoverage(checkpoint.originals,receipts,intact)) {
                    "Журнал или состав выборочного архива не подтверждены"
                }
                val result=mutableListOf<SelectiveArchiveMoveReceipt>()
                for(original in checkpoint.originals) {
                    if(original.catalogId in intact) continue // Independently proven untouched.
                    val restored=engine.restore(checkpoint,original)
                    result+=restored
                    // Cleanup is best-effort only AFTER successful identity,
                    // bytes, mtime and public-reference restoration. A foreign
                    // entry is never touched; its diagnostic remains inspectable.
                    runCatching { target.releaseEmpty(original,restored) }
                }
                SelectiveArchiveBatchRestoreResult(result,intact,checkpoint.originals.size)
                }
            } finally {
                withContext(NonCancellable) { refreshPrivacyLocked() }
            }
        }

    /** Read-only privacy state for the public index. In-flight and unknown
     * operations must stay blocked, even after process death. */
    suspend fun blockedCatalogIds(folderIds:Collection<String>):Set<String> =
        withContext(Dispatchers.IO) {
            val blocked=mutableSetOf<String>()
            for(folderId in folderIds.distinct()) {
                val cp=checkpoints.read(folderId) ?: continue
                for(original in cp.originals) {
                    val receipt=journal.read(folderId,original.catalogId)
                    if(receipt?.phase in setOf(SelectiveArchiveMovePhase.HIDE_INTENT,
                        SelectiveArchiveMovePhase.HIDDEN_VERIFIED,
                        SelectiveArchiveMovePhase.RESTORE_INTENT,
                        SelectiveArchiveMovePhase.NEEDS_REVIEW)) blocked+=original.catalogId
                }
            }
            blocked
        }

    private suspend fun prepare(plan:NomediaAutoFolderPlan):SelectiveArchiveCheckpoint {
        check(FolderVisibilityStorageAccess.granted())
        val ids=db.catalogDao().memberIds(plan.folderId).toSet()
        check(ids.isNotEmpty() && ids.size==plan.expected.size)
        val rows=db.downloadDao().all().filter { it.id in ids }.sortedBy { it.id }
        check(rows.size==ids.size && rows.all {
            it.state==DownloadState.COMPLETED && !it.hidden && !it.isPrivate &&
                !it.savedUri.isNullOrBlank()
        }) { "Состав выбранной коллекции изменён" }
        val physical=ManagedLocalNomediaPort().listMedia(plan.treeUri.toString())
            .map { NomediaMediaSignature(it.displayName,it.byteCount ?: -1L) }
        check(archiveInventoryVerdict(plan.expected,physical) is
            ArchiveInventoryVerdict.RequiresSelectiveArchive) {
            "Нет строго проверенной выборки из общей физической папки"
        }
        val members=rows.map { row ->
            val uri=requireNotNull(row.savedUri)
            val ref=checkNotNull(references.sha256(row.id,uri,row.sizeBytes)) {
                "История или связи выбранного файла не подтверждены"
            }
            SelectiveArchiveCatalogMember(row.id,uri,ref)
        }
        val originals=inspector.inspect(members,plan.expected,physical)
        check(originals.map { it.catalogId }.toSet()==ids &&
            originals.all { nomediaSuggestedTree(it.volume,it.relativePath)==plan.treeUri }) {
            "Выбранный файл находится вне подтверждённого физического каталога"
        }
        return checkpoints.prepare(plan.folderId,originals,plan.expected,physical)
    }
}
