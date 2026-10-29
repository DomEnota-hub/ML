package com.domenota.medialoader.core.diagnostics

/** Display policy for one sequential run. It does not change persisted evidence. */
object DiagnosticPresentation {
    fun activeCode(specs: List<DiagnosticCheckSpec>, findings: List<DiagnosticFinding>,
        running: Boolean): String? = if (running) {
        val finished = findings.mapTo(HashSet()) { it.spec.code }
        specs.firstOrNull { it.code !in finished }?.code
    } else null

    fun isActive(spec: DiagnosticCheckSpec, specs: List<DiagnosticCheckSpec>,
        findings: List<DiagnosticFinding>, running: Boolean): Boolean =
        spec.code == activeCode(specs, findings, running)

    fun isExpired(finding: DiagnosticFinding, running: Boolean, nowMillis: Long): Boolean =
        !running && finding.isStale(nowMillis)

    fun confirmedGoodCount(findings: List<DiagnosticFinding>): Int =
        findings.count { it.state == DiagnosticState.OK &&
            it.evidence == DiagnosticEvidence.VERIFIED }
}
