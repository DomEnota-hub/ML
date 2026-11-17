package com.domenota.medialoader.data.catalog.visibility

import org.junit.Assert.*
import org.junit.Test

class VisibilityExecutionStateTest {
    private inline fun rejected(block:()->Unit) {
        try { block();fail("Unsafe transition was accepted") }
        catch (_:IllegalStateException) {}
    }

    @Test fun oldJournalIsRecoverableButNeverAutomaticallyStarts() {
        val model=VisibilityExecutionStateMachine()
        model.restoreFromJournal(pending = true, confirmed = 23, total = 46)
        assertEquals(VisibilityExecutionPhase.RECOVERABLE_PENDING,model.state.value.phase)
        rejected { model.startNew() }
        rejected { model.completeAfterFinalCheck() }
        model.beginExplicitResume()
        model.running(23,46)
        assertEquals(VisibilityExecutionPhase.RUNNING,model.state.value.phase)
    }
    @Test fun pauseDoesNotClaimSuccessUntilNextDurableCheckpoint() {
        val model=VisibilityExecutionStateMachine()
        model.startNew()
        model.running(10,46)
        model.requestPause()
        assertEquals(VisibilityExecutionPhase.PAUSE_REQUESTED,model.state.value.phase)
        rejected { model.completeAfterFinalCheck() }
        model.checkpoint(11,46)
        assertEquals(VisibilityExecutionPhase.PAUSED,model.state.value.phase)
        assertEquals(11,model.state.value.confirmed)
        rejected { model.checkpoint(12,46) }
        model.beginExplicitResume()
        model.running(11,46)
        model.checkpoint(12,46)
        assertEquals(VisibilityExecutionPhase.RUNNING,model.state.value.phase)
    }
    @Test fun deniedAndroidPermissionCannotAdvanceOrDelete() {
        val model=VisibilityExecutionStateMachine()
        model.startNew();model.running(1,3)
        model.waitingForAndroid()
        rejected { model.completeAfterFinalCheck() }
        model.androidDecision(false)
        assertEquals(VisibilityExecutionPhase.RECOVERABLE_PENDING,model.state.value.phase)
        rejected { model.running(2,3) }
        model.beginExplicitResume()
        model.running(1,3)
    }
    @Test fun completionRequiresFinalVerificationAndAllConfirmedMembers() {
        val model=VisibilityExecutionStateMachine()
        model.startNew();model.running(0,3)
        rejected { model.verifying(2,3) }
        rejected { model.completeAfterFinalCheck() }
        model.checkpoint(3,3)
        model.verifying(3,3)
        model.completeAfterFinalCheck()
        assertEquals(VisibilityExecutionPhase.COMPLETED,model.state.value.phase)
        assertFalse(model.state.value.hasPendingJournal)
    }
    @Test fun duplicateRunsBackwardsCountersAndSensitiveErrorReasonsRejected() {
        val model=VisibilityExecutionStateMachine()
        model.startNew()
        rejected { model.startNew() }
        model.running(2,46)
        rejected { model.checkpoint(1,46) }
        rejected { model.failed("/storage/emulated/0/private/file.jpg",true) }
        model.failed("FV_DESTINATION_STATE",true)
        assertEquals(VisibilityExecutionPhase.FAILED,model.state.value.phase)
        rejected { model.startNew() }
        model.beginExplicitResume()
    }
}
