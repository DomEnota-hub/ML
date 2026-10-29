package com.domenota.medialoader.core.diagnostics

/**
 * A derived status, never a measurement. It must retain visibility of missing and
 * expired evidence rather than treating absent rows as successful checks.
 */
data class DiagnosticSummary(
    val state: DiagnosticState,
    val totalChecks: Int,
    val freshResults: Int,
    val expiredResults: Int,
    val missingResults: Int,
)

/** Pure, deterministic status decisions: no clocks, Android APIs or I/O. */
object DiagnosticAssessment {
    fun application(
        specs: List<DiagnosticCheckSpec>,
        findings: List<DiagnosticFinding>,
        nowMillis: Long,
        currentRun: Boolean = false,
    ): DiagnosticSummary {
        val mandatory = specs.filter { it.impact == DiagnosticImpact.REQUIRED }
        val current = latestByCode(specs, findings, nowMillis, currentRun)
        val counts = counts(specs, current, findings, nowMillis, currentRun)
        if (mandatory.isEmpty()) return counts.copy(state = DiagnosticState.UNCHECKED)
        val requiredStates = mandatory.map { effectiveState(it, current) }
        val optionalStates = specs.filter { it.impact == DiagnosticImpact.OPTIONAL }
            .map { effectiveState(it, current) }

        val state = when {
            DiagnosticState.ERROR in requiredStates -> DiagnosticState.ERROR
            DiagnosticState.WARNING in requiredStates -> DiagnosticState.WARNING
            DiagnosticState.RUNNING in requiredStates -> DiagnosticState.RUNNING
            DiagnosticState.UNCHECKED in requiredStates -> DiagnosticState.UNCHECKED
            DiagnosticState.UNAVAILABLE in requiredStates -> DiagnosticState.UNAVAILABLE
            requiredStates.any { it != DiagnosticState.OK } -> DiagnosticState.UNCHECKED
            optionalStates.any { it == DiagnosticState.ERROR || it == DiagnosticState.WARNING } ->
                DiagnosticState.WARNING // optional failure cannot turn the whole app red
            specs.any { it.scope == DiagnosticScope.FULL &&
                it.code.startsWith("source.") && it.code.endsWith(".workflow") &&
                effectiveState(it, current) in listOf(DiagnosticState.UNAVAILABLE,
                    DiagnosticState.UNCHECKED) } -> DiagnosticState.UNAVAILABLE
            else -> DiagnosticState.OK // every mandatory check has fresh VERIFIED evidence
        }
        return counts.copy(state = state)
    }

    /** A missing optional feature may be red inside ITS OWN component, not globally. */
    fun component(
        component: DiagnosticComponent,
        specs: List<DiagnosticCheckSpec>,
        findings: List<DiagnosticFinding>,
        nowMillis: Long,
        currentRun: Boolean = false,
    ): DiagnosticSummary {
        val ownSpecs = specs.filter { it.component == component }
        val current = latestByCode(ownSpecs, findings, nowMillis, currentRun)
        val counts = counts(ownSpecs, current, findings, nowMillis, currentRun)
        if (ownSpecs.isEmpty()) return counts.copy(state = DiagnosticState.UNCHECKED)
        // In a full run, functional source results supersede anonymous reachability,
        // stored-cookie presence and historical downloads for the group headline.
        val decisive = if (component == DiagnosticComponent.SOURCES) {
            val workflows = ownSpecs.filter { it.code.endsWith(".workflow") }
            if (workflows.any { it.code in current }) workflows else ownSpecs
        } else ownSpecs
        val states = decisive.map { effectiveState(it, current) }
        val state = when {
            DiagnosticState.ERROR in states -> DiagnosticState.ERROR
            DiagnosticState.WARNING in states -> DiagnosticState.WARNING
            DiagnosticState.RUNNING in states -> DiagnosticState.RUNNING
            DiagnosticState.UNCHECKED in states -> DiagnosticState.UNCHECKED
            DiagnosticState.UNAVAILABLE in states -> DiagnosticState.UNAVAILABLE
            states.all { it == DiagnosticState.OK } -> DiagnosticState.OK
            else -> DiagnosticState.UNCHECKED
        }
        return counts.copy(state = state)
    }

    private fun latestByCode(
        specs: List<DiagnosticCheckSpec>,
        findings: List<DiagnosticFinding>,
        nowMillis: Long,
        currentRun: Boolean,
    ): Map<String, DiagnosticFinding> {
        val available = specs.map { it.code }.toSet()
        // No stale results can ever turn a component green (or red).
        return findings.filter { it.spec.code in available &&
            (currentRun || !it.isStale(nowMillis)) }
            .groupBy { it.spec.code }
            .mapValues { (_, values) ->
                values.maxByOrNull { it.checkedAtMillis ?: -1L }!!
            }
    }

    private fun effectiveState(spec: DiagnosticCheckSpec,
        current: Map<String, DiagnosticFinding>): DiagnosticState {
        val state = current[spec.code]?.state ?: DiagnosticState.UNCHECKED
        // A completed write/read proves more than the earlier metadata-only folder probe.
        // Do not mask a verified folder error such as a missing SAF permission.
        return if (spec.code == "storage.selected_folder" &&
            state in listOf(DiagnosticState.UNAVAILABLE, DiagnosticState.UNCHECKED) &&
            current["storage.write"]?.state == DiagnosticState.OK) DiagnosticState.OK
        else state
    }

    private fun counts(
        specs: List<DiagnosticCheckSpec>,
        current: Map<String, DiagnosticFinding>,
        findings: List<DiagnosticFinding>,
        nowMillis: Long,
        currentRun: Boolean,
    ): DiagnosticSummary {
        val knownCodes = specs.map { it.code }.toSet()
        val fresh = current.values.count { it.checkedAtMillis != null }
        val stale = findings.asSequence().filter { !currentRun && it.spec.code in knownCodes &&
            it.checkedAtMillis != null && it.isStale(nowMillis) }
            .map { it.spec.code }.distinct().count { it !in current }
        return DiagnosticSummary(DiagnosticState.UNCHECKED, specs.size,
            fresh, stale, (specs.size - current.size).coerceAtLeast(0))
    }
}
