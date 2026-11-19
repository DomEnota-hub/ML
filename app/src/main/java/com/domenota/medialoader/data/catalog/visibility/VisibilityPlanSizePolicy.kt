package com.domenota.medialoader.data.catalog.visibility

/** Strictly classify which of three independent lengths disagrees. The code
 * never contains a private filename, URI, fingerprint, or actual file size. */
internal enum class VisibilityPlanSizeMismatch {
    CATALOG_ONLY, MEDIASTORE_ONLY, STREAM_ONLY, ALL_DIFFERENT
}

internal fun classifyVisibilityPlanSize(
    catalogBytes: Long, mediaStoreBytes: Long, streamedBytes: Long,
): VisibilityPlanSizeMismatch? {
    if (catalogBytes == mediaStoreBytes && mediaStoreBytes == streamedBytes) return null
    return when {
        mediaStoreBytes == streamedBytes -> VisibilityPlanSizeMismatch.CATALOG_ONLY
        catalogBytes == streamedBytes -> VisibilityPlanSizeMismatch.MEDIASTORE_ONLY
        catalogBytes == mediaStoreBytes -> VisibilityPlanSizeMismatch.STREAM_ONLY
        else -> VisibilityPlanSizeMismatch.ALL_DIFFERENT
    }
}

internal class VisibilityPlanSizeFailure(val kind: VisibilityPlanSizeMismatch) :
    IllegalStateException("Размеры исходника не совпали; файл не изменён")
