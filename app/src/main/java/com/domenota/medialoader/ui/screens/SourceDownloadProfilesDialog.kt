package com.domenota.medialoader.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ExpandMore
import com.domenota.medialoader.ui.components.SourceIcon
import com.domenota.medialoader.data.share.InstagramShareSettings
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
internal fun SourceDownloadProfilesDialog(selectedSafFolder: Boolean, generalFolder:String="", onDismiss: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { SourceDownloadProfiles(context) }
    val scope = rememberCoroutineScope()
    val general=remember(context){VideoPreferences(context)}
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
                    Text("Общие параметры действуют до индивидуального переопределения.", style = MaterialTheme.typography.bodySmall,
                        color=MaterialTheme.colorScheme.onSurfaceVariant)
                    SourceDownloadProfiles.providers.forEach { (id, label) ->
                        val profile = remember(id, revision) { store.profile(id) }
                        Surface(Modifier.fillMaxWidth().padding(top=8.dp),shape=RoundedCornerShape(14.dp),
                            color=MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.45f)){
                            Row(Modifier.fillMaxWidth().clickable {
                                draft=store.profile(id);folderText=draft.folder.orEmpty();source=id;error=false
                            }.padding(10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
                                SourceIcon(id,size=38.dp)
                                Column(Modifier.weight(1f)){
                                    Text(label,style=MaterialTheme.typography.titleMedium)
                                    val count=listOf(profile.height,profile.direction,profile.format,profile.mode,profile.folder,profile.confirmation).count{it!=null}
                                    Text(if(count==0)"Общие настройки" else "Индивидуальные: $count",
                                        style=MaterialTheme.typography.bodySmall,color=if(count==0)MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
                                }
                                Icon(Icons.Rounded.ChevronRight,null,tint=MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                } else {
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
                        SourceIcon(provider,40.dp)
                        Column{Text("Индивидуальные параметры",style=MaterialTheme.typography.titleSmall)
                            Text("Общие значения наследуются",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                    }
                    Spacer(Modifier.height(10.dp))
                    if (SourceDownloadProfiles.supportsQuality(provider)) {
                        ProfileSection("Качество и формат")
                        ProfileChoice("Качество", draft.height, listOf(null to VideoPreferences.qualityLabel(general.preferredHeight)) +
                            SourceDownloadProfiles.heights.map { it to VideoPreferences.qualityLabel(it.takeIf { h -> h > 0 }) },
                            !busy) { draft = draft.copy(height = it) }
                        ProfileChoice("Если качества нет", draft.direction,
                            listOf(null to general.qualityDirection.label) + VideoQualityDirection.entries.map { it to it.label },
                            !busy) { draft = draft.copy(direction = it) }
                        ProfileChoice("Формат", draft.format,
                            listOf(null to "Выбор источника") + SourceFormat.entries.map { it to it.label },
                            !busy) { draft = draft.copy(format = it) }
                    }
                    ProfileSection("Загрузка")
                    if (SourceDownloadProfiles.supportsAuto(provider)) {
                        ProfileChoice("Режим", draft.mode,
                            listOf(null to if(InstagramShareSettings.isAutomatic(context)) "Автоматический" else "Ручной") + SourceDownloadMode.entries.map { it to it.label },
                            !busy) { draft = draft.copy(mode = it) }
                    }
                    ProfileChoice("Подтверждение", draft.confirmation, listOf(null to "Показывать",
                        true to "Показывать", false to "Без диалога имени"), !busy) { draft = draft.copy(confirmation = it) }
                    ProfileSection("Папка")
                    Text(if(selectedSafFolder||folderText.isBlank()) "Общая · $generalFolder" else "Индивидуальная",
                        style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(folderText, { folderText = it }, Modifier.fillMaxWidth().padding(top = 8.dp),
                        label = { Text("Своя папка") }, placeholder = { Text("Общая папка") }, singleLine = true,
                        enabled = !busy && !selectedSafFolder, isError = !folderValid)
                    Text(if (selectedSafFolder) "Сейчас используется общая папка, выбранная в проводнике."
                        else "Pictures для фото и видео, Music для аудио. Пустое поле — общая папка.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (SourceDownloadProfiles.supportsAuto(provider) && (draft.confirmation==true || draft.format==SourceFormat.AUDIO))
                        Text("Фактический режим: ручной (формат или подтверждение).",style=MaterialTheme.typography.bodySmall)
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
    Column(Modifier.fillMaxWidth().padding(vertical=2.dp)){
        Row(Modifier.fillMaxWidth().heightIn(min=48.dp).clickable(enabled=enabled){open=true}.padding(vertical=5.dp),
            verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
            Column(Modifier.weight(1f)){
                Text(label,style=MaterialTheme.typography.bodyMedium)
                Text(if(selected==null)"Общие настройки" else "Индивидуально",style=MaterialTheme.typography.labelSmall,
                    color=if(selected==null)MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
            }
            Column(Modifier.weight(1f)){
                Text(options.firstOrNull{it.first==selected}?.second ?: "Не задано",style=MaterialTheme.typography.bodySmall)
            }
            Box{
                Icon(Icons.Rounded.ExpandMore,null,tint=MaterialTheme.colorScheme.onSurfaceVariant)
                DropdownMenu(expanded=open,onDismissRequest={open=false}){
                    options.forEach{(value,title)->DropdownMenuItem(text={Text(if(value==null)"Общие · $title" else title)},
                        onClick={onSelect(value);open=false})}
                }
            }
        }
        HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.5f))
    }
}

@Composable private fun ProfileSection(title:String){
    Text(title,Modifier.padding(top=12.dp,bottom=4.dp),style=MaterialTheme.typography.titleSmall,
        color=MaterialTheme.colorScheme.onSurfaceVariant)
}
