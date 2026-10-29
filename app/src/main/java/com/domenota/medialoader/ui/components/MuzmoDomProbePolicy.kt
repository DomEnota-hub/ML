package com.domenota.medialoader.ui.components

/**
 * A settled DOM with repeatedly missing/malformed data is a parser failure,
 * not a network timeout or evidence that a track does not exist.
 * Never terminate searches on elapsed wall-clock time alone.
 */
internal object MuzmoDomProbePolicy {
    private const val EMPTY_SETTLED_SCANS = 12
    private const val MALFORMED_SETTLED_SCANS = 3

    fun exhausted(readyState: String, consecutiveSettledScans: Int, candidatesPresent: Boolean): Boolean {
        if (readyState != "complete") return false
        val required = if (candidatesPresent) MALFORMED_SETTLED_SCANS else EMPTY_SETTLED_SCANS
        return consecutiveSettledScans >= required
    }
}
