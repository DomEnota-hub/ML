package com.domenota.medialoader.data.catalog.visibility

import android.app.PendingIntent
import android.content.Context
import androidx.annotation.RequiresApi
import com.domenota.medialoader.core.database.HistoryDatabase
import com.domenota.medialoader.data.storage.HistoricalRecoverySignals
import com.domenota.medialoader.data.storage.OldDownloadsRecovery
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
    val preparing: Boolean = false, val prepared: Int = 0, val failure: VisibilityFailureInfo? = null,
    val pausedAtCheckpoint: Boolean = false)

/** One explicit operation owner. No background deletion or implicit consent.
 * Keep this separate from the vault and from historical-date recovery. */
internal class FolderVisibilityManager(context: Context, private val database: HistoryDatabase) {
    private val context = context.applicationContext
    private val root = File(this.context.noBackupFilesDir,DIRECTORY)
    private val moveJournal = FileVisibilityMoveJournal(File(root,MOVE))
    private val batchJournal = FileVisibilityBatchJournal(File(root,BATCH))
    private val v2Journals = VisibilityV2JournalStore(File(root,V2_JOURNAL))
    private val state = CatalogVisibilityState(database)
    private val bindings = FolderVisibilityCatalogBindings(this.context,database)
    private val gate = Mutex()
    private val pauseGate = VisibilityCheckpointPauseGate()
    private val operationActive = java.util.concurrent.atomic.AtomicBoolean(false)
    private var pausedByUser = false
    private var preparedPreview: VisibilityPreflightSnapshot? = null
    /** Cooperative request; processing stops only after a durable member cursor.
     * False means no operation is in progress, not that existing journals are gone. */
    fun requestPauseAtCheckpoint(): Boolean = operationActive.get() && pauseGate.request()
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
            val v1Pending=legacyPending(context)
            val v2Pending=VisibilityV2JournalStore.pending(File(root,V2_JOURNAL))
            check(!(v1Pending && v2Pending)) { "Конфликт журналов v1 и v2" }
            if(v2Pending) {
                val state=v2Journals.snapshot()
                VisibilityRecoveryPlanner.inspect(state.batch,state.move)
                if(state.batch != null) publish(state.batch,state.move) else unknownPending()
            } else {
                val batch = batchJournal.read()
                if (batch != null) publish(batch,moveJournal.read())
                else if (moveJournal.read() != null) unknownPending()
                else publish(null)
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { unknownPending() }
    }
    private fun unknownPending() {
        progress.value = FolderVisibilityProgress(null,null,0,0,true,failure=lastFailure?.info,
            pausedAtCheckpoint=pausedByUser)
        HistoricalRecoverySignals.markFolderVisibilityPending(true)
    }
    private fun publish(batch: VisibilityBatchRecord?, pendingMove:VisibilityMoveRecord?=null) {
        progress.value = if (batch == null) FolderVisibilityProgress(null,null,0,0,false,failure=lastFailure?.info)
            else FolderVisibilityProgress(batch.folderId,batch.direction,batch.next,batch.members.size,true,
                batch.members + listOfNotNull(pendingMove),failure=lastFailure?.info,
                pausedAtCheckpoint=pausedByUser)
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
    /** Preflight runs entirely before a foreground service or recovery gate.
     * The exact source snapshot remains process-local until explicit consent. */
    @RequiresApi(30)
    suspend fun preview(folder: String, hidden: Boolean): VisibilityPreflightSummary = gate.withLock {
        check(!operationActive.get() && !hasPendingJournal(context)) { "Сначала завершите предыдущий перенос" }
        check(state.isHidden(folder) != hidden) { "Видимость папки уже изменена" }
        preparedPreview = null
        val direction = if (hidden) VisibilityDirection.HIDE else VisibilityDirection.SHOW
        val snapshot = bindings.preview(folder,direction,state,VisibilityMediaStore(context))
        preparedPreview = snapshot.takeIf { it.summary.canStart }
        com.domenota.medialoader.core.logging.AppLog.i("FolderVisibility",
            "PREFLIGHT_V2 ready=${snapshot.summary.ready} total=${snapshot.summary.total} " +
                "problems=${snapshot.summary.problems} journalCreated=false")
        snapshot.summary
    }

    @RequiresApi(30)
    suspend fun setHidden(folder: String, hidden: Boolean, approve: suspend (PendingIntent) -> Boolean,
        previewToken: String? = null) = gate.withLock {
        check(!hasPendingJournal(context)) { "Сначала завершите предыдущий перенос" }
        val frozen = preparedPreview
        preparedPreview = null // one-shot, including invalid attempts
        check(frozen != null && frozen.summary.token == previewToken &&
            frozen.summary.folderId == folder && frozen.summary.hidden == hidden &&
            frozen.summary.canStart) { "Предварительная проверка устарела. Повторите проверку папки" }
        check(state.isHidden(folder) != hidden) { "Видимость папки уже изменена" }
        val direction = if (hidden) VisibilityDirection.HIDE else VisibilityDirection.SHOW
        // A second complete read-only plan must still match the approved
        // immutable source evidence; no journal/global refresh before this.
        val plan = bindings.plan(folder,direction,state,VisibilityMediaStore(context))
        check(visibilityPreflightMatches(frozen.verifiedBatch,plan)) {
            "Состав или содержимое папки изменилось. Повторите проверку"
        }
        runOperation(approve,newV2=true) { batch ->
            currentStage=VisibilityOperationStage.PREFLIGHT
            HistoricalRecoverySignals.markFolderMediaTypes(
                plan.members.mapNotNull { it.publicOrigin?.mime }
                    .takeIf { it.size == plan.members.size } ?: emptyList())
            com.domenota.medialoader.data.playback.ensureFolderVisibilityPlaybackSafe(context,plan.members)
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
        runOperation(approve,newV2=true) { batch ->
            currentStage=VisibilityOperationStage.PLAN
            val plan=checkNotNull(bindings.planMembership(ref,target,state,VisibilityMediaStore(context)))
            currentStage=VisibilityOperationStage.PREFLIGHT
            HistoricalRecoverySignals.markFolderMediaTypes(
                plan.members.mapNotNull { it.publicOrigin?.mime }
                    .takeIf { it.size == plan.members.size } ?: emptyList())
            com.domenota.medialoader.data.playback.ensureFolderVisibilityPlaybackSafe(context,plan.members)
            batch.begin(plan); changed=true
        }
        changed
    }
    @RequiresApi(30)
    suspend fun resume(approve: suspend (PendingIntent) -> Boolean) = gate.withLock {
        check(hasPendingJournal(context)) { "Нет незавершённого переноса" }
        runOperation(approve) { batch ->
            currentStage=VisibilityOperationStage.PREFLIGHT
            val v2Pending=VisibilityV2JournalStore.pending(File(root,V2_JOURNAL))
            val plan=checkNotNull((if(v2Pending) v2Journals.batches else batchJournal).read()) {
                "Нет точного плана переноса"
            }
            HistoricalRecoverySignals.markFolderMediaTypes(
                plan.members.mapNotNull { it.publicOrigin?.mime }
                    .takeIf { it.size == plan.members.size } ?: emptyList())
            com.domenota.medialoader.data.playback.ensureFolderVisibilityPlaybackSafe(context,plan.members)
            batch.resume()
        }
    }
    @RequiresApi(30)
    private suspend fun runOperation(approve: suspend (PendingIntent) -> Boolean,
        newV2:Boolean=false, action: suspend (FolderVisibilityBatch) -> Unit) {
        lastFailure=null
        pausedByUser=false
        pauseGate.reset()
        var running=false
        var skipUnchangedProofInvalidation = false
        var noInvalidationReason: String? = null
        val hadPendingJournalAtStart = hasPendingJournal(context)
        val v1PendingAtStart = legacyPending(context)
        val v2PendingAtStart = VisibilityV2JournalStore.pending(File(root,V2_JOURNAL))
        val family=visibilityJournalFamily(newV2,v1PendingAtStart,v2PendingAtStart)
        val useV2=family==VisibilityJournalFamily.VERSIONED_V2
        val activeBatch:VisibilityBatchJournal=if(useV2) v2Journals.batches else batchJournal
        val activeMove:VisibilityMoveJournal=if(useV2) v2Journals.moves else moveJournal
        // Family conflict/unknown journals must fail before claiming the in-process
        // operation flag. Otherwise a rejected run could leave it stuck as active.
        check(operationActive.compareAndSet(false,true)) { "Перенос уже выполняется" }
        val attempt = com.domenota.medialoader.core.logging.AppLog.newOperationId("visibility")
        com.domenota.medialoader.core.logging.AppLog.i("FolderVisibility",
            "ATTEMPT_BEGIN id=$attempt pending=${hasPendingJournal(context)} journal=${family.name}")
        try {
            currentStage=VisibilityOperationStage.ACCESS
            check(FolderVisibilityStorageAccess.granted()) { "Разрешите управление всеми файлами" }
            currentStage=VisibilityOperationStage.PREFLIGHT
            check(!HistoricalRecoverySignals.hasPendingHistoricalJournal(context) && !OldDownloadsRecovery.pending(context)) {
                "Сначала завершите восстановление исторических файлов"
            }
            // Existing interrupted batches may be replayed only under the exact
            // owner-root. Reject an invalid .nomedia *before* recovery enters its
            // global cache/scanner gate. No user media or durable journal changes.
            if (hasPendingJournal(context)) {
                currentStage=VisibilityOperationStage.JOURNAL
                // Read-only compatibility preflight, including a 1.7.203 batch
                // that crashed after the batch-first legacy metadata upgrade.
                // Reject orphan/corrupt/wrong-member plans BEFORE entering the
                // expensive global media-index refresh gate.
                val checkpoint = VisibilityRecoveryPlanner.inspect(
                    activeBatch.read(), activeMove.read(), allowLegacyUpgrade = !useV2)
                checkNotNull(checkpoint)
                com.domenota.medialoader.core.logging.AppLog.i("FolderVisibility",
                    "RECOVERY_PLAN verified=true direction=${checkpoint.direction} " +
                        "confirmed=${checkpoint.confirmed}/${checkpoint.total} " +
                        "phase=${checkpoint.active?.phase ?: "BETWEEN_FILES"}")
                currentStage=VisibilityOperationStage.DESTINATION
                val marker = withContext(Dispatchers.IO) {
                    FolderVisibilityStorageAccess.store().verifyExistingRoot()
                }
                com.domenota.medialoader.core.logging.AppLog.i("FolderVisibility",
                    "ROOT_PREFLIGHT code=${marker.name} pending=true ownerVerified=true")
            }
            HistoricalRecoverySignals.beginFolderVisibility(); running=true
            com.domenota.medialoader.data.storage.HistoricalReferenceLinks(context,database).ensureIdle()
            currentStage=VisibilityOperationStage.LEGACY
            if(!useV2) VisibilityLegacyUpgrade(batchJournal,moveJournal) {
                bindings.enrichLegacy(it,VisibilityMediaStore(context))
            }.run()
            val androidPorts = AndroidFolderVisibilityPorts(context,state,bindings,approve)
            val ports = ReportingVisibilityPorts(androidPorts,androidPorts) { currentStage=it }
            val move = FolderVisibilityMove(activeMove,ports)
            currentStage=VisibilityOperationStage.JOURNAL
            action(FolderVisibilityBatch(activeBatch,activeMove,move,ports,{ publish(it) },
                { pauseGate.checkpoint() }))
        } catch(paused: VisibilityPausedAtCheckpoint) {
            pausedByUser = true
            com.domenota.medialoader.core.logging.AppLog.i("FolderVisibility",
                "PAUSE_CHECKPOINT id=$attempt confirmed=${progress.value.completed} total=${progress.value.total} journalDurable=true")
        } catch(cancelled:kotlinx.coroutines.CancellationException) { throw cancelled }
        catch(error:Exception) {
            val failure=VisibilityOperationFailure.wrap(currentStage,error)
            lastFailure=failure
            // A proof-store guard fails before rebind's first catalog CAS.
            // Only a confirmed first-member VERIFIED phase with no prior batch
            // checkpoint may skip global catalog invalidation. Other failures
            // conservatively refresh, including any possible partial rebind.
            if (failure.proofFailedBeforeCatalogMutation && progress.value.completed == 0) {
                skipUnchangedProofInvalidation = try {
                    activeBatch.read()?.next == 0 &&
                        activeMove.read()?.phase == VisibilityMovePhase.VERIFIED
                } catch (_: Exception) { false }
                if (skipUnchangedProofInvalidation) noInvalidationReason = "PROOF_PRE_CAS_UNCHANGED"
            }
            // A failed read-only plan with zero durable journals has not
            // changed the catalog; do not advance Gallery/Music generation.
            if (failure.info.stage == VisibilityOperationStage.PLAN && !hadPendingJournalAtStart) {
                val journalsEmpty = try {
                    !hasPendingJournal(context) && activeBatch.read() == null && activeMove.read() == null
                } catch (_: Exception) { false }
                if (canSkipVisibilityPlanFailureRefresh(failure.info.stage,
                        hadPendingJournalAtStart, journalsEmpty)) {
                    skipUnchangedProofInvalidation = true
                    noInvalidationReason = "PLAN_FAILED_NO_MUTATION"
                }
            }
            val trace=failure.stackTrace.take(8).joinToString(" > ") { "${it.className}.${it.methodName}:${it.lineNumber}" }
            com.domenota.medialoader.core.logging.AppLog.w("FolderVisibility",
                "STOP id=$attempt code=${failure.info.code} stage=${failure.info.stage.name} type=${failure.originalType} confirmed=${progress.value.completed} total=${progress.value.total} catalogUnchanged=$skipUnchangedProofInvalidation reason=${noInvalidationReason ?: "CONSERVATIVE"} trace=$trace")
            throw failure
        } finally {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                try { refreshPending() } finally {
                    try {
                        bindings.batchContext(null)
                        if (running) {
                            if (skipUnchangedProofInvalidation) com.domenota.medialoader.core.logging.AppLog.i(
                                "FolderVisibility", "RECOVERY_SCAN_DECISION force=false reason=${noInvalidationReason ?: "NO_MUTATION"} invalidates=NONE")
                            HistoricalRecoverySignals.end(changed = !skipUnchangedProofInvalidation)
                        }
                    } finally {
                        // A diagnostic/refresh error must not leave the process
                        // permanently marked active or a stale pause armed.
                        operationActive.set(false)
                        pauseGate.reset()
                    }
                }
            }
        }
    }

    companion object {
        private const val DIRECTORY = "folder-visibility-v1"
        private const val MOVE = "move.bin"
        private const val BATCH = "batch.bin"
        private const val V2_JOURNAL = "checkpoint-v2.bin"
        private fun legacyPending(context:Context):Boolean {
            val root=File(context.noBackupFilesDir,DIRECTORY)
            return VisibilityPendingJournal.pending(File(root,MOVE)) {VisibilityJournalCodec.decode(it)==null} ||
                VisibilityPendingJournal.pending(File(root,BATCH)) {VisibilityBatchCodec.decode(it)==null}
        }
        /** Cheap fail-closed check used before automatic catalog alias updates.
         * A nonempty/corrupt journal is pending even after process death. */
        fun hasPendingJournal(context: Context): Boolean {
            val root = File(context.noBackupFilesDir,DIRECTORY)
            return legacyPending(context) || VisibilityV2JournalStore.pending(File(root,V2_JOURNAL))
        }

        /** Read-only exact reserved-name proof for independent Private exports.
         * A stalled SHOW may not yet have a public row, so querying MediaStore
         * alone is insufficient to prevent stealing its original destination.
         * Corrupt, missing or legacy ambiguous plans fail closed. */
        internal suspend fun reservedPendingPublicNames(context: Context, relativePath: String): Set<String> {
            if (!hasPendingJournal(context)) return emptySet()
            check(relativePath.endsWith('/') && !relativePath.contains(".."))
            val root = File(context.noBackupFilesDir,DIRECTORY)
            val v1Pending=legacyPending(context)
            val v2Pending=VisibilityV2JournalStore.pending(File(root,V2_JOURNAL))
            check(!(v1Pending && v2Pending)) { "Конфликт журналов v1 и v2" }
            val journal:VisibilityBatchJournal=if(v2Pending)
                VisibilityV2JournalStore(File(root,V2_JOURNAL)).batches
                else FileVisibilityBatchJournal(File(root,BATCH))
            val plan = checkNotNull(journal.read()) {
                "Незавершённый перенос без проверенного плана"
            }
            check(plan.members.all { it.publicOrigin != null }) {
                "Неполный план переноса: проверка имён недоступна"
            }
            return plan.members.mapNotNull { member ->
                member.publicOrigin?.takeIf { it.relativePath == relativePath }?.name
            }.toSet()
        }
    }
}

/** Only a failed read-only PLAN with *two freshly verified empty* durable
 * journals may skip rescans; unknown or legacy state stays conservative. */
internal fun canSkipVisibilityPlanFailureRefresh(
    stage: VisibilityOperationStage, hadPendingJournalAtStart: Boolean, bothJournalsEmpty: Boolean,
): Boolean = stage == VisibilityOperationStage.PLAN && !hadPendingJournalAtStart && bothJournalsEmpty
