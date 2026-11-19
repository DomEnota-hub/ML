package com.domenota.medialoader.data.catalog.visibility

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.domenota.medialoader.core.logging.AppLog
import com.domenota.medialoader.data.MediaRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Application/process-scoped, one command owner for a distinct foreground
 * service. Never retains an Activity, screen coroutine or user media buffer.
 *
 * Android approval is exposed as a one-shot ticket. Only a visible Activity's
 * ActivityResultLauncher may claim and resolve it. The service cannot grant
 * itself permissions and will stop safely after rejection/timeout.
 *
 * Intent extras are untrusted: only a matching process-local reservation
 * authorizes a run. Cold-process starts never replay the durable move journal.
 */
internal class FolderVisibilityWorkCoordinator(
    private val context: Context, private val repository: MediaRepository,
) {
    private val commands = VisibilityWorkCommandSlot()
    private val execution = VisibilityExecutionStateMachine()
    private val approval = VisibilityUserApprovalGate<PendingIntent>()
    val state = execution.state
    val pendingAndroidApproval = approval.pending

    @RequiresApi(30)
    fun startFromVisibleActivity(folderId: String, hidden: Boolean, previewToken: String) =
        enqueue(VisibilityWorkCommand.SetHidden(folderId, hidden, previewToken))

    @RequiresApi(30)
    fun resumeFromVisibleActivity() = enqueue(VisibilityWorkCommand.ExplicitResume)

    /** This API is called only after the Activity has verified its permissions. */
    @RequiresApi(30)
    private fun enqueue(command: VisibilityWorkCommand): Boolean {
        val journalPending = FolderVisibilityManager.hasPendingJournal(context)
        if (command is VisibilityWorkCommand.SetHidden) check(!journalPending) {
            "Сначала завершите предыдущий перенос"
        } else check(journalPending) { "Нет сохранённого переноса" }
        val reservation = commands.offer(command)
        try {
            execution.restoreFromJournal(journalPending)
            if (journalPending) execution.beginExplicitResume() else execution.startNew()
            val intent = Intent(context, FolderVisibilityService::class.java).apply {
                action = FolderVisibilityService.ACTION_RUN_REQUEST
                putExtra(FolderVisibilityService.EXTRA_REQUEST_ID, reservation.id)
            }
            ContextCompat.startForegroundService(context, intent)
            AppLog.i("FolderVisibility", "FOREGROUND_REQUEST accepted=true")
            return true
        } catch (error: Exception) {
            commands.cancelUnclaimed(reservation.id)
            if (execution.state.value.active) execution.failed("FV_FGS_START_REJECTED",journalPending)
            AppLog.w("FolderVisibility", "FOREGROUND_REQUEST accepted=false reason=${error.javaClass.simpleName}")
            throw error
        }
    }

    /** A foreground-start rejection must not leave a queued command/false
     * PREPARING state permanently resident in the process. */
    fun rejectUnclaimedForegroundStart(id: Long) {
        if (commands.cancelUnclaimed(id) && execution.state.value.active)
            execution.failed("FV_FGS_START_REJECTED",FolderVisibilityManager.hasPendingJournal(context))
    }

    /** Called only from the already-foreground Service. */
    fun claimServiceRequest(id: Long): VisibilityWorkReservation? = commands.claim(id)

    /** Request the existing manager to pause only at a durable batch cursor. */
    fun pauseAtNextCheckpoint(): Boolean {
        if (execution.state.value.phase !in setOf(
                VisibilityExecutionPhase.RUNNING,VisibilityExecutionPhase.PREPARING)) return false
        if (!repository.folderVisibility.requestPauseAtCheckpoint()) return false
        execution.requestPause()
        AppLog.i("FolderVisibility","PAUSE_REQUESTED safePoint=AFTER_BATCH_CAS")
        return true
    }

    private fun observe(p: FolderVisibilityProgress) {
        val s = execution.state.value
        if (s.phase == VisibilityExecutionPhase.PREPARING &&
            !p.preparing && p.total > 0) execution.running(p.completed,p.total)
        else if (s.phase == VisibilityExecutionPhase.RUNNING &&
            p.total > 0 && p.completed > s.confirmed) execution.checkpoint(p.completed,p.total)
    }

    private suspend fun userApproval(request: PendingIntent): Boolean {
        observe(repository.folderVisibility.status.value)
        if (execution.state.value.phase != VisibilityExecutionPhase.RUNNING &&
            execution.state.value.phase != VisibilityExecutionPhase.PAUSE_REQUESTED)
            throw IllegalStateException("Запрос Android вне активного переноса")
        execution.waitingForAndroid()
        AppLog.i("FolderVisibility","ANDROID_APPROVAL requested=true")
        val accepted = try { approval.request(request) }
            catch (_: TimeoutCancellationException) { false }
        execution.androidDecision(accepted)
        AppLog.i("FolderVisibility","ANDROID_APPROVAL approved=$accepted")
        return accepted
    }

    /** Does not catch Android cancellation as a successful move, and never
     * auto-resumes an interrupted journal on service restart. */
    @RequiresApi(30)
    suspend fun executeReserved(request: VisibilityWorkReservation) = coroutineScope {
        val manager = repository.folderVisibility
        val collector = launch {
            manager.status.collect { progress ->
                // Presentation observer failure must not cancel an in-flight
                // file cleanup or interrupt a journaled atomic transition.
                runCatching { observe(progress) }.onFailure {
                    AppLog.w("FolderVisibility","PROGRESS_OBSERVER_REJECTED reason=${it.javaClass.simpleName}")
                }
            }
        }
        try {
            when (val cmd = request.command) {
                is VisibilityWorkCommand.SetHidden ->
                    repository.setFolderVisibility(cmd.folderId,cmd.hidden,::userApproval,cmd.previewToken)
                VisibilityWorkCommand.ExplicitResume ->
                    repository.resumeFolderVisibility(::userApproval)
            }
            val after = manager.pending()
            if (after.pausedAtCheckpoint) {
                if (execution.state.value.phase == VisibilityExecutionPhase.PREPARING)
                    execution.running(after.completed,after.total)
                if (execution.state.value.phase == VisibilityExecutionPhase.RUNNING)
                    execution.requestPause()
                if (execution.state.value.phase == VisibilityExecutionPhase.PAUSE_REQUESTED)
                    execution.checkpoint(after.completed,after.total)
            } else if (after.pending) {
                if (execution.state.value.active) execution.failed("FV_PENDING_RECHECK",true)
            } else {
                val now = execution.state.value
                // Do not infer successful 46/46 from the disappearance of a
                // journal. Use the final confirmed manager cursor even when
                // StateFlow coalesced intermediate 1/46..46/46 events.
                if (after.completed != after.total) {
                    if (now.active) execution.failed("FV_PROGRESS_UNVERIFIED",false)
                } else {
                    if (now.phase == VisibilityExecutionPhase.PREPARING)
                        execution.running(after.completed,after.total)
                    else if (now.phase == VisibilityExecutionPhase.RUNNING &&
                        (now.confirmed != after.completed || now.total != after.total))
                        execution.checkpoint(after.completed,after.total)
                    if (execution.state.value.phase == VisibilityExecutionPhase.RUNNING ||
                        execution.state.value.phase == VisibilityExecutionPhase.PAUSE_REQUESTED) {
                        // Final durable CAS and no pending journal are verified.
                        // A late pause cannot strand a completed transfer.
                        execution.verifying(after.completed,after.total)
                        execution.completeAfterFinalCheck()
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            if (execution.state.value.active)
                execution.failed("FV_WORK_INTERRUPTED",FolderVisibilityManager.hasPendingJournal(context))
            throw cancelled
        } catch (error: Exception) {
            val pending = FolderVisibilityManager.hasPendingJournal(context)
            if (execution.state.value.active)
                execution.failed("FV_WORK_ERROR",pending)
            AppLog.w("FolderVisibility","FOREGROUND_STOP kind=${error.javaClass.simpleName} journalPending=$pending")
            // Manager has already logged the precise failure reason without paths.
        } finally {
            // Cancellation of the Service scope must not cancel the join before
            // releasing the one-command reservation. Otherwise a stopped
            // service leaves the in-process slot permanently busy.
            releaseVisibilityRequestAfterCancellation(collector) {
                commands.finish(request.id)
            }
        }
    }

    /** No synthetic approval on Activity recreation or process death. */
    fun claimAndroidApproval(id: Long): Boolean = approval.claim(id)
    fun resolveAndroidApproval(id: Long, granted: Boolean): Boolean = approval.decide(id, granted)
    fun rejectAndroidApprovalOnActivityLoss(id: Long): Boolean = approval.rejectClaimed(id)
}

/** Finish the observer and release the one-shot transfer reservation even when
 * the Service work coroutine has already been cancelled. This does not approve
 * an Android request, delete a file or alter a durable move/batch journal. */
internal suspend fun releaseVisibilityRequestAfterCancellation(
    observer: Job, release: () -> Unit,
) {
    try {
        withContext(NonCancellable) { observer.cancelAndJoin() }
    } finally {
        release()
    }
}
