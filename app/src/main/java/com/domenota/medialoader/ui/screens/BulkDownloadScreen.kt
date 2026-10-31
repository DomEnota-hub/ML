package com.domenota.medialoader.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import com.domenota.medialoader.ui.components.NeutralOutlinedButton as OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import com.domenota.medialoader.ui.components.NeutralTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.FileSizeFormatter
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.data.bulk.BulkLinks
import com.domenota.medialoader.data.bulk.BulkPacing
import com.domenota.medialoader.data.bulk.BulkParsedLinks
import com.domenota.medialoader.data.bulk.BulkPhase
import com.domenota.medialoader.data.bulk.BulkRules
import com.domenota.medialoader.data.bulk.BulkState
import com.domenota.medialoader.ui.components.RemotePreview
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun BulkDownloadScreen(
    state: BulkState,
    history: List<BulkState>,
    downloads: List<DownloadEntity>,
    onBack: () -> Unit,
    onStart: (String, BulkRules) -> String?,
    onToggle: (String) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onDiscardPreview: () -> Unit,
    onStopAnalysis: () -> Unit,
    onDisableAnalysisPause: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onRetryFailed: () -> Unit,
    onOpenJob: (String) -> Unit,
    onClearHistory: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember(context) { context.getSharedPreferences("bulk_rules", 0) }
    var input by remember { mutableStateOf("") }
    var rules by remember { mutableStateOf(BulkRules(
        instagram = prefs.getBoolean("instagram", true),
        youtube = prefs.getBoolean("youtube", true),
        vk = prefs.getBoolean("vk", true),
        rutube = prefs.getBoolean("rutube", true),
        photo = prefs.getBoolean("photo", true),
        video = prefs.getBoolean("video", true),
        audio = prefs.getBoolean("audio", false),
        maxHeight = prefs.getInt("maxHeight", 1080).takeIf { it in listOf(480, 720, 1080, 2160) },
        disableAnalysisPause = prefs.getBoolean("disableAnalysisPause", false),
    )) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmStop by remember { mutableStateOf(false) }
    var confirmClearHistory by remember { mutableStateOf(false) }
    var summaryInput by remember { mutableStateOf("") }
    var summary by remember { mutableStateOf<BulkParsedLinks?>(null) }
    var summaryError by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(rules) {
        prefs.edit().putBoolean("instagram", rules.instagram)
            .putBoolean("youtube", rules.youtube).putBoolean("vk", rules.vk)
            .putBoolean("rutube", rules.rutube).putBoolean("photo", rules.photo)
            .putBoolean("video", rules.video).putBoolean("audio", rules.audio)
            .putInt("maxHeight", rules.maxHeight ?: -1)
            .putBoolean("disableAnalysisPause", rules.disableAnalysisPause).apply()
    }
    // Parse off the UI thread so pasted TXT contents cannot freeze the settings screen.
    LaunchedEffect(input) {
        summaryInput = ""
        summary = null
        summaryError = null
        if (input.isNotBlank()) {
            delay(250)
            val parsed = withContext(Dispatchers.Default) { runCatching { BulkLinks.parse(input) } }
            summary = parsed.getOrNull()
            summaryError = parsed.exceptionOrNull()?.message
            summaryInput = input
        }
    }
    LaunchedEffect(state.phase, state.lastInstagramAttemptAt, state.rules.disableAnalysisPause) {
        while (state.phase == BulkPhase.ANALYZING) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }

    if (confirmStop) AlertDialog(
        onDismissRequest = { confirmStop = false },
        title = { Text("Завершить задание?") },
        text = { Text("Ожидающие файлы будут отменены, но уже начавшиеся загрузки " +
            "могут продолжиться. Сохранённые материалы останутся на устройстве.") },
        confirmButton = { TextButton(onClick = { confirmStop = false; onStop() }) { Text("Завершить") } },
        dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("Назад") } },
    )
    if (confirmClearHistory) AlertDialog(
        onDismissRequest = { confirmClearHistory = false },
        title = { Text("Очистить историю заданий?") },
        text = { Text("Будут удалены только записи массовых заданий. Скачанные файлы " +
            "и их обычная история загрузок сохранятся.") },
        confirmButton = { TextButton(onClick = {
            confirmClearHistory = false
            onClearHistory()
        }) { Text("Очистить") } },
        dismissButton = { TextButton(onClick = { confirmClearHistory = false }) { Text("Отмена") } },
    )

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            message = null
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { stream ->
                        val output = ByteArrayOutputStream()
                        val chunk = ByteArray(8192)
                        while (true) {
                            val size = stream.read(chunk)
                            if (size < 0) break
                            if (output.size() + size > BulkLinks.MAX_BYTES)
                                throw IOException("TXT превышает 1 МиБ")
                            output.write(chunk, 0, size)
                        }
                        output.toByteArray()
                    } ?: throw IOException("Не удалось открыть TXT")
                    bytes.toString(Charsets.UTF_8)
                }
            }
            result.onSuccess {
                // Import only populates the form. User confirms analysis explicitly.
                input = it
            }.onFailure {
                message = it.message ?: "Не удалось прочитать TXT"
            }
        }
    }
    val canEdit = state.phase in setOf(BulkPhase.EMPTY, BulkPhase.FINISHED, BulkPhase.STOPPED)
    val inputReady = input.isNotBlank() && summaryInput == input && summaryError == null &&
        summary?.urls?.isNotEmpty() == true
    val canAnalyze = canEdit && inputReady && rules.isUsable()
    val linked = remember(state.candidates, downloads) {
        val ids = state.candidates.mapNotNull { it.downloadId }.toSet()
        downloads.filter { it.id in ids }
    }
    val completed = linked.count { it.state == DownloadState.COMPLETED }
    val failed = linked.count { it.state == DownloadState.FAILED }
    val selected = state.candidates.filter { it.selected }
    val knownBytes = selected.mapNotNull { it.item.sizeBytes?.takeIf { size -> size > 0L } }.sum()
    val unknownSizeCount = selected.count { (it.item.sizeBytes ?: 0L) <= 0L }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(onClick = onBack) { Text("← Настройки") }
        Text("Массовая загрузка", style = MaterialTheme.typography.headlineMedium)

        if (canEdit) {
            Text("Сначала проверьте ссылки и настройки. Анализ не запускает скачивание.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = input,
                onValueChange = { input = it; message = null },
                label = { Text("Ссылки") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(onClick = { picker.launch(arrayOf("text/plain", "text/*")) }) {
                Text("Импортировать TXT")
            }
            if (summaryInput == input && input.isNotBlank()) {
                summary?.let {
                    Text("Поддерживаемых ссылок: ${it.urls.size} · повторов: ${it.duplicates} · " +
                        "неподдерживаемых: ${it.unsupported}", style = MaterialTheme.typography.bodySmall)
                }
                summaryError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } else if (input.isNotBlank()) {
                Text("Проверяем ссылки…", style = MaterialTheme.typography.bodySmall)
            }

            Text("Типы файлов", style = MaterialTheme.typography.titleMedium)
            BulkMultiChoice("Фото", rules.photo) { rules = rules.copy(photo = it) }
            BulkMultiChoice("Видео", rules.video) { rules = rules.copy(video = it) }
            BulkMultiChoice("Аудио / MP3", rules.audio) { rules = rules.copy(audio = it) }
            if (!rules.hasSelectedTypes()) {
                Text("Выберите хотя бы один тип файлов.", color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider()
            Text("Источники", style = MaterialTheme.typography.titleMedium)
            BulkMultiChoice("Instagram", rules.instagram) { rules = rules.copy(instagram = it) }
            BulkMultiChoice("YouTube", rules.youtube) { rules = rules.copy(youtube = it) }
            BulkMultiChoice("VK", rules.vk) { rules = rules.copy(vk = it) }
            BulkMultiChoice("RUTUBE", rules.rutube) { rules = rules.copy(rutube = it) }
            if (!rules.hasSelectedSources()) {
                Text("Выберите хотя бы один источник.", color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider()
            Text("Качество видео", style = MaterialTheme.typography.titleMedium)
            if (rules.instagram) {
                Text("Instagram — лучшее доступное автоматически. Выбор разрешения " +
                    "не требуется: видео не исключается из-за неизвестного качества.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (rules.youtube || rules.vk || rules.rutube) {
                Text("YouTube, VK, RUTUBE — предел качества",
                    style = MaterialTheme.typography.bodyMedium)
                listOf(null to "Лучшее", 480 to "До 480p", 720 to "До 720p",
                    1080 to "До 1080p", 2160 to "До 2160p")
                    .forEach { (height, label) ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .selectable(selected = rules.maxHeight == height, role = Role.RadioButton,
                                    onClick = { rules = rules.copy(maxHeight = height) }),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = rules.maxHeight == height, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Text(label)
                        }
                    }
                Text("Для этих источников предел строгий: если подходящего разрешения " +
                    "нет или оно неизвестно, видео не будет выбрано. Файлы не перекодируются.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            HorizontalDivider()
            Text("Анализ Instagram", style = MaterialTheme.typography.titleMedium)
            BulkMultiChoice("Без паузы между запросами Instagram", rules.disableAnalysisPause) {
                rules = rules.copy(disableAnalysisPause = it)
            }
            Text("Без обычных 20 секунд ожидания между запросами. Instagram может " +
                "временно ограничить обращения к сервису.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { message = onStart(input, rules) },
                enabled = canAnalyze, modifier = Modifier.fillMaxWidth()) {
                Text("Найти файлы")
            }
        }

        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        if (state.phase != BulkPhase.EMPTY) {
            HorizontalDivider()
            Text("Ссылок: ${state.urls.size} · повторов: ${state.duplicates} · " +
                "неподдерживаемых: ${state.unsupported}")
            Text("Обработано: ${state.cursor}/${state.urls.size} · " +
                "найдено файлов: ${state.candidates.size}", style = MaterialTheme.typography.bodyMedium)

            if (state.phase == BulkPhase.ANALYZING) {
                val url = state.urls.getOrNull(state.cursor)
                val provider = url?.let(BulkLinks::provider).orEmpty()
                val leftMs = if (provider == "instagram") BulkPacing.waitMillis(
                    state.lastInstagramAttemptAt, now, state.rules.disableAnalysisPause,
                ) else 0L
                val leftSeconds = (leftMs + 999L) / 1000L
                val stage = when {
                    leftMs > 0L -> "Ожидание перед анализом Instagram: ещё $leftSeconds с"
                    url == null -> "Подготовка результатов"
                    else -> "Анализ · ${bulkProviderName(provider)}"
                }
                Text(stage, style = MaterialTheme.typography.titleMedium)
                LinearProgressIndicator(
                    progress = { if (state.urls.isEmpty()) 0f
                        else state.cursor.toFloat() / state.urls.size },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state.urls.any { BulkLinks.provider(it) == "instagram" }) {
                    BulkMultiChoice("Без паузы между запросами Instagram",
                        state.rules.disableAnalysisPause) { enabled ->
                        rules = rules.copy(disableAnalysisPause = enabled)
                        onDisableAnalysisPause(enabled)
                    }
                    Text(
                        if (state.rules.disableAnalysisPause)
                            "Пауза отключена. Сервис может ограничить запросы."
                        else "Между запросами Instagram сохраняется пауза до 20 секунд.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = onStopAnalysis) { Text("Остановить анализ") }
            }

            if (state.phase == BulkPhase.PREVIEW) {
                val photos = state.candidates.count { it.item.type == MediaType.PHOTO }
                val videos = state.candidates.count { it.item.type == MediaType.VIDEO }
                val audio = state.candidates.count { it.item.type == MediaType.AUDIO }
                Text("Найдено: фото — $photos · видео — $videos · аудио — $audio",
                    style = MaterialTheme.typography.titleSmall)
                Text("Выбрано: ${state.selectedCount} · известный размер: " +
                    FileSizeFormatter.format(knownBytes) +
                    if (unknownSizeCount > 0) " · без данных о размере: $unknownSizeCount" else "",
                    style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onSelectAll) { Text("Выбрать всё") }
                    OutlinedButton(onClick = onClearSelection) { Text("Снять всё") }
                }
                state.candidates.groupBy { it.item.sourcePageUrl.orEmpty() }
                    .entries.forEachIndexed { index, (_, candidates) ->
                        Text("Публикация ${index + 1} · " +
                            bulkProviderName(candidates.first().item.providerId),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        candidates.forEach { candidate ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .toggleable(value = candidate.selected, role = Role.Checkbox,
                                        onValueChange = { onToggle(candidate.key) })
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                BulkChoiceCircle(candidate.selected)
                                candidate.item.previewUrl?.let {
                                    RemotePreview(it, Modifier.size(52.dp))
                                } ?: Text(bulkTypeLabel(candidate.item.type))
                                Column(Modifier.weight(1f)) {
                                    Text(candidate.item.originalName, maxLines = 2)
                                    Text(
                                        listOfNotNull(
                                            bulkProviderName(candidate.item.providerId),
                                            bulkTypeLabel(candidate.item.type),
                                            candidate.item.qualityLabel,
                                            candidate.item.sizeBytes?.takeIf { it > 0L }
                                                ?.let(FileSizeFormatter::format),
                                        ).joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                Button(onClick = onConfirm, enabled = state.selectedCount > 0,
                    modifier = Modifier.fillMaxWidth()) {
                    Text("Скачать выбранное (${state.selectedCount})")
                }
                OutlinedButton(onClick = onDiscardPreview) { Text("Новое задание") }
            }

            if (state.phase in setOf(BulkPhase.QUEUING, BulkPhase.PAUSED,
                    BulkPhase.FINISHED, BulkPhase.STOPPED)) {
                Text("Задание: ${bulkPhaseLabel(state.phase)}",
                    style = MaterialTheme.typography.titleMedium)
                Text("В очереди: ${state.queuedCount} · завершено: $completed · ошибок: $failed")
                if (state.phase == BulkPhase.QUEUING) {
                    OutlinedButton(onClick = onPause) { Text("Приостановить очередь") }
                }
                if (state.phase == BulkPhase.PAUSED) {
                    OutlinedButton(onClick = onResume) { Text("Продолжить очередь") }
                }
                if (state.phase in setOf(BulkPhase.QUEUING, BulkPhase.PAUSED)) {
                    Text("Пауза и завершение задания не останавливают файлы, которые уже скачиваются.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = { confirmStop = true }) { Text("Завершить задание") }
                }
                if (failed > 0 && state.phase == BulkPhase.FINISHED) {
                    OutlinedButton(onClick = onRetryFailed) {
                        Text("Повторить неудачные ($failed)")
                    }
                }
                val byId = downloads.associateBy { it.id }
                state.candidates.filter { it.downloadId != null }
                    .groupBy { it.item.sourcePageUrl ?: it.item.sourceGroupId.orEmpty() }
                    .entries.forEachIndexed { index, (_, candidates) ->
                        Text("Публикация ${index + 1} · " +
                            bulkProviderName(candidates.first().item.providerId),
                            style = MaterialTheme.typography.titleSmall)
                        candidates.forEach { candidate ->
                            candidate.downloadId?.let(byId::get)?.let { row ->
                                Text("${row.originalName} · ${bulkDownloadLabel(row.state)}",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
            }
            state.errors.forEach {
                Text(it, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
            if (state.unsupported > 0) {
                Text("Неподдерживаемых ссылок: ${state.unsupported}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (state.phase !in setOf(BulkPhase.ANALYZING, BulkPhase.QUEUING, BulkPhase.PAUSED)) {
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Text("История заданий", style = MaterialTheme.typography.titleMedium)
            val byId = downloads.associateBy { it.id }
            history.filter { it.jobId.isNotBlank() && it.jobId != state.jobId }
                .take(30).forEach { job ->
                    val date = if (job.createdAtEpochMillis <= 0L) "Дата не сохранена" else
                        SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
                            .format(Date(job.createdAtEpochMillis))
                    val rows = job.candidates.mapNotNull { it.downloadId?.let(byId::get) }
                    val successCount = rows.count { it.state == DownloadState.COMPLETED }
                    val errorCount = rows.count { it.state == DownloadState.FAILED }
                    OutlinedButton(
                        onClick = { onOpenJob(job.jobId) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            Text("$date · ${bulkPhaseLabel(job.phase)}",
                                fontWeight = FontWeight.SemiBold)
                            Text("Ссылок: ${job.urls.size} · файлов: ${job.candidates.size} · " +
                                "скачано: $successCount · ошибок: $errorCount",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            if (history.isNotEmpty()) OutlinedButton(onClick = { confirmClearHistory = true }) {
                Text("Очистить историю массовой загрузки")
            }
        }
    }
}

@Composable
private fun BulkChoiceCircle(selected: Boolean) {
    val main = MaterialTheme.colorScheme.primary
    Box(
        Modifier.size(24.dp)
            .background(if (selected) main else Color.Transparent, CircleShape)
            .border(2.dp, if (selected) main else MaterialTheme.colorScheme.outline, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(
            Icons.Rounded.Check,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(17.dp),
        )
    }
}

@Composable
private fun BulkMultiChoice(label: String, selected: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(value = selected, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BulkChoiceCircle(selected)
        Text(label)
    }
}

private fun bulkProviderName(provider: String): String = when (provider.lowercase(Locale.ROOT)) {
    "instagram" -> "Instagram"
    "youtube" -> "YouTube"
    "vk" -> "VK"
    "rutube" -> "RUTUBE"
    else -> "Источник"
}

private fun bulkTypeLabel(type: MediaType): String = when (type) {
    MediaType.PHOTO -> "Фото"
    MediaType.VIDEO -> "Видео"
    MediaType.AUDIO -> "Аудио"
}

private fun bulkPhaseLabel(phase: BulkPhase): String = when (phase) {
    BulkPhase.EMPTY -> "Не создано"
    BulkPhase.ANALYZING -> "Анализируется"
    BulkPhase.PREVIEW -> "Выбор файлов"
    BulkPhase.QUEUING -> "Загрузка"
    BulkPhase.PAUSED -> "Приостановлено"
    BulkPhase.FINISHED -> "Завершено"
    BulkPhase.STOPPED -> "Остановлено"
}

private fun bulkDownloadLabel(state: DownloadState): String = when (state) {
    DownloadState.QUEUED -> "Ожидает"
    DownloadState.RUNNING -> "Скачивается"
    DownloadState.COMPLETED -> "Завершено"
    DownloadState.FAILED -> "Ошибка"
    DownloadState.CANCELLED -> "Отменено"
}
