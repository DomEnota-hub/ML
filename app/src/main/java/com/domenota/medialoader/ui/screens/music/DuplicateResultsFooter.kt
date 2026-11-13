package com.domenota.medialoader.ui.screens.music

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.ui.theme.musicPlaybackAccent

/** Anchored outside the scrolling results; the dialog Window owns the system-bar fit. */
@Composable
internal fun DuplicateResultsFooter(count: Int, bytesLabel: String, verifying: Boolean, onTrash: () -> Unit) {
    HorizontalDivider()
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth().testTag("duplicates-footer")) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            val compact = maxWidth > 500.dp
            val stats: @Composable () -> Unit = {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text("Выбрано файлов", style = MaterialTheme.typography.labelSmall)
                        Text("$count", style = MaterialTheme.typography.titleMedium, color = musicPlaybackAccent, modifier = Modifier.testTag("duplicates-count"))
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Освободится", style = MaterialTheme.typography.labelSmall)
                        Text(bytesLabel, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("duplicates-bytes"))
                    }
                }
            }
            val action: @Composable () -> Unit = {
                Button(onClick = onTrash, enabled = count > 0 && !verifying,
                    modifier = Modifier.fillMaxWidth().testTag("duplicates-trash-action"),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)) {
                    Text(if (verifying) "Проверка файлов…" else "В корзину ($count)", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (compact) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) { stats() }
                Box(Modifier.weight(1f)) { action() }
            } else Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { stats(); action() }
        }
    }
}
