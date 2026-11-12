package com.domenota.medialoader.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.domenota.medialoader.data.MediaRepository
import com.domenota.medialoader.data.storage.OldDownloadCandidate
import com.domenota.medialoader.data.storage.OldDownloadInventory
import com.domenota.medialoader.data.storage.OldDownloadsPolicy
import com.domenota.medialoader.ui.components.NeutralTextButton
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Independent legacy-folder operation. No mutation on opening or inventory. */
@Composable
internal fun OldDownloadsRecoverySection(activeDownloadCount: Int) {
    val context = LocalContext.current.applicationContext
    val repository = remember(context) { MediaRepository.get(context) }
    val scope = rememberCoroutineScope()
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    var state by remember { mutableStateOf<OldDownloadInventory?>(null) }
    var status by remember { mutableStateOf("Инвентаризация не запускалась") }
    var busy by remember { mutableStateOf(false) }
    var selectedUri by remember { mutableStateOf<String?>(null) }
    var confirmedSources by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf(false) }
    var showSkipped by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) }
    val available = !busy && activeDownloadCount == 0
    val candidates = state?.candidates.orEmpty().filter { it.problem == null }
    val selected = candidates.firstOrNull { it.uri == selectedUri }
    val pending = state?.pending == true
    fun eligible(item: OldDownloadCandidate) = !item.missingHistory || confirmedSources
    suspend fun inspect() {
        state = repository.inspectOldDownloads()
        status = state!!.message
        if (selectedUri !in state!!.candidates.map { it.uri }) selectedUri = null
    }
    fun run(action: suspend () -> Unit) {
        if (!available) return
        busy = true
        scope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { status = "Операция остановлена: ${e.message ?: e.javaClass.simpleName}" }
            finally { busy = false }
        }
    }
    BackHandler(busy) { }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Text("Восстановление старых загрузок", style = MaterialTheme.typography.titleMedium)
        Text("Download/MediaLoader → Pictures/MediaLoader · аудио → Music/MediaLoader", style = MaterialTheme.typography.bodySmall)
        Text("Исходные даты, URI и содержимое сохраняются. Массовый режим — после одиночной проверки.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        NeutralTextButton(onClick = { run { inspect() } }, enabled = available) { Text("Проверить папку · только чтение") }
        Text(status, style = MaterialTheme.typography.bodySmall)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state != null) {
            val excluded = state!!.skipped.size + state!!.candidates.count { it.problem != null }
            if (excluded > 0) NeutralTextButton(onClick = { showSkipped = true }, enabled = !busy) { Text("Ограничения · $excluded") }
            if (pending) {
                Text("Незавершённая операция. Резерв и журнал сохранены.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                NeutralTextButton(onClick = { confirm = "resume" }, enabled = available) { Text("Продолжить по журналу") }
            } else {
                if (candidates.any { it.missingHistory }) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = confirmedSources, onCheckedChange = { confirmedSources = it }, enabled = available)
                        Text("Подтверждённый остаток ML: фото/видео Instagram, ADDICT и COTTON CANDY YouTube; прочее аудио — неизвестный источник.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text("Шаг 1 · один файл", style = MaterialTheme.typography.titleSmall)
                selected?.let {
                    Text(it.name, style = MaterialTheme.typography.bodyMedium)
                    Text("Исходная дата: ${dateFormat.format(Date(it.receivedMillis))}", style = MaterialTheme.typography.bodySmall)
                }
                NeutralTextButton(onClick = { picker = true }, enabled = available && candidates.isNotEmpty()) { Text("Выбрать файл (${candidates.size})") }
                NeutralTextButton(onClick = { confirm = "trial" }, enabled = available && selected?.let(::eligible) == true) { Text("Перенести один файл") }
                if (state!!.trialReady) {
                    Text("Одиночный перенос: ${state!!.trialName}\nДаты, SHA-256 и история подтверждены.", style = MaterialTheme.typography.bodySmall)
                    if (!state!!.trialConfirmed) {
                        Text("Аппаратная проверка: исходная дата и положение в галерее Xiaomi, наличие в «Загрузках».", style = MaterialTheme.typography.bodySmall)
                        NeutralTextButton(onClick = { confirm = "verify" }, enabled = available) { Text("Подтвердить результат проверки") }
                    }
                }
                Text("Шаг 2 · оставшиеся файлы", style = MaterialTheme.typography.titleSmall)
                val bulk = candidates.filter(::eligible)
                Text(if (state!!.trialConfirmed) "Массовый режим доступен · ${bulk.size} файлов" else "Массовый режим закрыт", style = MaterialTheme.typography.bodySmall)
                NeutralTextButton(onClick = { confirm = "bulk" }, enabled = available && state!!.trialConfirmed && bulk.isNotEmpty()) { Text("Восстановить все оставшиеся (${bulk.size})") }
            }
        }
    }
    if (picker) AlertDialog(onDismissRequest = { picker = false }, title = { Text("Файл для одиночного переноса") },
        text = { LazyColumn(Modifier.heightIn(max = 360.dp)) { items(candidates, key = { it.uri }) { item ->
            Row(Modifier.fillMaxWidth().clickable { selectedUri = item.uri; picker = false }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = item.uri == selectedUri, onClick = { selectedUri = item.uri; picker = false })
                Column(Modifier.weight(1f)) {
                    Text(item.name)
                    Text("${dateFormat.format(Date(item.receivedMillis))} · ${item.bytes} Б", style = MaterialTheme.typography.bodySmall)
                    Text(if (item.missingHistory) "История отсутствует" else "Запись истории найдена", style = MaterialTheme.typography.bodySmall)
                }
            }
        } } }, confirmButton = { NeutralTextButton(onClick = { picker = false }) { Text("Закрыть") } })
    if (showSkipped) AlertDialog(onDismissRequest = { showSkipped = false }, title = { Text("Ограничения переноса") },
        text = { LazyColumn(Modifier.heightIn(max = 360.dp)) { items(state?.skipped.orEmpty() + state?.candidates.orEmpty().mapNotNull { c -> c.problem?.let { "${c.name} · $it" } }) { Text(it, Modifier.padding(vertical = 6.dp), style = MaterialTheme.typography.bodySmall) } } },
        confirmButton = { NeutralTextButton(onClick = { showSkipped = false }) { Text("Закрыть") } })
    confirm?.let { action ->
        val message = when(action) {
            "trial" -> "Один файл: ${selected?.name.orEmpty()}. Перенос существующего URI с проверкой исходных дат и восстановлением истории."
            "verify" -> "Дата, положение в галерее Xiaomi и запись в «Загрузках» проверены. Массовый режим станет доступен."
            "bulk" -> "Перенос ${candidates.count(::eligible)} оставшихся файлов. При несовпадении дат или содержимого серия остановится."
            else -> "Продолжение сохранённой операции с последнего подтверждённого этапа."
        }
        AlertDialog(onDismissRequest = { confirm = null }, title = { Text("Подтверждение операции") }, text = { Text(message) },
            dismissButton = { NeutralTextButton(onClick = { confirm = null }) { Text("Отмена") } },
            confirmButton = { NeutralTextButton(onClick = {
                confirm = null
                run {
                    val result = when(action) {
                        "trial" -> repository.runOldDownloadsRecovery(listOf(requireNotNull(selected)), true, confirmedSources) { status = it }
                        "bulk" -> repository.runOldDownloadsRecovery(candidates.filter(::eligible), false, confirmedSources) { status = it }
                        "resume" -> repository.resumeOldDownloadsRecovery { status = it }
                        else -> { repository.confirmOldDownloadsTrial(); "Одиночная проверка подтверждена" }
                    }
                    inspect(); status = result
                }
            }, enabled = available) { Text("Подтвердить") } })
    }
    if (busy) AlertDialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text("Проверка / перенос") }, text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(status) } }, confirmButton = {})
}
