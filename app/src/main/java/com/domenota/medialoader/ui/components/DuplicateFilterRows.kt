package com.domenota.medialoader.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Equal-width categories. A short final row is centered, never stretched. */
@Composable
internal fun <K> DuplicateFilterRows(
    options: List<K?>,
    active: K?,
    tag: String,
    optionTag: (K?) -> String,
    label: (K?) -> String,
    onSelect: (K?) -> Unit,
) {
    if (options.isEmpty()) return
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth().testTag(tag)) {
        val columns = when {
            maxWidth < 220.dp || fontScale >= 1.7f -> 1
            options.size == 3 && maxWidth >= 300.dp && fontScale <= 1.2f -> 3
            options.size == 4 && maxWidth >= 560.dp && fontScale <= 1.2f -> 4
            else -> minOf(2, options.size)
        }
        val gap = 6.dp
        val chipWidth = (maxWidth - gap * (columns - 1)) / columns
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            options.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally)) {
                    row.forEach { kind ->
                        FilterChip(selected = active == kind, onClick = { onSelect(kind) },
                            label = { Text(label(kind), Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
                            modifier = Modifier.width(chipWidth).testTag(optionTag(kind)))
                    }
                }
            }
        }
    }
}
