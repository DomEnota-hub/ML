package com.domenota.medialoader.ui.components

import com.domenota.medialoader.data.catalog.visibility.VisibilityExecutionPhase
import com.domenota.medialoader.data.catalog.visibility.VisibilityExecutionSnapshot
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
    @Test fun realSuccessOnlyAfterFinalModelState() {
        val completed=visibilityTransferPresentation(model(VisibilityExecutionPhase.COMPLETED))
        assertTrue(completed.isComplete)
        assertFalse(completed.canPause)
        val denied=visibilityTransferPresentation(model(VisibilityExecutionPhase.WAITING_FOR_ANDROID,true))
        assertFalse(denied.canPause);assertFalse(denied.canResume)
        assertTrue(denied.message.contains("Android"))
    }
}
