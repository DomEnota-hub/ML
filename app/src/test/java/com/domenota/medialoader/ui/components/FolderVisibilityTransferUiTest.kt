package com.domenota.medialoader.ui.components

import com.domenota.medialoader.data.catalog.visibility.VisibilityExecutionPhase
import com.domenota.medialoader.data.catalog.visibility.VisibilityExecutionSnapshot
import com.domenota.medialoader.ui.PostTransferCatalogStatus
import org.junit.Assert.*
import org.junit.Test

class FolderVisibilityTransferUiTest {
    private fun model(phase:VisibilityExecutionPhase,pending:Boolean=false) =
        VisibilityExecutionSnapshot(phase=phase,confirmed=11,total=46,hasPendingJournal=pending)
    @Test fun runningShowsPauseWhileFinalVerificationNeverClaimsCompletion() {
        val run=visibilityTransferPresentation(model(VisibilityExecutionPhase.RUNNING,true))
        assertTrue(run.canPause)
        assertFalse(run.canResume)
        assertFalse(run.isComplete)
        val verify=visibilityTransferPresentation(model(VisibilityExecutionPhase.VERIFYING,true))
        assertFalse(verify.canPause);assertFalse(verify.canResume);assertFalse(verify.isComplete)
        assertEquals("Проверяем результат",verify.message)
    }
    @Test fun pendingStateCanResumeButCannotClaimSuccess() {
        val pending=visibilityTransferPresentation(model(VisibilityExecutionPhase.RECOVERABLE_PENDING,true))
        assertTrue(pending.canResume)
        assertFalse(pending.isComplete)
        val pause=visibilityTransferPresentation(model(VisibilityExecutionPhase.PAUSE_REQUESTED,true))
        assertFalse(pause.canPause)
        assertFalse(pause.canResume)
    }
    @Test fun preparingDoesNotPromiseAnUnavailablePauseAndFailedStartCanClose() {
        val preparing=visibilityTransferPresentation(model(VisibilityExecutionPhase.PREPARING))
        assertFalse(preparing.canPause)
        val failed=visibilityTransferPresentation(model(VisibilityExecutionPhase.FAILED))
        assertTrue(failed.canDismiss)
        assertFalse(failed.canResume)
        assertFalse(failed.isComplete)
        val persisted=visibilityTransferPresentation(model(VisibilityExecutionPhase.FAILED,true))
        assertFalse(persisted.canDismiss)
        assertTrue(persisted.canResume)
    }
    @Test fun transferBytesCanFinishBeforePhoneMediaCatalogIsReindexed() {
        val completed = model(VisibilityExecutionPhase.COMPLETED)
        val idle = visibilityTransferPresentation(completed,PostTransferCatalogStatus.IDLE)
        assertFalse(idle.isComplete)
        assertFalse(idle.canDismiss)
        assertTrue(idle.explanation.contains("не подтверждено"))
        val updating = visibilityTransferPresentation(completed,PostTransferCatalogStatus.UPDATING)
        assertFalse(updating.isComplete)
        assertFalse(updating.canDismiss)
        assertEquals("Файлы перенесены",updating.message)
        assertTrue(updating.explanation.contains("Обновляем"))
        val incomplete = visibilityTransferPresentation(completed,PostTransferCatalogStatus.INCOMPLETE)
        assertFalse(incomplete.isComplete)
        assertTrue(incomplete.canDismiss)
        assertTrue(incomplete.explanation.contains("не проверена"))
        val ready = visibilityTransferPresentation(completed,PostTransferCatalogStatus.READY)
        assertTrue(ready.isComplete)
        assertEquals("Перенос завершён",ready.message)
    }

    @Test fun compactPanelNamesVerificationAndIncompleteStatusWithoutPrivateFolderNames() {
        val state=model(VisibilityExecutionPhase.COMPLETED)
        assertEquals("Проверяем медиатеку",visibilityMiniPanelTitle(state,PostTransferCatalogStatus.IDLE))
        assertEquals("Обновляем медиатеку",visibilityMiniPanelTitle(state,PostTransferCatalogStatus.UPDATING))
        assertEquals("Медиатека не проверена",visibilityMiniPanelTitle(state,PostTransferCatalogStatus.INCOMPLETE))
        assertEquals("Перенос завершён",visibilityMiniPanelTitle(state,PostTransferCatalogStatus.READY))
        assertEquals("Проверяем файлы",visibilityMiniPanelTitle(model(VisibilityExecutionPhase.VERIFYING),PostTransferCatalogStatus.READY))
        assertEquals("Перенос не завершён",visibilityMiniPanelTitle(model(VisibilityExecutionPhase.RECOVERABLE_PENDING),PostTransferCatalogStatus.IDLE))
        assertEquals("Перенос файлов",visibilityMiniPanelTitle(model(VisibilityExecutionPhase.RUNNING),PostTransferCatalogStatus.IDLE))
    }

    @Test fun realSuccessOnlyAfterFinalModelState() {
        val completed=visibilityTransferPresentation(model(VisibilityExecutionPhase.COMPLETED),PostTransferCatalogStatus.READY)
        assertTrue(completed.isComplete)
        assertFalse(completed.canPause)
        val denied=visibilityTransferPresentation(model(VisibilityExecutionPhase.WAITING_FOR_ANDROID,true))
        assertFalse(denied.canPause);assertFalse(denied.canResume)
        assertTrue(denied.message.contains("Android"))
    }
}
