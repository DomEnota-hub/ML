package com.domenota.medialoader.data.catalog.visibility

import org.junit.Assert.*
import org.junit.Test

class VisibilityForegroundStatusTest {
    private fun snapshot(phase:VisibilityExecutionPhase,pending:Boolean=false)=
        VisibilityExecutionSnapshot(phase=phase,confirmed=1,total=8,hasPendingJournal=pending)

    @Test fun terminalWithoutJournalCannotClaimOriginalsAreIntactOrTransferNeverStarted() {
        val text=visibilityForegroundMessage(snapshot(VisibilityExecutionPhase.FAILED))
        assertTrue(text.contains("журнале"))
        assertFalse(text.contains("данные сохранены"))
        assertFalse(text.contains("исходные"))
        assertFalse(text.contains("завершён"))
    }

    @Test fun pendingAndAndroidApprovalAreNotMisrepresentedAsSuccess() {
        val pending=visibilityForegroundMessage(snapshot(VisibilityExecutionPhase.FAILED,true))
        assertTrue(pending.contains("требуется проверка"))
        assertTrue(visibilityForegroundMessage(snapshot(VisibilityExecutionPhase.RECOVERABLE_PENDING,true)).contains("проверки"))
        assertTrue(visibilityForegroundMessage(snapshot(VisibilityExecutionPhase.WAITING_FOR_ANDROID,true)).contains("подтвердить"))
    }

    @Test fun afterDurableFileSuccessCatalogIsStillSeparatePhase() {
        val text=visibilityForegroundMessage(snapshot(VisibilityExecutionPhase.COMPLETED))
        assertTrue(text.contains("медиатека"))
        assertFalse(text.contains("полностью"))
        assertEquals("Подтверждено: 1 из 8",
            visibilityForegroundMessage(snapshot(VisibilityExecutionPhase.RUNNING,true)))
    }
}
