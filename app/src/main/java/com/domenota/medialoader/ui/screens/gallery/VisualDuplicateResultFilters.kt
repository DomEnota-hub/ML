package com.domenota.medialoader.ui.screens.gallery

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
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
    Row(
        Modifier.horizontalScroll(rememberScrollState()).testTag("visual-result-filters"),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FilterChip(active == null, { onSelect(null) }, label = { Text("Все") },
            modifier = Modifier.testTag("visual-result-filter-ALL"))
        kinds.forEach { kind ->
            val label = when(kind) {
                VisualMatch.EXACT -> "Точные · ${counts[kind] ?: 0}"
                VisualMatch.SIMILAR_PHOTO -> "Похожие фото · ${counts[kind] ?: 0}"
                VisualMatch.SIMILAR_VIDEO -> "Видео · ${counts[kind] ?: 0}"
            }
            FilterChip(active == kind, { onSelect(kind) }, label = { Text(label) },
                modifier = Modifier.testTag("visual-result-filter-${kind.name}"))
        }
    }
}
