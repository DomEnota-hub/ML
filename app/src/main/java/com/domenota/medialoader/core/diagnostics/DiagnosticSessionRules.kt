package com.domenota.medialoader.core.diagnostics

/** A cancelled or failed scan cannot replace the last fully completed results on screen. */
object DiagnosticSessionRules {
    fun afterInterruptedRun(lastCompleted: DiagnosticRun?): List<DiagnosticFinding> =
        lastCompleted?.findings.orEmpty()
}
