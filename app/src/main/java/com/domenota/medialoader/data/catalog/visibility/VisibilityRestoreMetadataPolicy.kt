package com.domenota.medialoader.data.catalog.visibility

import kotlin.math.abs

/**
 * Metadata acknowledgement is separate from the authoritative byte/owner/path
 * proof. MediaStore may round DATE_TAKEN to the nearest second after a scan.
 * The same <=1000 ms tolerance is used by private-media restoration.
 *
 * This policy never authorizes deletion by itself: the move engine still
 * requires exact source + destination SHA-256 and live catalog bindings.
 */
internal data class VisibilityRestoreMetadataStatus(
    val sizeConfirmed: Boolean,
    val modifiedConfirmed: Boolean,
    val takenConfirmed: Boolean,
) {
    val confirmed: Boolean get() = sizeConfirmed && modifiedConfirmed && takenConfirmed
    val diagnosticCode: String get() = buildList {
        if (!sizeConfirmed) add("SIZE")
        if (!modifiedConfirmed) add("DATE_MODIFIED")
        if (!takenConfirmed) add("DATE_TAKEN")
    }.joinToString("+").ifEmpty { "OK" }
}

internal object VisibilityRestoreMetadataPolicy {
    const val DATE_TAKEN_TOLERANCE_MS = 1000L

    fun observe(
        expectedBytes: Long,
        expectedModifiedSeconds: Long,
        expectedTakenMillis: Long?,
        observedBytes: Long,
        observedModifiedSeconds: Long,
        observedTakenMillis: Long?,
    ): VisibilityRestoreMetadataStatus = VisibilityRestoreMetadataStatus(
        sizeConfirmed = observedBytes == expectedBytes,
        modifiedConfirmed = observedModifiedSeconds == expectedModifiedSeconds,
        takenConfirmed = expectedTakenMillis == null ||
            (observedTakenMillis != null &&
                abs(expectedTakenMillis - observedTakenMillis) <= DATE_TAKEN_TOLERANCE_MS),
    )
}
