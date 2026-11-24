package com.domenota.medialoader.ui.screens.gallery

import androidx.compose.runtime.Composable
import com.domenota.medialoader.data.library.VisualMatch
import com.domenota.medialoader.ui.components.DuplicateResultFilterPolicy

/** Finished Gallery reports expose only categories with actual groups. */
@Composable
internal fun VisualDuplicateResultFilters(
    counts: Map<VisualMatch, Int>,
    selected: VisualMatch?,
    onSelect: (VisualMatch?) -> Unit,
) {
    val kinds = DuplicateResultFilterPolicy.available(VisualMatch.entries, counts)
    if (kinds.isEmpty()) return
    val active = DuplicateResultFilterPolicy.active(selected, counts)
    val options = listOf<VisualMatch?>(null) + kinds
    com.domenota.medialoader.ui.components.DuplicateFilterRows(
        options, active, "visual-result-filters",
        { kind -> "visual-result-filter-${kind?.name ?: "ALL"}" },
        { kind -> when (kind) {
            null -> "Все"
            VisualMatch.EXACT -> "Точные: ${counts[kind] ?: 0}"
            VisualMatch.SIMILAR_PHOTO -> "Похожие фото: ${counts[kind] ?: 0}"
            VisualMatch.SIMILAR_VIDEO -> "Видео: ${counts[kind] ?: 0}"
        } }, onSelect)
}
