package com.domenota.medialoader.core.diagnostics

/**
 * Stable domain contracts for the MediaLoader diagnostic subsystem.
 *
 * Phase 1 deliberately has no Android, network, file-system, ONNX or yt-dlp dependencies.
 * A stored session/cookies, an installed component or a successful historical download
 * MUST NOT by themselves be interpreted as a successful diagnostic check.
 */
enum class DiagnosticComponent {
    SOURCES, MEDIA_RUNTIME, DOWNLOAD_QUEUE, STORAGE, PHOTO_EDITOR, FAULT_JOURNAL, DEVICE,
}

enum class DiagnosticState {
    OK, WARNING, ERROR, UNCHECKED, RUNNING, UNAVAILABLE,
}

enum class DiagnosticEvidence {
    NONE, OBSERVED, HISTORICAL, VERIFIED,
}

enum class DiagnosticImpact {
    /** Failed verified checks can mark the complete application as faulty. */
    REQUIRED,
    /** Absence or failure must not paint the whole application red. */
    OPTIONAL,
}

enum class DiagnosticScope { LOCAL, NETWORK, FULL }
enum class DiagnosticMode { LOCAL, WITH_NETWORK, FULL }

data class DiagnosticCheckSpec(
    val code: String,
    val component: DiagnosticComponent,
    val impact: DiagnosticImpact,
    val scope: DiagnosticScope = DiagnosticScope.LOCAL,
    val timeoutMillis: Long = 15_000L,
    val maxAgeMillis: Long = 5 * 60_000L,
) {
    init {
        require(CODE.matches(code)) { "Diagnostic code must be an opaque stable identifier" }
        require(timeoutMillis in 1..120_000L) { "Unsafe diagnostic timeout" }
        require(maxAgeMillis in 1..86_400_000L) { "Invalid freshness policy" }
    }

    companion object {
        private val CODE = Regex("[a-z][a-z0-9_.-]{1,63}")
    }
}

/**
 * Probe output is separate from execution metadata. Error messages, URLs, cookies,
 * tokens, file names and stack traces are NOT valid technical reason codes.
 * User explanations/recommendations must be static, localized messages.
 */
data class DiagnosticOutcome(
    val state: DiagnosticState,
    val evidence: DiagnosticEvidence,
    val explanation: String,
    val reasonCode: String? = null,
    val recommendation: String? = null,
    val relatedEventIds: List<String> = emptyList(),
) {
    init {
        require(state != DiagnosticState.RUNNING) {
            "A completed probe cannot report RUNNING"
        }
        if (state == DiagnosticState.OK || state == DiagnosticState.ERROR) {
            require(evidence == DiagnosticEvidence.VERIFIED) {
                "Success/failure requires evidence from an actual check"
            }
        }
        if (state == DiagnosticState.UNCHECKED) {
            require(evidence == DiagnosticEvidence.NONE) {
                "Unchecked components cannot claim verified evidence"
            }
        }
        require(explanation.isNotBlank()) { "Missing user explanation" }
        require(reasonCode == null || REASON.matches(reasonCode)) {
            "Technical reason must be an opaque code, not raw exception text"
        }
        require(relatedEventIds.all { REFERENCE.matches(it) }) {
            "Log links must be opaque identifiers, not raw log lines or URLs"
        }
    }

    companion object {
        private val REASON = Regex("[A-Z][A-Z0-9_.-]{1,95}")
        private val REFERENCE = Regex("[a-zA-Z0-9_.:-]{1,96}")

        fun unavailable(explanation: String, reasonCode: String): DiagnosticOutcome =
            DiagnosticOutcome(DiagnosticState.UNAVAILABLE, DiagnosticEvidence.NONE,
                explanation, reasonCode = reasonCode)
        fun unchecked(explanation: String): DiagnosticOutcome =
            DiagnosticOutcome(DiagnosticState.UNCHECKED, DiagnosticEvidence.NONE, explanation)
    }
}

data class DiagnosticFinding(
    val spec: DiagnosticCheckSpec,
    val state: DiagnosticState,
    val evidence: DiagnosticEvidence,
    val checkedAtMillis: Long?,
    val durationMillis: Long?,
    val explanation: String,
    val reasonCode: String?,
    val recommendation: String?,
    val relatedEventIds: List<String>,
) {
    init {
        require(checkedAtMillis == null || checkedAtMillis >= 0)
        require(durationMillis == null || durationMillis >= 0)
        if (state == DiagnosticState.OK || state == DiagnosticState.ERROR) {
            require(evidence == DiagnosticEvidence.VERIFIED && checkedAtMillis != null) {
                "Verified result must come from an executed check"
            }
        }
    }

    fun isStale(nowMillis: Long): Boolean {
        val checked = checkedAtMillis ?: return true
        if (nowMillis < checked) return true // clock may have been adjusted
        return nowMillis - checked > spec.maxAgeMillis
    }

    companion object {
        fun fromOutcome(spec: DiagnosticCheckSpec, outcome: DiagnosticOutcome,
            checkedAtMillis: Long, durationMillis: Long): DiagnosticFinding =
            DiagnosticFinding(spec, outcome.state, outcome.evidence, checkedAtMillis,
                durationMillis, outcome.explanation, outcome.reasonCode,
                outcome.recommendation, outcome.relatedEventIds)

        fun notChecked(spec: DiagnosticCheckSpec, explanation: String): DiagnosticFinding =
            DiagnosticFinding(spec, DiagnosticState.UNCHECKED, DiagnosticEvidence.NONE,
                null, null, explanation, null, null, emptyList())
    }
}

data class DiagnosticRun(
    val mode: DiagnosticMode,
    val startedAtMillis: Long,
    val finishedAtMillis: Long,
    val findings: List<DiagnosticFinding>,
) {
    init {
        require(finishedAtMillis >= startedAtMillis)
        require(findings.map { it.spec.code }.distinct().size == findings.size)
    }
}

/** Implemented with the existing app's local persistence in a later stage. */
interface DiagnosticRunStore {
    suspend fun save(run: DiagnosticRun)
    suspend fun mostRecent(): DiagnosticRun?
}

/** Read-only checks are registered as adapters in stages 2 and 3. */
interface DiagnosticProbe {
    val spec: DiagnosticCheckSpec
    suspend fun check(): DiagnosticOutcome
}
