package com.domenota.medialoader.ui.navigation

/** A media-index rebuild must not turn a transient missing row into Back.
 * The editor never renders without a currently authorized public/private row.
 * All elapsed times are monotonic since the navigation entry was created.
 */
internal enum class VideoEditorRouteAdmission { OPEN, WAIT, EXIT }

internal object VideoEditorRouteAdmissionPolicy {
    const val MIN_WAIT_MS = 2_200L
    const val MAX_WAIT_MS = 35_000L

    fun decide(
        authorizedVideoAvailable: Boolean,
        explicitlyDenied: Boolean,
        publicCatalogReady: Boolean,
        elapsedMs: Long,
    ): VideoEditorRouteAdmission = when {
        explicitlyDenied -> VideoEditorRouteAdmission.EXIT
        authorizedVideoAvailable -> VideoEditorRouteAdmission.OPEN
        elapsedMs < MIN_WAIT_MS -> VideoEditorRouteAdmission.WAIT
        publicCatalogReady -> VideoEditorRouteAdmission.EXIT
        elapsedMs < MAX_WAIT_MS -> VideoEditorRouteAdmission.WAIT
        else -> VideoEditorRouteAdmission.EXIT
    }
}

/** An asynchronous visibility restore must not open an editor from a stale
 * viewer entry or stack another player/editor while the first request is active. */
internal object EditorOpenRequestPolicy {
    fun canBegin(originEntryId:String?, originIsEditor:Boolean, inFlight:Boolean):Boolean =
        originEntryId!=null && !originIsEditor && !inFlight

    fun canCommit(originEntryId:String?, activeEntryId:String?):Boolean =
        originEntryId!=null && originEntryId==activeEntryId
}
