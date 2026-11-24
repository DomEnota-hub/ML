package com.domenota.medialoader.ui.screens.music

import androidx.compose.runtime.Composable
import com.domenota.medialoader.data.library.DuplicateKind
import com.domenota.medialoader.ui.components.DuplicateResultFilterPolicy

/** Shared visible-category controls for completed AND provisional groups. */
@Composable
internal fun MusicDuplicateResultFilters(
    counts: Map<DuplicateKind, Int>,
    selected: DuplicateKind?,
    onSelect: (DuplicateKind?) -> Unit,
) {
    val found = DuplicateResultFilterPolicy.available(
        listOf(DuplicateKind.EXACT, DuplicateKind.ACOUSTIC, DuplicateKind.POSSIBLE), counts)
    if (found.isEmpty()) return

    val active = DuplicateResultFilterPolicy.active(selected, counts)
    val options = listOf<DuplicateKind?>(null) + found
    com.domenota.medialoader.ui.components.DuplicateFilterRows(
        options, active, "duplicate-result-filters",
        { kind -> "duplicate-result-filter-${kind?.name ?: "ALL"}" },
        { kind -> when (kind) {
            null -> "Все"
            DuplicateKind.EXACT -> "Точные: ${counts[kind] ?: 0}"
            DuplicateKind.ACOUSTIC -> "Акустика: ${counts[kind] ?: 0}"
            DuplicateKind.POSSIBLE -> "Версии: ${counts[kind] ?: 0}"
        } }, onSelect)
}
