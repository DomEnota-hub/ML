package com.domenota.medialoader.ui.screens

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import com.domenota.medialoader.ui.components.NeutralOutlinedButton as OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import com.domenota.medialoader.ui.components.NeutralTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.core.diagnostics.DiagnosticAssessment
import com.domenota.medialoader.core.diagnostics.DiagnosticCheckSpec
import com.domenota.medialoader.core.diagnostics.DiagnosticComponent
import com.domenota.medialoader.core.diagnostics.DiagnosticFinding
import com.domenota.medialoader.core.diagnostics.DiagnosticRun
import com.domenota.medialoader.core.diagnostics.SafeDiagnosticReport
import com.domenota.medialoader.core.diagnostics.DiagnosticLabels
import com.domenota.medialoader.core.diagnostics.DiagnosticMode
import com.domenota.medialoader.core.diagnostics.DiagnosticScope
import com.domenota.medialoader.core.diagnostics.DiagnosticState
import com.domenota.medialoader.core.diagnostics.DiagnosticSessionRules
import com.domenota.medialoader.core.diagnostics.DiagnosticPresentation
import com.domenota.medialoader.core.diagnostics.DiagnosticModeChecks
import com.domenota.medialoader.core.diagnostics.FullDiagnosticInput
import com.domenota.medialoader.core.diagnostics.FaultExplanations
import com.domenota.medialoader.core.diagnostics.FaultRecord
import com.domenota.medialoader.core.diagnostics.FaultStatus
import com.domenota.medialoader.core.diagnostics.FaultSeverity
import com.domenota.medialoader.data.MediaRepository
import com.domenota.medialoader.data.diagnostics.DiagnosticJournalRunner
import com.domenota.medialoader.data.diagnostics.DiagnosticControlSettings
import com.domenota.medialoader.data.diagnostics.FaultJournalStore
import com.domenota.medialoader.data.diagnostics.MediaDiagnosticRegistry
import com.domenota.medialoader.data.diagnostics.RecentDiagnosticRunStore
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Explicit diagnostics screen: constructing it doesn't probe a source.
 * Network checks require a one-time confirmation for EVERY network run.
 */
@Composable
fun DiagnosticsScreen(
    onBack: () -> Unit,
    versionName: String,
    versionCode: Int,
) {
    val context = LocalContext.current
    val controls = remember(context) { DiagnosticControlSettings(context) }
    val repository = remember(context) { MediaRepository.get(context) }
    val fullInputState = remember { mutableStateOf(FullDiagnosticInput.EMPTY) }
    val coordinator = remember(context, repository) {
        MediaDiagnosticRegistry.create(context, repository,
            fullInput = { fullInputState.value })
    }
    val specs = remember(coordinator) { coordinator.specifications }
    val journal = remember(context) { FaultJournalStore(context) }
    val recentRun = remember(context, coordinator) { RecentDiagnosticRunStore(context, specs) }
    val runner = remember(coordinator, journal) { DiagnosticJournalRunner(coordinator, journal) }
    val scope = rememberCoroutineScope()

    var findings by remember { mutableStateOf<List<DiagnosticFinding>>(emptyList()) }
    var lastCompletedRun by remember { mutableStateOf<DiagnosticRun?>(null) }
    var latestRunReadable by remember { mutableStateOf(true) }
    var journalReadable by remember { mutableStateOf(true) }
    var pendingMarkdown by remember { mutableStateOf<String?>(null) }
    var faults by remember { mutableStateOf<List<FaultRecord>>(emptyList()) }
    var lastCheckedAt by remember { mutableStateOf<Long?>(null) }
    var lastMode by remember { mutableStateOf<DiagnosticMode?>(null) }
    var activeMode by remember { mutableStateOf<DiagnosticMode?>(null) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var checkedCount by remember { mutableStateOf(0) }
    var running by remember { mutableStateOf(false) }
    var currentJob by remember { mutableStateOf<Job?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var askNetwork by remember { mutableStateOf(false) }
    var askFull by remember { mutableStateOf(false) }
    var showFullSettings by remember { mutableStateOf(false) }
    var askClearFaults by remember { mutableStateOf(false) }
    var controlLinks by remember(controls) { mutableStateOf(controls.links) }
    var controlArtist by remember(controls) { mutableStateOf(controls.artist) }
    var controlTitle by remember(controls) { mutableStateOf(controls.title) }
    var expanded by remember { mutableStateOf(setOf(DiagnosticComponent.SOURCES)) }

    // System-owned file picker: no broad storage permission or auto-share/upload.
    val reportSaver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        val content = pendingMarkdown
        pendingMarkdown = null
        if (uri != null && content != null) {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val output = context.contentResolver.openOutputStream(uri, "w")
                            ?: throw IOException("Document provider refused writable stream")
                        output.use { it.write(content.toByteArray(Charsets.UTF_8)) }
                    }
                    notice = "Диагностический отчёт сохранён в выбранный файл."
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    notice = "Не удалось сохранить отчёт. " +
                        "Проверьте выбранную папку: файл может быть неполным."
                }
            }
        }
    }

    fun exportReport() {
        if (running || pendingMarkdown != null) return
        try {
            // Use the last completed run only; current on-screen findings may be
            // partial if a scan was cancelled. No active probe is started here.
            pendingMarkdown = SafeDiagnosticReport.render(SafeDiagnosticReport.Input(
                specs = specs,
                lastCompletedRun = lastCompletedRun,
                incidents = faults,
                generatedAtMillis = System.currentTimeMillis(),
                versionName = versionName,
                versionCode = versionCode,
                androidSdk = Build.VERSION.SDK_INT,
                latestRunReadable = latestRunReadable,
                faultJournalReadable = journalReadable,
            ))
            val name = SimpleDateFormat("yyyyMMdd_HHmm", Locale.ROOT)
                .format(Date(System.currentTimeMillis()))
            reportSaver.launch("MediaLoader_diagnostics_${name}.md")
        } catch (_: Exception) {
            pendingMarkdown = null
            notice = "Не удалось подготовить безопасный диагностический отчёт."
        }
    }

    LaunchedEffect(Unit) {
        try {
            val past = recentRun.mostRecent()
            latestRunReadable = true
            if (past != null) {
                lastCompletedRun = past
                findings = past.findings
                lastCheckedAt = past.finishedAtMillis
                lastMode = past.mode
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            latestRunReadable = false
            notice = "Не удалось прочитать предыдущую диагностику. Результаты не сброшены."
        }
        try {
            faults = journal.entries()
            journalReadable = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            journalReadable = false
            notice = "Журнал неисправностей недоступен. Старые записи не удалены."
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            now = System.currentTimeMillis()
        }
    }
    fun start(mode: DiagnosticMode, approved: Boolean) {
        if (running) return
        running = true
        if (mode == DiagnosticMode.FULL) {
            fullInputState.value = FullDiagnosticInput.parse(controlLinks, controlArtist, controlTitle)
        }
        activeMode = mode
        checkedCount = 0
        findings = emptyList()
        notice = null
        askNetwork = false
        currentJob = scope.launch {
            try {
                val completed = runner.execute(mode, networkConsent = approved,
                    onFinding = { row ->
                        findings = findings + row
                        checkedCount = findings.size
                    })
                recentRun.save(completed)
                lastCompletedRun = completed
                latestRunReadable = true
                lastCheckedAt = completed.finishedAtMillis
                lastMode = completed.mode
                now = System.currentTimeMillis()
                faults = journal.entries()
                journalReadable = true
                notice = "Проверка завершена. Результаты сохранены локально."
            } catch (_: CancellationException) {
                findings = DiagnosticSessionRules.afterInterruptedRun(lastCompletedRun)
                checkedCount = 0
                notice = "Проверка остановлена. Частичные результаты не сохранены."
            } catch (_: Exception) {
                findings = DiagnosticSessionRules.afterInterruptedRun(lastCompletedRun)
                checkedCount = 0
                notice = "Проверка или сохранение журнала завершились с ошибкой. " +
                    "Старые записи не удалены."
            } finally {
                running = false
                currentJob = null
            }
        }
    }
    fun closeScreen() {
        currentJob?.cancel()
        onBack()
    }
    BackHandler { closeScreen() }
    val visibleSpecs = DiagnosticModeChecks.visible(specs, if (running) activeMode else lastMode)
    val summary = DiagnosticAssessment.application(visibleSpecs, findings, now, currentRun = running)
    val status = if (running) DiagnosticState.RUNNING else summary.state
    val activeCode = DiagnosticPresentation.activeCode(visibleSpecs, findings, running)
    val groups = listOf(
        DiagnosticComponent.SOURCES,
        DiagnosticComponent.MEDIA_RUNTIME,
        DiagnosticComponent.DOWNLOAD_QUEUE,
        DiagnosticComponent.STORAGE,
        DiagnosticComponent.PHOTO_EDITOR,
    )

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = ::closeScreen) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "Назад")
            }
            Text("Диагностика", style = MaterialTheme.typography.headlineSmall)
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Column(Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusIcon(status)
                            Spacer(Modifier.size(10.dp))
                            Column {
                                Text(if (running) "Выполняется проверка"
                                    else DiagnosticLabels.overall(status),
                                    style = MaterialTheme.typography.titleMedium)
                                Text(
                                    if (lastCheckedAt == null) "Ранее проверка не выполнялась"
                                    else (if (running) "Предыдущая: " else "Последняя: ") +
                                        "${formatDiagnosticDate(lastCheckedAt!!)} · " +
                                        (when (lastMode) {
                                            DiagnosticMode.LOCAL -> "локальная"
                                            DiagnosticMode.FULL -> "полная"
                                            else -> "с сетью"
                                        }),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Text(
                            "Выполнено проб: ${summary.freshResults} из ${summary.totalChecks}" +
                                if (summary.expiredResults > 0)
                                    " · устарели: ${summary.expiredResults}" else "",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text("Подтверждено отдельных этапов: ${DiagnosticPresentation.confirmedGoodCount(findings)}",
                            style = MaterialTheme.typography.bodySmall)
                        if (running) {
                            Text("Фиолетовый значок показывает выполняемый раздел.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            LinearProgressIndicator(
                                progress = { checkedCount.toFloat() / visibleSpecs.size.coerceAtLeast(1) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text("$checkedCount из ${visibleSpecs.size} проверок",
                                style = MaterialTheme.typography.labelMedium)
                            OutlinedButton(onClick = { currentJob?.cancel() }) {
                                Text("Остановить проверку")
                            }
                        } else {
                            Button(onClick = { askNetwork = true },
                                modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Rounded.Refresh, contentDescription = null)
                                Spacer(Modifier.size(8.dp))
                                Text("Проверить всё")
                            }
                            OutlinedButton(
                                onClick = { start(DiagnosticMode.LOCAL, false) },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Проверить без интернета") }
                            OutlinedButton(onClick = { showFullSettings = !showFullSettings },
                                modifier = Modifier.fillMaxWidth()) {
                                Text(if (showFullSettings) "Скрыть параметры полной проверки"
                                    else "Полная проверка для разработки")
                            }
                            if (showFullSettings) {
                            OutlinedTextField(value = controlLinks,
                                onValueChange = { controlLinks = it.take(2_000); controls.links = controlLinks },
                                label = { Text("Контрольные ссылки: Instagram, YouTube, VK, RUTUBE") },
                                supportingText = { Text("По одной HTTPS-ссылке на строку. Без ссылки источник не проверяется.") },
                                minLines = 2, maxLines = 5,
                                modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(value = controlArtist,
                                onValueChange = { controlArtist = it.take(120); controls.artist = controlArtist },
                                label = { Text("Исполнитель для проверки музыки") },
                                modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(value = controlTitle,
                                onValueChange = { controlTitle = it.take(120); controls.title = controlTitle },
                                label = { Text("Название песни для проверки музыки") },
                                modifier = Modifier.fillMaxWidth())
                            Button(onClick = { askFull = true },
                                modifier = Modifier.fillMaxWidth()) {
                                Text("Запустить полную проверку")
                            }
                            }
                        }
                        notice?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("Полная проверка испытывает получение метаданных, вход, " +
                            "обработку медиа и отдельную пробную очередь.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            items(groups) { component ->
                val own = visibleSpecs.filter { it.component == component }
                if (own.isNotEmpty()) {
                    val state = DiagnosticAssessment.component(component, visibleSpecs, findings, now,
                        currentRun = running)
                    val activeHere = own.any { it.code == activeCode }
                    val opened = component in expanded
                    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Column {
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    expanded = if (opened) expanded - component
                                        else expanded + component
                                }.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                StatusIcon(if (activeHere) DiagnosticState.RUNNING else state.state)
                                Spacer(Modifier.size(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(FaultExplanations.componentTitle(component),
                                        style = MaterialTheme.typography.titleMedium)
                                    Text("${if (activeHere) "Проверяется" else DiagnosticLabels.state(state.state)} · " +
                                        "выполнено ${state.freshResults} из ${state.totalChecks}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Icon(if (opened) Icons.Rounded.ExpandLess
                                    else Icons.Rounded.ExpandMore,
                                    contentDescription = if (opened) "Свернуть" else "Развернуть")
                            }
                            if (opened) {
                                HorizontalDivider()
                                own.forEach { spec ->
                                    FindingRow(spec, findings.lastOrNull { it.spec.code == spec.code },
                                        now, running, spec.code == activeCode,
                                        spec.code == "storage.selected_folder" &&
                                            findings.any { it.spec.code == "storage.write" &&
                                                it.state == DiagnosticState.OK })
                                    HorizontalDivider()
                                }
                            }
                        }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Column(Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Журнал неисправностей",
                            style = MaterialTheme.typography.titleMedium)
                        Text("Отдельно от технического журнала. Показаны сохранённые " +
                            "проблемы, а не все события отладки.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val active = faults.count { it.status != FaultStatus.RESOLVED }
                        Text("Требуют внимания: $active · всего: ${faults.size}")
                        if (faults.isEmpty()) {
                            Text("Записей пока нет. Это не означает, что проверки пройдены.",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        faults.asReversed().take(20).forEach { fault ->
                            FaultRow(fault)
                            HorizontalDivider()
                        }
                        if (faults.size > 20)
                            Text("Показаны 20 последних записей из ${faults.size}",
                                style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(
                            onClick = ::exportReport,
                            enabled = !running && pendingMarkdown == null,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Сохранить безопасный отчёт .md")
                        }
                        Text(
                            "Отчёт сохраняется только по твоему выбору. " +
                                "Данные аккаунтов, файлы и исходные логи не включаются. " +
                                "При прерванной проверке используется последний завершённый результат.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(
                            onClick = { askClearFaults = true },
                            enabled = !running && faults.isNotEmpty(),
                        ) { Text("Очистить журнал неисправностей") }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Column(Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Сведения об устройстве",
                            style = MaterialTheme.typography.titleMedium)
                        Text("MediaLoader $versionName ($versionCode)")
                        Text("Android ${Build.VERSION.RELEASE} · SDK ${Build.VERSION.SDK_INT}")
                        Text("ABI устройства: ${Build.SUPPORTED_ABIS.joinToString()}")
                        Text("Архитектура выпускаемого APK проверяется отдельно в CI; " +
                            "поддержка ABI устройством не означает наличие всех ABI в APK.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item { Spacer(Modifier.size(12.dp)) }
        }
    }
    if (askNetwork) AlertDialog(
        onDismissRequest = { askNetwork = false },
        title = { Text("Проверить доступность источников?") },
        text = {
            Text("Приложение отправит по одному анонимному запросу на сайты " +
                "Instagram, YouTube, VK, RUTUBE и двух музыкальных источников. " +
                "Сайты могут увидеть IP-адрес или адрес VPN. Cookies, " +
                "медиафайлы и поисковые запросы не отправляются. " +
                "Можно выполнить только локальные проверки.")
        },
        confirmButton = {
            TextButton(onClick = { start(DiagnosticMode.WITH_NETWORK, true) }) {
                Text("Проверить с сетью")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { start(DiagnosticMode.LOCAL, false) }) {
                    Text("Только локально")
                }
                TextButton(onClick = { askNetwork = false }) { Text("Отмена") }
            }
        },
    )
    if (askFull) AlertDialog(
        onDismissRequest = { askFull = false },
        title = { Text("Запустить полную проверку?") },
        text = { Text("Диагностика запустит локальные компоненты, проверит запись в папку " +
            "и обратится к источникам с контрольными ссылками и запросом. " +
            "Сохранённые сессии могут использоваться рабочими механизмами приложения. " +
            "Удаление пробного файла проверяется отдельно; текущие загрузки не отменяются.") },
        confirmButton = {
            TextButton(onClick = { askFull = false; start(DiagnosticMode.FULL, true) }) {
                Text("Запустить")
            }
        },
        dismissButton = {
            TextButton(onClick = { askFull = false }) { Text("Отмена") }
        },
    )
    if (askClearFaults) AlertDialog(
        onDismissRequest = { askClearFaults = false },
        title = { Text("Очистить журнал неисправностей?") },
        text = {
            Text("Удалятся только записи диагностики. Технический журнал, " +
                "история загрузок и файлы останутся на месте.")
        },
        confirmButton = {
            TextButton(onClick = {
                askClearFaults = false
                scope.launch {
                    try {
                        journal.clear()
                        faults = journal.entries()
                        journalReadable = true
                    } catch (_: CancellationException) {
                        throw CancellationException()
                    } catch (_: Exception) {
                        notice = "Не удалось очистить журнал неисправностей."
                    }
                }
            }) { Text("Очистить") }
        },
        dismissButton = {
            TextButton(onClick = { askClearFaults = false }) { Text("Отмена") }
        },
    )
}

@Composable
private fun FindingRow(spec: DiagnosticCheckSpec, finding: DiagnosticFinding?, now: Long,
    running: Boolean, active: Boolean, superseded: Boolean = false) {
    val expired = finding?.let { DiagnosticPresentation.isExpired(it, running, now) } ?: false
    val state = when {
        active -> DiagnosticState.RUNNING
        expired -> DiagnosticState.UNCHECKED
        superseded -> DiagnosticState.OK
        else -> finding?.state ?: DiagnosticState.UNCHECKED
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusIcon(state)
            Spacer(Modifier.size(8.dp))
            Text(DiagnosticLabels.check(spec),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium)
            Text(when {
                active -> "Проверяется"
                finding == null && running -> "Ожидает"
                expired -> "Устарело"
                superseded -> "Запись подтверждена"
                else -> finding?.let(DiagnosticLabels::finding) ?: DiagnosticLabels.state(state)
            },
                style = MaterialTheme.typography.labelSmall,
                color = diagnosticTint(state))
        }
        Text(when {
            active -> "Проверка выполняется."
            finding == null && running -> "Проверка ожидает своей очереди."
            finding == null -> "Проверка не запускалась."
            expired -> "Результат устарел. Требуется повторная проверка."
            superseded -> "Позднее пробный файл успешно записан и прочитан в папке загрузок."
            else -> finding.explanation
        }, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!expired) finding?.recommendation?.let {
            Text("Рекомендация: $it", style = MaterialTheme.typography.bodySmall)
        }
        finding?.checkedAtMillis?.let {
            Text("Проверено: ${formatDiagnosticDate(it)}" +
                (finding.durationMillis?.let { duration -> " · ${duration} мс" } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FaultRow(record: FaultRecord) {
    val description = FaultExplanations.forRecord(record)
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusIcon(when(record.status) {
                FaultStatus.RESOLVED -> DiagnosticState.OK
                FaultStatus.NEEDS_RECHECK -> DiagnosticState.UNAVAILABLE
                FaultStatus.ACTIVE -> if (record.severity == FaultSeverity.ERROR)
                    DiagnosticState.ERROR else DiagnosticState.WARNING
            })
            Spacer(Modifier.size(8.dp))
            Text(description.title, style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        }
        Text("${FaultExplanations.componentTitle(record.component)} · " +
            "${formatDiagnosticDate(record.lastSeenMillis)} · повторов: ${record.repetitions}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(when(record.status) {
            FaultStatus.ACTIVE -> description.explanation
            FaultStatus.NEEDS_RECHECK -> "Требуется повторная проверка. " + description.explanation
            FaultStatus.RESOLVED -> "Устранено подтверждённой проверкой. " + description.explanation
        }, style = MaterialTheme.typography.bodySmall)
        if (record.status != FaultStatus.RESOLVED) description.advice?.let {
            Text("Рекомендация: $it", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StatusIcon(state: DiagnosticState) {
    when (state) {
        DiagnosticState.RUNNING -> Icon(Icons.Rounded.Refresh,
            contentDescription = null, tint = diagnosticTint(state),
            modifier = Modifier.size(20.dp))
        DiagnosticState.OK -> Icon(Icons.Rounded.CheckCircle,
            contentDescription = null, tint = diagnosticTint(state),
            modifier = Modifier.size(20.dp))
        else -> Icon(Icons.Rounded.WarningAmber,
            contentDescription = null, tint = diagnosticTint(state),
            modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun diagnosticTint(state: DiagnosticState): Color = when(state) {
    DiagnosticState.OK -> Color(0xFF15964A)
    DiagnosticState.ERROR -> MaterialTheme.colorScheme.error
    DiagnosticState.WARNING, DiagnosticState.UNAVAILABLE -> Color(0xFFF29A27)
    DiagnosticState.RUNNING -> MaterialTheme.colorScheme.primary
    DiagnosticState.UNCHECKED ->
        MaterialTheme.colorScheme.onSurfaceVariant
}

private fun formatDiagnosticDate(epochMillis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(epochMillis))
