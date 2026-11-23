package com.domenota.medialoader.ui.screens.music

import androidx.compose.foundation.layout.*
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
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
    Column(Modifier.fillMaxWidth().testTag("duplicate-result-filters"),
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        options.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { kind ->
                    val label = when (kind) {
                        null -> "Все"
                        DuplicateKind.EXACT -> "Точные · ${counts[kind] ?: 0}"
                        DuplicateKind.ACOUSTIC -> "Акустика · ${counts[kind] ?: 0}"
                        DuplicateKind.POSSIBLE -> "Версии · ${counts[kind] ?: 0}"
                    }
                    FilterChip(
                        active == kind, { onSelect(kind) },
                        label = { Text(label) },
                        modifier = Modifier.weight(1f)
                            .testTag("duplicate-result-filter-${kind?.name ?: "ALL"}"),
                    )
                }
            }
        }
    }
}
