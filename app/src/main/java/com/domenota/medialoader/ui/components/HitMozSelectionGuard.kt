package com.domenota.medialoader.ui.components

/** Explicit selection only: an old WebView callback must never enqueue another song. */
internal object HitMozSelectionGuard {
    fun accepts(
        pendingUrl: String,
        callbackUrl: String,
        pendingRevision: Int,
        activeRevision: Int,
        hasActiveSearch: Boolean,
        loading: Boolean,
        selectedRowStillVisible: Boolean,
    ): Boolean =
        hasActiveSearch && !loading && selectedRowStillVisible &&
            pendingRevision == activeRevision &&
            pendingUrl.isNotBlank() && callbackUrl == pendingUrl
}
