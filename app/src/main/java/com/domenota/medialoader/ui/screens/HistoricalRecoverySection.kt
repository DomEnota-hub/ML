package com.domenota.medialoader.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Collections
import androidx.compose.material.icons.rounded.FindInPage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.domenota.medialoader.data.MediaRepository
import com.domenota.medialoader.data.storage.HistoricalRecoveryItem
import com.domenota.medialoader.data.storage.HistoricalRecoveryState
import com.domenota.medialoader.ui.components.NeutralTextButton
import com.domenota.medialoader.ui.theme.SettingIconTone
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Real-file replacement. Inspection is the only automatic action. */
@Composable
internal fun HistoricalRecoverySection(activeDownloadCount: Int) {
    val context = LocalContext.current.applicationContext
    val repository = remember(context) { MediaRepository.get(context) }
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<HistoricalRecoveryState?>(null) }
    var inspecting by remember { mutableStateOf(true) }
    var running by remember { mutableStateOf(false) }
    var status by rememberSaveable { mutableStateOf("Читаем список файлов и журнал восстановления…") }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var showPicker by remember { mutableStateOf(false) }
    var trialConfirmation by remember { mutableStateOf<HistoricalRecoveryItem?>(null) }
    var batchConfirmation by remember { mutableStateOf<List<String>?>(null) }
    var resumeConfirmation by remember { mutableStateOf(false) }
    val candidates = state?.items.orEmpty()
    val selected = candidates.firstOrNull { it.downloadId == selectedId } ?: candidates.firstOrNull()
    val pending = state?.pending == true
    val available = state != null && !inspecting && !running && activeDownloadCount == 0
    val canTrial = available && !pending && selected != null
    val canBatch = available && !pending && state?.trialPassed == true && candidates.isNotEmpty()
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    fun date(item: HistoricalRecoveryItem): String = dateFormat.format(Date(item.historicalMillis))

    suspend fun inspect(updateStatus: Boolean) {
        try {
            val updated = repository.inspectHistoricalReplacement()
            state = updated
            if (updateStatus) status = updated.message
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            state = null
            status = "Не удалось прочитать состояние: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            inspecting = false
        }
    }

    fun refresh() {
        if (inspecting || running) return
        inspecting = true
        scope.launch { inspect(updateStatus = true) }
    }

    fun run(ids: List<String>, trial: Boolean, resume: Boolean = false) {
        if (running || inspecting || state == null || activeDownloadCount > 0) return
        if (resume && state?.pending != true) return
        if (!resume && state?.pending == true) return
        if (!resume && !trial && state?.trialPassed != true) return
        if (!resume && (ids.isEmpty() || (trial && ids.size != 1))) return
        running = true
        status = "Подготавливаем восстановление…"
        scope.launch {
            try {
                status = if (resume) repository.resumeHistoricalReplacement { status = it }
                else repository.runHistoricalReplacement(ids, trial) { status = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status = "Операция остановлена: ${e.message ?: e.javaClass.simpleName}. " +
                    "Состояние проверяется по сохранённому журналу."
            } finally {
                try {
                    // The persisted receipt, never local UI state, unlocks batch recovery.
                    inspecting = true
                    inspect(updateStatus = false)
                } finally {
                    running = false
                }
            }
        }
    }

    LaunchedEffect(repository) { inspect(updateStatus = true) }
    BackHandler(enabled = running) { /* Keep the operation and its progress visible. */ }

    Column(Modifier.padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Восстановление исторических дат", style = MaterialTheme.typography.titleMedium)
        Text("Дата берётся из истории загрузок MediaLoader и может отличаться от даты съёмки. " +
            "Восстановление заменяет реальные файлы.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(if (state == null) "Список файлов ещё не получен" else
            "Доступно: ${candidates.size} · фото: ${candidates.count { it.mime.startsWith("image/") }} · " +
                "видео: ${candidates.count { it.mime.startsWith("video/") }}",
            style = MaterialTheme.typography.bodyMedium)
        NeutralTextButton(onClick = ::refresh, enabled = !inspecting && !running) {
            Text(if (inspecting) "Читаем список…" else "Обновить список · только чтение")
        }
        if (activeDownloadCount > 0) Text(
            "Запуск недоступен: выполняются загрузки ($activeDownloadCount). Дождитесь их завершения.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)

        RecoveryModeCard(Icons.Rounded.FindInPage, "Проверить на одном файле",
            "Шаг 1 · один выбранный оригинал", SettingIconTone.BLUE) {
            Text("Сначала выполните одну проверку. Успешный результат сохранится и откроет массовый режим в этой сборке.",
                style = MaterialTheme.typography.bodySmall)
            if (selected != null) {
                Text(selected.name, style = MaterialTheme.typography.bodyMedium)
                Text("Дата из истории: ${date(selected)}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else Text("Нет файлов для восстановления", style = MaterialTheme.typography.bodySmall)
            NeutralTextButton(onClick = { showPicker = true },
                enabled = available && !pending && candidates.isNotEmpty()) { Text("Выбрать файл") }
            NeutralTextButton(onClick = { trialConfirmation = selected }, enabled = canTrial) {
                Text("Проверить выбранный файл")
            }
        }
        RecoveryModeCard(Icons.Rounded.Collections, "Массовое восстановление",
            "Шаг 2 · все оставшиеся файлы по очереди", SettingIconTone.AMBER) {
            Text(when {
                pending -> "Сначала продолжите незавершённую операцию по журналу."
                state?.trialPassed == true -> "Успешная проверка сохранена. Массовое восстановление запускается только вашей кнопкой."
                else -> "Заблокировано до успешной проверки одного реального файла. Дополнительная сборка не требуется."
            }, style = MaterialTheme.typography.bodySmall)
            Text("Файлов к замене: ${candidates.size}. Связи истории, избранного и альбомов сохраняются.",
                style = MaterialTheme.typography.bodySmall)
            NeutralTextButton(onClick = { batchConfirmation = candidates.map { it.downloadId } },
                enabled = canBatch) { Text("Восстановить все оставшиеся (${candidates.size})") }
        }

        Text(status, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (pending) {
            Text("Есть незавершённая операция. Продолжение использует сохранённый журнал.",
                style = MaterialTheme.typography.bodySmall)
            NeutralTextButton(onClick = { resumeConfirmation = true }, enabled = available) {
                Text("Продолжить по журналу")
            }
            if (state?.canCancel == true) NeutralTextButton(enabled = available, onClick = {
                running = true
                scope.launch {
                    try { status = repository.cancelHistoricalReplacement() }
                    catch (error: Exception) { status = "Отмена остановлена: ${error.message}" }
                    finally { inspect(updateStatus = false); running = false }
                }
            }) { Text("Отменить оставшуюся очередь до изменения оригинала") }
        }
    }

    if (showPicker) AlertDialog(
        onDismissRequest = { showPicker = false },
        title = { Text("Выберите один файл") },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                items(candidates, key = { it.downloadId }) { item ->
                    Column(Modifier.fillMaxWidth().clickable {
                        selectedId = item.downloadId
                        showPicker = false
                    }.padding(vertical = 12.dp)) {
                        Text(item.name, style = MaterialTheme.typography.bodyMedium)
                        Text("${if (item.mime.startsWith("video/")) "Видео" else "Фото"} · ${date(item)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = { NeutralTextButton(onClick = { showPicker = false }) { Text("Закрыть") } },
    )

    trialConfirmation?.let { item ->
        AlertDialog(onDismissRequest = { trialConfirmation = null },
            title = { Text("Заменить один реальный файл?") },
            text = { Text("Файл: ${item.name}\nДата из истории: ${date(item)}\n\n" +
                "Будет создана замена с исторической датой. Оригинал удаляется только после проверки замены. " +
                "Связи истории, избранного и альбомов сохраняются. После проверьте файл в галерее. " +
                "Массовое восстановление автоматически не запускается.") },
            confirmButton = { NeutralTextButton(enabled = canTrial && candidates.any { it.downloadId == item.downloadId }, onClick = {
                trialConfirmation = null
                run(listOf(item.downloadId), trial = true)
            }) { Text("Заменить и проверить") } },
            dismissButton = { NeutralTextButton(onClick = { trialConfirmation = null }) { Text("Отмена") } })
    }
    batchConfirmation?.let { ids ->
        AlertDialog(onDismissRequest = { batchConfirmation = null },
            title = { Text("Заменить оригиналы: ${ids.size}?") },
            text = { Text("Файлы будут восстановлены по очереди с датами из истории MediaLoader. " +
                "Каждый оригинал удаляется только после проверки его замены. " +
                "Связи истории, избранного и альбомов сохраняются. " +
                "Ход операции сохраняется в журнале для продолжения после прерывания.") },
            confirmButton = { NeutralTextButton(enabled = canBatch, onClick = {
                batchConfirmation = null
                run(ids, trial = false)
            }) { Text("Начать замену ${ids.size} файлов") } },
            dismissButton = { NeutralTextButton(onClick = { batchConfirmation = null }) { Text("Отмена") } })
    }
    if (resumeConfirmation) AlertDialog(
        onDismissRequest = { resumeConfirmation = false },
        title = { Text("Продолжить восстановление?") },
        text = { Text("Будет продолжена уже подтверждённая операция из журнала. " +
            "Файлы обрабатываются по очереди; оригинал удаляется только после проверки замены. " +
            "Связи истории, избранного и альбомов сохраняются.") },
        confirmButton = { NeutralTextButton(enabled = available && pending, onClick = {
            resumeConfirmation = false
            run(emptyList(), trial = false, resume = true)
        }) { Text("Продолжить") } },
        dismissButton = { NeutralTextButton(onClick = { resumeConfirmation = false }) { Text("Отмена") } },
    )
    if (running) AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text("Восстановление выполняется") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(status)
            Text("Дождитесь завершения. Если приложение закроется, используйте «Продолжить по журналу».",
                style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = {},
    )
}

@Composable
private fun RecoveryModeCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    tone: SettingIconTone,
    content: @Composable ColumnScope.() -> Unit,
) {
    val swatch = tone.swatch(MaterialTheme.colorScheme.background.luminance() < 0.5f)
    Card(Modifier.fillMaxWidth(), border = BorderStroke(1.dp, swatch.foreground.copy(alpha = 0.5f)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        SettingRow(icon, title, subtitle, grouped = true, iconTone = tone)
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}
