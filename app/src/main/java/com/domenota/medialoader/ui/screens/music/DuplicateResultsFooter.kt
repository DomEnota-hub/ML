package com.domenota.medialoader.ui.screens.music

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.ui.theme.musicPlaybackAccent

/** Anchored outside the weighted scrolling list inside the Activity's safe viewport. */
@Composable
internal fun DuplicateResultsFooter(count: Int, bytesLabel: String, verifying: Boolean, onTrash: () -> Unit) {
    HorizontalDivider(Modifier.fillMaxWidth().testTag("duplicates-footer-divider"))
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth().testTag("duplicates-footer")) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
            val oneRow = maxWidth >= 300.dp && LocalDensity.current.fontScale <= 1.4f
            val stats: @Composable () -> Unit = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Выбрано:", style = MaterialTheme.typography.labelMedium)
                        Text("$count", style = MaterialTheme.typography.titleMedium, color = musicPlaybackAccent, modifier = Modifier.testTag("duplicates-count"))
                    }
                    Text(bytesLabel, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("duplicates-bytes"))
                }
            }
            val action: @Composable () -> Unit = {
                Button(onClick = onTrash, enabled = count > 0 && !verifying,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("duplicates-trash-action"),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)) {
                    Text(if (verifying) "Проверка…" else "В корзину")
                }
            }
            if (oneRow) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) { stats() }
                Box(Modifier.weight(1f)) { action() }
            } else Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { stats(); action() }
        }
    }
}
