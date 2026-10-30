package com.domenota.medialoader.ui.components

internal data class MediaDeletionTarget(val id: String, val uri: String?) {
    val phone: Boolean get() = id.startsWith("phone:")
}

internal object MediaDeletionPolicy {
    // Android 16 limits requests for apps targeting newer APIs to 2000 URIs.
    const val REQUEST_LIMIT = 2000
    fun needsAppConfirmation(sdk: Int, canManage: Boolean, targets: List<MediaDeletionTarget>): Boolean =
        targets.none { it.phone } || targets.any { !it.phone } || sdk < 30 || canManage
    fun phoneBatches(targets: List<MediaDeletionTarget>): List<List<String>> = targets.filter { it.phone }
        .map { requireNotNull(it.uri) }.distinct().chunked(REQUEST_LIMIT)
}
