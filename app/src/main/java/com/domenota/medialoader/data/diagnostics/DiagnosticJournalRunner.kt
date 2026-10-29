package com.domenota.medialoader.data.diagnostics

import com.domenota.medialoader.core.diagnostics.DiagnosticCoordinator
import com.domenota.medialoader.core.diagnostics.DiagnosticFinding
import com.domenota.medialoader.core.diagnostics.DiagnosticMode
import com.domenota.medialoader.core.diagnostics.DiagnosticRun

/**
 * Explicit entry point for the upcoming UI. No automatic runs and no background
 * monitoring. The fault journal is updated only after a COMPLETED diagnostic run.
 * A cancelled run is never saved as a successful or resolved incident.
 *
 * A storage error propagates to the caller instead of reporting a false success.
 */
class DiagnosticJournalRunner(
    private val coordinator: DiagnosticCoordinator,
    private val journal: FaultHistoryStore,
) {
    suspend fun execute(
        mode: DiagnosticMode = DiagnosticMode.LOCAL,
        networkConsent: Boolean = false,
        onFinding: (DiagnosticFinding) -> Unit = {},
    ): DiagnosticRun {
        val result = coordinator.run(mode, networkConsent, onFinding)
        journal.record(result)
        return result
    }
}
