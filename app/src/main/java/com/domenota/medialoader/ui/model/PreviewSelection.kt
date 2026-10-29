package com.domenota.medialoader.ui.model

import com.domenota.medialoader.core.model.MediaItem
import com.domenota.medialoader.core.model.MediaType

internal fun togglePreviewSelection(items: List<MediaItem>, selectedIds: Set<String>, id: String): Set<String> {
    val item = items.firstOrNull { it.id == id } ?: return selectedIds
    val singleChoice = items.none { it.type == MediaType.PHOTO }
    if (!singleChoice && id in selectedIds) return selectedIds - id
    val alternatives = if (item.type == MediaType.VIDEO || item.type == MediaType.AUDIO) {
        items.filter { it.providerId == item.providerId && (it.type == MediaType.VIDEO || it.type == MediaType.AUDIO) }
            .map { it.id }.toSet()
    } else emptySet()
    return (selectedIds - alternatives) + id
}
