package com.domenota.medialoader.data.catalog.visibility

import android.content.Context
import com.domenota.medialoader.core.database.HistoryDatabase
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.data.library.PlaylistRepository
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
    private val inspector=SelectiveArchiveSourceInspector(AndroidSelectiveArchiveMediaProbe(app))
    private val checkpoints=SelectiveArchiveCheckpointStore(
        File(app.noBackupFilesDir,"selective-archive-checkpoints-v1"))
    private val journal=SelectiveArchiveMoveJournal(
        File(app.noBackupFilesDir,"selective-archive-phases-v1"))
    private val target=SelectiveArchiveLocalTarget()
    private val engine=SelectiveArchiveMoveEngine(journal,
        AndroidSelectiveArchiveMovePort(app,{original->references.sha256(original)},target))

    suspend fun hide(plan:NomediaAutoFolderPlan):List<SelectiveArchiveMoveReceipt> =
        mutex.withLock {
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
                    for(original in cp.originals.asReversed()) {
                        val record=try { journal.read(cp.folderId,original.catalogId) }
                            catch(_:Exception) {
                                incomplete=true // Unknown receipt is not a confirmed rollback.
                                continue
                            } ?: continue
                        if(record.phase in setOf(SelectiveArchiveMovePhase.PREPARED,
                            SelectiveArchiveMovePhase.HIDE_INTENT,
                            SelectiveArchiveMovePhase.HIDDEN_VERIFIED,
                            SelectiveArchiveMovePhase.RESTORE_INTENT)) {
                            try {
                                val restored=engine.restore(cp,original)
                                if(restored.phase==SelectiveArchiveMovePhase.RESTORED_VERIFIED)
                                    runCatching { target.releaseEmpty(original,restored) }
                            } catch(_:Exception) { incomplete=true }
                        }
                    }
                    throw IOException(if(incomplete)
                        "Выборочное скрытие не завершено. Часть файлов требует проверки журнала"
                    else "Выборочное скрытие отменено, исходные файлы проверены",
                        error)
                }
                completed
            }
        }

    suspend fun restore(folderId:String):List<SelectiveArchiveMoveReceipt> =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val checkpoint=checkNotNull(checkpoints.read(folderId)) {
                    "Сохранённая точка выборочного архива не найдена"
                }
                // Validate COMPLETE journal coverage before restoring any file.
                // A missing member receipt is an unknown batch state, never a
                // successful partial restore.
                val receipts=checkpoint.originals.map { original ->
                    checkNotNull(journal.read(folderId,original.catalogId)) {
                        "Нет записи журнала для выбранного файла. Восстановление всей подборки остановлено"
                    }
                }
                check(receipts.size==checkpoint.originals.size &&
                    receipts.all { it.folderId==folderId }) {
                    "Неполный или изменённый журнал выборочного архива"
                }
                val result=mutableListOf<SelectiveArchiveMoveReceipt>()
                for(original in checkpoint.originals) {
                    val restored=engine.restore(checkpoint,original)
                    result+=restored
                    // Cleanup is best-effort only AFTER successful identity,
                    // bytes, mtime and public-reference restoration. A foreign
                    // entry is never touched; its diagnostic remains inspectable.
                    runCatching { target.releaseEmpty(original,restored) }
                }
                result
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
