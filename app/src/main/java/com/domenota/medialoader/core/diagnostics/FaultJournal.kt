package com.domenota.medialoader.core.diagnostics

/** Structured fault history. Not a replacement for the append-only technical AppLog. */
enum class FaultSeverity { WARNING, ERROR }
enum class FaultStatus { ACTIVE, NEEDS_RECHECK, RESOLVED }

/** Stable opaque metadata only. No URL, cookie, user input, raw exception or file name. */
data class FaultSignal(
    val component: DiagnosticComponent,
    val checkCode: String,
    val reasonCode: String,
    val severity: FaultSeverity,
    val atMillis: Long,
    val operationId: String? = null,
    val eventIds: List<String> = emptyList(),
) {
    init {
        require(Regex("[a-z][a-z0-9_.-]{1,63}").matches(checkCode))
        require(Regex("[A-Z][A-Z0-9_.-]{1,95}").matches(reasonCode))
        require(operationId == null || Regex("[a-zA-Z0-9_.:-]{1,96}").matches(operationId))
        require(eventIds.all { Regex("[a-zA-Z0-9_.:-]{1,96}").matches(it) })
        require(atMillis >= 0)
    }
}

/** Opaque ID is created externally, never derived from an account or remote URL. */
data class FaultRecord(
    val id: String,
    val component: DiagnosticComponent,
    val checkCode: String,
    val reasonCode: String,
    val operationId: String?,
    val severity: FaultSeverity,
    val status: FaultStatus,
    val firstSeenMillis: Long,
    val lastSeenMillis: Long,
    val resolvedAtMillis: Long?,
    val repetitions: Int,
    val eventIds: List<String>,
) {
    init {
        require(Regex("[a-zA-Z0-9_.:-]{1,96}").matches(id))
        require(firstSeenMillis >= 0 && lastSeenMillis >= firstSeenMillis)
        require(resolvedAtMillis == null || resolvedAtMillis >= lastSeenMillis)
        require(repetitions in 1..1_000_000)
        require(eventIds.size <= FaultJournal.MAX_LINKS)
        // Persisted fields must be generated from validated opaque signals.
        FaultSignal(component, checkCode, reasonCode, severity,
            firstSeenMillis, operationId, eventIds)
    }
}

sealed class FaultObservation {
    data class Failure(val signal: FaultSignal) : FaultObservation()

    /**
     * A successful test of the SAME check code can close its earlier failures.
     * If an operation ID exists, it must also match exactly.
     * No cross-component or cross-check recovery is permitted.
     */
    data class Recovered(
        val component: DiagnosticComponent,
        val checkCode: String,
        val atMillis: Long,
        val operationId: String? = null,
    ) : FaultObservation()

    data class RecheckRequired(
        val component: DiagnosticComponent,
        val checkCode: String,
        val operationId: String? = null,
    ) : FaultObservation()
}

/** Deterministic reduction; persistence, clocks and UUIDs are injected by caller. */
object FaultJournal {
    const val MAX_RECORDS = 300
    const val MAX_LINKS = 16
    private val stableCheck = Regex("[a-z][a-z0-9_.-]{1,63}")
    private val stableId = Regex("[a-zA-Z0-9_.:-]{1,96}")

    fun apply(
        existing: List<FaultRecord>,
        observation: FaultObservation,
        nextId: () -> String,
    ): List<FaultRecord> {
        val data = existing.toMutableList()
        when (observation) {
            is FaultObservation.Failure -> {
                val event = observation.signal
                val index = data.indexOfLast {
                    it.status != FaultStatus.RESOLVED &&
                        it.component == event.component &&
                        it.checkCode == event.checkCode &&
                        it.reasonCode == event.reasonCode &&
                        it.operationId == event.operationId
                }
                if (index >= 0) {
                    val previous = data[index]
                    // Old/replayed events must not reset the clock or inflate counters.
                    if (event.atMillis > previous.lastSeenMillis) {
                        data[index] = previous.copy(
                            lastSeenMillis = event.atMillis,
                            severity = if (previous.severity == FaultSeverity.ERROR ||
                                event.severity == FaultSeverity.ERROR) FaultSeverity.ERROR
                                else FaultSeverity.WARNING,
                            repetitions = (previous.repetitions + 1).coerceAtMost(1_000_000),
                            status = FaultStatus.ACTIVE,
                            eventIds = (previous.eventIds + event.eventIds)
                                .distinct().takeLast(MAX_LINKS),
                        )
                    }
                } else {
                    // An old event replayed after a verified recovery cannot create
                    // a new incident for a time when the previous issue was resolved.
                    val resolvedAlready = data.any {
                        it.component == event.component &&
                            it.checkCode == event.checkCode &&
                            it.reasonCode == event.reasonCode &&
                            it.operationId == event.operationId &&
                            it.status == FaultStatus.RESOLVED &&
                            event.atMillis <= (it.resolvedAtMillis ?: it.lastSeenMillis)
                    }
                    if (resolvedAlready) return existing
                    val id = nextId()
                    require(stableId.matches(id) && data.none { it.id == id })
                    data.add(FaultRecord(id, event.component, event.checkCode, event.reasonCode,
                        event.operationId, event.severity, FaultStatus.ACTIVE, event.atMillis,
                        event.atMillis, null, 1, event.eventIds.distinct().takeLast(MAX_LINKS)))
                }
            }
            is FaultObservation.Recovered -> {
                require(stableCheck.matches(observation.checkCode) &&
                    observation.atMillis >= 0)
                require(observation.operationId == null ||
                    stableId.matches(observation.operationId))
                for (index in data.indices) {
                    val record = data[index]
                    if (record.component == observation.component &&
                        record.checkCode == observation.checkCode &&
                        record.operationId == observation.operationId &&
                        record.status != FaultStatus.RESOLVED &&
                        observation.atMillis >= record.lastSeenMillis) {
                        data[index] = record.copy(status = FaultStatus.RESOLVED,
                            resolvedAtMillis = observation.atMillis)
                    }
                }
            }
            is FaultObservation.RecheckRequired -> {
                require(stableCheck.matches(observation.checkCode))
                require(observation.operationId == null ||
                    stableId.matches(observation.operationId))
                for (index in data.indices) {
                    val record = data[index]
                    if (record.component == observation.component &&
                        record.checkCode == observation.checkCode &&
                        record.operationId == observation.operationId &&
                        record.status == FaultStatus.ACTIVE) {
                        data[index] = record.copy(status = FaultStatus.NEEDS_RECHECK)
                    }
                }
            }
        }
        // Retain bounded history including older resolved incidents, never merge those away.
        return data.sortedWith(compareBy<FaultRecord> { it.lastSeenMillis }.thenBy { it.id })
            .takeLast(MAX_RECORDS)
    }

    /**
     * Only actually executed checks may open or close a fault.
     * An unchecked network probe or a stale result cannot close a previous fault.
     */
    fun applyRun(
        existing: List<FaultRecord>,
        run: DiagnosticRun,
        nextId: () -> String,
    ): List<FaultRecord> {
        var records = existing
        for (finding in run.findings) {
            val whenChecked = finding.checkedAtMillis ?: continue
            if (whenChecked !in run.startedAtMillis..run.finishedAtMillis) continue
            if (finding.spec.scope == DiagnosticScope.NETWORK &&
                run.mode == DiagnosticMode.LOCAL) continue
            val event = when {
                finding.evidence == DiagnosticEvidence.VERIFIED &&
                    finding.state == DiagnosticState.OK ->
                    FaultObservation.Recovered(finding.spec.component, finding.spec.code,
                        whenChecked)
                finding.evidence == DiagnosticEvidence.VERIFIED &&
                    finding.state in listOf(DiagnosticState.WARNING, DiagnosticState.ERROR) &&
                    finding.reasonCode != null ->
                    FaultObservation.Failure(FaultSignal(
                        component = finding.spec.component,
                        checkCode = finding.spec.code,
                        reasonCode = finding.reasonCode,
                        severity = if (finding.state == DiagnosticState.ERROR) FaultSeverity.ERROR
                            else FaultSeverity.WARNING,
                        atMillis = whenChecked,
                        eventIds = finding.relatedEventIds,
                    ))
                finding.state == DiagnosticState.UNAVAILABLE ->
                    FaultObservation.RecheckRequired(finding.spec.component, finding.spec.code)
                else -> null
            }
            if (event != null) records = apply(records, event, nextId)
        }
        return records
    }
}
