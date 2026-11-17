package com.domenota.medialoader.data.catalog.visibility

import android.app.PendingIntent
import android.content.Context
import androidx.annotation.RequiresApi
import com.domenota.medialoader.core.database.HistoryDatabase
import com.domenota.medialoader.data.storage.HistoricalRecoverySignals
import com.domenota.medialoader.data.storage.OldDownloadsRecovery
import com.domenota.medialoader.data.storage.ensureHistoricalPlaybackIdle
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class FolderVisibilityProgress(val folderId: String?, val direction: VisibilityDirection?,
    val completed: Int, val total: Int, val pending: Boolean,
    val members: List<VisibilityMoveRecord> = emptyList(),
    val preparing: Boolean = false, val prepared: Int = 0, val failure: VisibilityFailureInfo? = null)

/** One explicit operation owner. No background deletion or implicit consent.
 * Keep this separate from the vault and from historical-date recovery. */
internal class FolderVisibilityManager(context: Context, private val database: HistoryDatabase) {
    private val context = context.applicationContext
    private val root = File(this.context.noBackupFilesDir,DIRECTORY)
    private val moveJournal = FileVisibilityMoveJournal(File(root,MOVE))
    private val batchJournal = FileVisibilityBatchJournal(File(root,BATCH))
    private val state = CatalogVisibilityState(database)
    private val bindings = FolderVisibilityCatalogBindings(this.context,database)
    private val gate = Mutex()
    private var lastFailure: VisibilityOperationFailure? = null
    private var currentStage = VisibilityOperationStage.JOURNAL
    private val progress = MutableStateFlow(FolderVisibilityProgress(null,null,0,0,hasPendingJournal(this.context)))
    val status = progress.asStateFlow()
    val snapshot = state.snapshot
    val hiddenFolderIds = state.hiddenFolderIds
    val receipts = state.receipts
    init { HistoricalRecoverySignals.markFolderVisibilityPending(progress.value.pending) }

    suspend fun pending(): FolderVisibilityProgress = gate.withLock {
        refreshPending()
        progress.value
    }
    private suspend fun refreshPending() {
        try {
            val batch = batchJournal.read()
            if (batch != null) publish(batch,moveJournal.read())
            else if (moveJournal.read() != null) unknownPending()
            else publish(null)
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { unknownPending() }
    }
    private fun unknownPending() {
        progress.value = FolderVisibilityProgress(null,null,0,0,true,failure=lastFailure?.info)
        HistoricalRecoverySignals.markFolderVisibilityPending(true)
    }
    private fun publish(batch: VisibilityBatchRecord?, pendingMove:VisibilityMoveRecord?=null) {
        progress.value = if (batch == null) FolderVisibilityProgress(null,null,0,0,false,failure=lastFailure?.info)
            else FolderVisibilityProgress(batch.folderId,batch.direction,batch.next,batch.members.size,true,batch.members + listOfNotNull(pendingMove),failure=lastFailure?.info)
        HistoricalRecoverySignals.markFolderVisibilityPending(batch != null)
    }
    suspend fun requiresMembershipMove(ref:com.domenota.medialoader.data.library.LibraryMediaRef,target:String?):Boolean = gate.withLock {
        val from=bindings.memberFolder(ref)
        from!=target && (from?.let { state.isHidden(it) }==true || target?.let { state.isHidden(it) }==true)
    }
    suspend fun isHidden(folder:String):Boolean = state.isHidden(folder)
    suspend fun requireVisibleMember(id:String) {
        check(state.receipt(id)?.direction!=VisibilityDirection.HIDE) { "Сначала верните видимость файла" }
    }
    @RequiresApi(30)
    suspend fun setHidden(folder: String, hidden: Boolean, approve: suspend (PendingIntent) -> Boolean) = gate.withLock {
        check(!hasPendingJournal(context)) { "Сначала завершите предыдущий перенос" }
        if (state.isHidden(folder) == hidden) return@withLock
        runOperation(approve) { batch ->
            val direction = if (hidden) VisibilityDirection.HIDE else VisibilityDirection.SHOW
            currentStage=VisibilityOperationStage.PLAN
            val plan = bindings.plan(folder,direction,state,VisibilityMediaStore(context)) { prepared,total ->
                progress.value=FolderVisibilityProgress(folder,direction,0,total,false,preparing=true,prepared=prepared)
            }
            batch.begin(plan)
        }
    }
    /** Returns false for a purely visible logical assignment. The caller can
     * use the existing catalog path only in that case. */
    @RequiresApi(30)
    suspend fun changeMember(ref:com.domenota.medialoader.data.library.LibraryMediaRef,target:String?,
        approve:suspend(PendingIntent)->Boolean):Boolean = gate.withLock {
        check(!hasPendingJournal(context)) { "Сначала завершите предыдущий перенос" }
        // Determine the actual mode under the operation gate, not from Compose.
        val from=bindings.memberFolder(ref)
        if(from==target || from?.let { state.isHidden(it) }!=true && target?.let { state.isHidden(it) }!=true) return@withLock false
        var changed=false
        runOperation(approve) { batch ->
            currentStage=VisibilityOperationStage.PLAN
            val plan=checkNotNull(bindings.planMembership(ref,target,state,VisibilityMediaStore(context)))
            batch.begin(plan); changed=true
        }
        changed
    }
    @RequiresApi(30)
    suspend fun resume(approve: suspend (PendingIntent) -> Boolean) = gate.withLock {
        check(hasPendingJournal(context)) { "Нет незавершённого переноса" }
        runOperation(approve) { it.resume() }
    }
    @RequiresApi(30)
    private suspend fun runOperation(approve: suspend (PendingIntent) -> Boolean, action: suspend (FolderVisibilityBatch) -> Unit) {
        lastFailure=null
        var running=false
        val attempt = com.domenota.medialoader.core.logging.AppLog.newOperationId("visibility")
        com.domenota.medialoader.core.logging.AppLog.i("FolderVisibility",
            "ATTEMPT_BEGIN id=$attempt pending=${hasPendingJournal(context)}")
        try {
            currentStage=VisibilityOperationStage.ACCESS
            check(FolderVisibilityStorageAccess.granted()) { "Разрешите управление всеми файлами" }
            currentStage=VisibilityOperationStage.PREFLIGHT
            check(!HistoricalRecoverySignals.hasPendingHistoricalJournal(context) && !OldDownloadsRecovery.pending(context)) {
                "Сначала завершите восстановление исторических файлов"
            }
            HistoricalRecoverySignals.beginFolderVisibility(); running=true
            ensureHistoricalPlaybackIdle(context)
            com.domenota.medialoader.data.storage.HistoricalReferenceLinks(context,database).ensureIdle()
            currentStage=VisibilityOperationStage.LEGACY
            VisibilityLegacyUpgrade(batchJournal,moveJournal) {
                bindings.enrichLegacy(it,VisibilityMediaStore(context))
            }.run()
            val androidPorts = AndroidFolderVisibilityPorts(context,state,bindings,approve)
            val ports = ReportingVisibilityPorts(androidPorts,androidPorts) { currentStage=it }
            val move = FolderVisibilityMove(moveJournal,ports)
            currentStage=VisibilityOperationStage.JOURNAL
            action(FolderVisibilityBatch(batchJournal,moveJournal,move,ports,{ publish(it) }))
        } catch(cancelled:kotlinx.coroutines.CancellationException) { throw cancelled }
        catch(error:Exception) {
            val failure=VisibilityOperationFailure.wrap(currentStage,error)
            lastFailure=failure
            val trace=failure.stackTrace.take(8).joinToString(" > ") { "${it.className}.${it.methodName}:${it.lineNumber}" }
            com.domenota.medialoader.core.logging.AppLog.w("FolderVisibility",
                "STOP id=$attempt code=${failure.info.code} stage=${failure.info.stage.name} type=${failure.originalType} confirmed=${progress.value.completed} total=${progress.value.total} trace=$trace")
            throw failure
        } finally {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                try { refreshPending() } finally { bindings.batchContext(null); if(running) HistoricalRecoverySignals.end() }
            }
        }
    }

    companion object {
        private const val DIRECTORY = "folder-visibility-v1"
        private const val MOVE = "move.bin"
        private const val BATCH = "batch.bin"
        /** Cheap fail-closed check used before automatic catalog alias updates.
         * A nonempty/corrupt journal is pending even after process death. */
        fun hasPendingJournal(context: Context): Boolean {
            val root = File(context.noBackupFilesDir,DIRECTORY)
            return VisibilityPendingJournal.pending(File(root,MOVE)) { VisibilityJournalCodec.decode(it) == null } ||
                VisibilityPendingJournal.pending(File(root,BATCH)) { VisibilityBatchCodec.decode(it) == null }
        }
    }
}
