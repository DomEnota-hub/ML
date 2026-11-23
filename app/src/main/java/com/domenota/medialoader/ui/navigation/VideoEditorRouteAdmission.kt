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
        authorizedVideoAvailable -> VideoEditorRouteAdmission.OPEN
        explicitlyDenied -> VideoEditorRouteAdmission.EXIT
        elapsedMs < MIN_WAIT_MS -> VideoEditorRouteAdmission.WAIT
        publicCatalogReady -> VideoEditorRouteAdmission.EXIT
        elapsedMs < MAX_WAIT_MS -> VideoEditorRouteAdmission.WAIT
        else -> VideoEditorRouteAdmission.EXIT
    }
}
