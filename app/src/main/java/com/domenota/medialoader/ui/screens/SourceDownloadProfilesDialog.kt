package com.domenota.medialoader.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.core.model.VideoQualityDirection
import com.domenota.medialoader.data.download.*
import com.domenota.medialoader.data.preferences.VideoPreferences
import com.domenota.medialoader.ui.components.NeutralTextButton as TextButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun SourceDownloadProfilesDialog(selectedSafFolder: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { SourceDownloadProfiles(context) }
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf(SourceDownloadProfile()) }
    var folderText by remember { mutableStateOf("") }
    var revision by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val provider = source
    val folderValid = folderText.isBlank() || SourceDownloadProfiles.validFolder(folderText.trim())
    AlertDialog(
        onDismissRequest = { if (!busy) { if (provider != null) source = null else onDismiss() } },
        title = { Text(provider?.let { SourceDownloadProfiles.providers.getValue(it) } ?: "Профили источников") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                if (provider == null) {
                    Text("По умолчанию используются общие настройки загрузок.", style = MaterialTheme.typography.bodySmall)
                    SourceDownloadProfiles.providers.forEach { (id, label) ->
                        val profile = remember(id, revision) { store.profile(id) }
                        Row(Modifier.fillMaxWidth().clickable {
                            draft = store.profile(id); folderText = draft.folder.orEmpty(); source = id; error = false
                        }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(label)
                                Text(if (profile == SourceDownloadProfile()) "Общие настройки" else "Индивидуальный профиль",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text("›", style = MaterialTheme.typography.titleLarge)
                        }
                        HorizontalDivider()
                    }
                } else {
                    if (SourceDownloadProfiles.supportsQuality(provider)) {
                        ProfileChoice("Качество", draft.height, listOf(null to "Общие настройки") +
                            SourceDownloadProfiles.heights.map { it to VideoPreferences.qualityLabel(it.takeIf { h -> h > 0 }) },
                            !busy) { draft = draft.copy(height = it) }
                        ProfileChoice("Если качества нет", draft.direction,
                            listOf(null to "Общие настройки") + VideoQualityDirection.entries.map { it to it.label },
                            !busy) { draft = draft.copy(direction = it) }
                        ProfileChoice("Формат", draft.format,
                            listOf(null to "Общие настройки") + SourceFormat.entries.map { it to it.label },
                            !busy) { draft = draft.copy(format = it) }
                    }
                    if (SourceDownloadProfiles.supportsAuto(provider)) {
                        ProfileChoice("Режим", draft.mode,
                            listOf(null to "Общие настройки") + SourceDownloadMode.entries.map { it to it.label },
                            !busy) { draft = draft.copy(mode = it) }
                    }
                    ProfileChoice("Подтверждение", draft.confirmation, listOf(null to "Общие настройки",
                        true to "Показывать", false to "Без диалога имени"), !busy) { draft = draft.copy(confirmation = it) }
                    OutlinedTextField(folderText, { folderText = it }, Modifier.fillMaxWidth().padding(top = 8.dp),
                        label = { Text("Своя папка") }, placeholder = { Text("Общая папка") }, singleLine = true,
                        enabled = !busy && !selectedSafFolder, isError = !folderValid)
                    Text(if (selectedSafFolder) "Сейчас используется общая папка, выбранная в проводнике."
                        else "Pictures для фото и видео, Music для аудио. Пустое поле — общая папка.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (provider == "youtube" && draft.format == SourceFormat.AUDIO && draft.mode != SourceDownloadMode.MANUAL)
                        Text("Автоматическая загрузка YouTube доступна для видео; аудио выбирается вручную.",
                            style = MaterialTheme.typography.bodySmall)
                    if (error) Text("Не удалось сохранить профиль", color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { draft = SourceDownloadProfile(); folderText = ""; error = false }, enabled = !busy) {
                        Text("Вернуть общие настройки")
                    }
                }
            }
        },
        confirmButton = {
            if (provider == null) TextButton(onClick = onDismiss) { Text("Закрыть") }
            else TextButton(enabled = folderValid && !busy, onClick = {
                busy = true
                val value = draft.copy(folder = folderText.trim().takeIf { it.isNotEmpty() })
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { store.save(provider, value) }
                        revision++; source = null
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { error = true }
                    finally { busy = false }
                }
            }) { Text("Сохранить") }
        },
        dismissButton = {
            if (provider != null) TextButton(onClick = { source = null }, enabled = !busy) { Text("Отмена") }
        },
    )
}

@Composable
private fun <T> ProfileChoice(label: String, selected: T?, options: List<Pair<T?, String>>,
    enabled: Boolean, onSelect: (T?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Box {
            TextButton(onClick = { open = true }, enabled = enabled) {
                Text(options.firstOrNull { it.first == selected }?.second ?: "Общие настройки")
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (value, title) -> DropdownMenuItem(text = { Text(title) },
                    onClick = { onSelect(value); open = false }) }
            }
        }
    }
}
