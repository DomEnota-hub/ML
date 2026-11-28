package com.domenota.medialoader.ui.screens

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Download
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.data.download.DownloadNetworkMode
import com.domenota.medialoader.data.download.DownloadNetworkPolicy
import com.domenota.medialoader.ui.components.NeutralTextButton as TextButton

@Composable
internal fun DownloadNetworkDialog(current: DownloadNetworkPolicy, onDismiss: () -> Unit,
    onSave: (DownloadNetworkPolicy) -> Unit) {
    var draft by remember(current) { mutableStateOf(current) }
    var thresholdMenu by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Условия сети") },
        text = {
            Column(Modifier.heightIn(max=440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Column(Modifier.selectableGroup(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    DownloadNetworkMode.entries.forEach { mode ->
                        val detail=when(mode){
                            DownloadNetworkMode.ANY->"Wi-Fi и мобильная сеть"
                            DownloadNetworkMode.WIFI->"Все загрузки ждут Wi-Fi"
                            DownloadNetworkMode.LARGE_WIFI->"Ограничение для файлов заданного размера"
                        }
                        val icon=when(mode){
                            DownloadNetworkMode.ANY->Icons.Rounded.SwapHoriz
                            DownloadNetworkMode.WIFI->Icons.Rounded.Wifi
                            DownloadNetworkMode.LARGE_WIFI->Icons.Rounded.Download
                        }
                        SettingsChoiceCard(mode.label,detail,draft.mode==mode,icon,{draft=draft.copy(mode=mode)})
                    }
                }
                if (draft.mode == DownloadNetworkMode.LARGE_WIFI) {
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("Размер от", Modifier.weight(1f))
                        Box {
                            TextButton(onClick = { thresholdMenu = true }) {
                                Text(DownloadNetworkPolicy.thresholdLabel(draft.thresholdMb) + " ▾")
                            }
                            DropdownMenu(expanded = thresholdMenu, onDismissRequest = { thresholdMenu = false }) {
                                DownloadNetworkPolicy.thresholds.forEach { size ->
                                    DropdownMenuItem(text = { Text(DownloadNetworkPolicy.thresholdLabel(size)) },
                                        onClick = { draft = draft.copy(thresholdMb = size); thresholdMenu = false })
                                }
                            }
                        }
                    }
                    Text("Файлы неизвестного размера тоже ожидают Wi-Fi.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("Условия проверяются перед началом загрузки. Уже начатые передачи не прерываются.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp))
            }
        }, confirmButton = { TextButton(onClick = { onSave(draft) }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } })
}
