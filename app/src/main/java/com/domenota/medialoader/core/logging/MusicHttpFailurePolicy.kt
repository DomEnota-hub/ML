package com.domenota.medialoader.core.logging

/**
 * Records the outcome of a MUSIC SEARCH OPERATION, not the health of a website.
 * A failing image, script or transient page request is not a failed song search.
 * No cookies, URLs, query strings or user input appear in emitted event names.
 */
enum class MusicHttpLogDecision {
    IGNORED,
    SUBRESOURCE_DEBUG,
    SEARCH_FAILED,
    DETAIL_FAILED,
    MAIN_FRAME_WARNING,
}

object MusicHttpFailurePolicy {
    fun classify(
        statusCode: Int,
        isMainFrame: Boolean,
        searching: Boolean,
        detailPending: Boolean,
    ): MusicHttpLogDecision = when {
        statusCode < 400 -> MusicHttpLogDecision.IGNORED
        !isMainFrame -> MusicHttpLogDecision.SUBRESOURCE_DEBUG
        searching -> MusicHttpLogDecision.SEARCH_FAILED
        detailPending -> MusicHttpLogDecision.DETAIL_FAILED
        else -> MusicHttpLogDecision.MAIN_FRAME_WARNING
    }

    /** Fixed event names make the technical journal and Russian explanations stable. */
    fun eventName(decision: MusicHttpLogDecision): String = when (decision) {
        MusicHttpLogDecision.SEARCH_FAILED -> "Search document HTTP failure"
        MusicHttpLogDecision.DETAIL_FAILED -> "Info page HTTP failure"
        MusicHttpLogDecision.MAIN_FRAME_WARNING -> "Main-frame HTTP rejected outside active operation"
        MusicHttpLogDecision.SUBRESOURCE_DEBUG -> "Subresource HTTP rejected"
        MusicHttpLogDecision.IGNORED -> "No HTTP error"
    }
}
