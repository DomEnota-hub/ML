package com.domenota.medialoader.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.domenota.medialoader.data.editor.AiModelCatalog
import com.domenota.medialoader.data.editor.OwnerModelCatalog
import com.domenota.medialoader.data.editor.AiModelUpdateChecker
import com.domenota.medialoader.data.editor.ModelUpdateResult
import com.domenota.medialoader.data.editor.ModelUpdateState
import com.domenota.medialoader.data.editor.LaMaModelStore
import com.domenota.medialoader.data.editor.MobileSamModelStore
import com.domenota.medialoader.data.editor.PersonDetectorModelStore
import com.domenota.medialoader.data.editor.SpecularSrNetModelStore
import com.domenota.medialoader.data.editor.ReflectionDExModelStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.domenota.medialoader.data.tasks.*

/** Optional AI weights, never downloaded implicitly when a photo is opened. */
@Composable
internal fun AiModelManagerDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val lama = remember(context) { LaMaModelStore(context.applicationContext) }
    val mobile = remember(context) { MobileSamModelStore(context.applicationContext) }
    val people = remember(context) { PersonDetectorModelStore(context.applicationContext) }
    val glare = remember(context) { SpecularSrNetModelStore(context.applicationContext) }
    val reflection = remember(context) { ReflectionDExModelStore(context.applicationContext) }
    val gigaam = remember(context) { com.domenota.medialoader.data.editor.GigaAmModelStore(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var lamaReady by remember { mutableStateOf(false) }
    var mobileReady by remember { mutableStateOf(false) }
    var peopleReady by remember { mutableStateOf(false) }
    var glareReady by remember { mutableStateOf(false) }
    var reflectionReady by remember { mutableStateOf(false) }
    var gigaamReady by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(true) }
    var active by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var job by remember { mutableStateOf<Job?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    var updateChecking by remember { mutableStateOf(false) }
    var updateResults by remember { mutableStateOf<Map<String, ModelUpdateResult>>(emptyMap()) }
    var updateJob by remember { mutableStateOf<Job?>(null) }
    var checkGeneration by remember { mutableIntStateOf(0) }

    fun cancelUpdateCheck() {
        checkGeneration++
        updateJob?.cancel()
        updateJob = null
        updateChecking = false
    }

    val reflectionFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null && active == null && !checking) {
            cancelUpdateCheck()
            error = null
            progress = 0
            active = "reflection"
            job = scope.launch {
                val report = TaskReporter.create(TaskOwner.MODEL, TaskKind.MODEL)
                report.report()
                try {
                    reflection.installFromUri(uri) { progress = it; report.report(percent=it.coerceIn(0,100)) }
                    reflectionReady = reflection.verifyInstalled()
                    report.report(if (reflectionReady) TaskStatus.COMPLETE else TaskStatus.FAILED)

                } catch (cancel: CancellationException) {
                    report.report(TaskStatus.CANCELLED)
                    error = "Импорт модели отменён."
                } catch (failure: Exception) {
                    report.report(TaskStatus.FAILED,errors=1)
                    error = failure.message ?: "Не удалось проверить файл DExNet."
                } finally {
                    active = null
                    job = null
                }
            }
        }
    }

    fun download(kind: String, offline: Boolean = false) {
        if (active != null || checking) return
        cancelUpdateCheck()
        updateResults = emptyMap()
        error = null
        progress = 0
        active = kind
        job = scope.launch {
                val report = TaskReporter.create(TaskOwner.MODEL, TaskKind.MODEL)
                report.report()
            try {
                if (kind == "gigaam") {
                    gigaam.install { progress=it; report.report(percent=it.coerceIn(0,100)) }
                    gigaamReady=gigaam.verifyInstalled()
                } else if (kind == "lama") {
                    lama.install { progress = it; report.report(percent=it.coerceIn(0,100)) }
                    lamaReady = lama.verifyInstalled()
                } else if (kind == "mobile") {
                    mobile.install { progress = it; report.report(percent=it.coerceIn(0,100)) }
                    mobileReady = mobile.verifyInstalled()
                } else if (kind == "people") {
                    people.install { progress = it; report.report(percent=it.coerceIn(0,100)) }
                    peopleReady = people.verifyInstalled()
                } else if (kind == "glare") {
                    if (offline) glare.install { progress = it; report.report(percent=it.coerceIn(0,100)) }
                    else glare.download { progress = it; report.report(percent=it.coerceIn(0,100)) }
                    glareReady = glare.verifyInstalled()
                } else if (kind == "reflection") {
                    if (reflection.bundled()) reflection.install { progress = it; report.report(percent=it.coerceIn(0,100)) }
                    else reflection.downloadAndAssemble { progress = it; report.report(percent=it.coerceIn(0,100)) }
                    reflectionReady = reflection.verifyInstalled()
                }
                report.report(if (when(kind) { "gigaam" -> gigaamReady; "lama" -> lamaReady; "mobile" -> mobileReady; "people" -> peopleReady; "glare" -> glareReady; "reflection" -> reflectionReady; else -> false }) TaskStatus.COMPLETE else TaskStatus.FAILED)

            } catch (cancel: CancellationException) {
                    report.report(TaskStatus.CANCELLED)
                error = "Установка отменена."
            } catch (failure: Exception) {
                    report.report(TaskStatus.FAILED,errors=1)
                error = failure.message ?: "Не удалось установить AI-модель."
            } finally {
                active = null
                job = null
                progress = 0
            }
        }
    }
    fun delete(kind: String) {
        if (active != null || checking) return
        cancelUpdateCheck()
        updateResults = emptyMap()
        error = null
        removing = true
        active = kind
        pendingDelete = null
        job = scope.launch {
            try {
                if (kind == "gigaam") {
                    gigaam.uninstall();gigaamReady=false
                } else if (kind == "lama") {
                    lama.uninstall()
                    lamaReady = false
                } else if (kind == "mobile") {
                    mobile.uninstall()
                    mobileReady = false
                } else if (kind == "people") {
                    people.uninstall()
                    peopleReady = false
                } else if (kind == "glare") {
                    glare.uninstall()
                    glareReady = false
                } else if (kind == "reflection") {
                    reflection.uninstall()
                    reflectionReady = false
                }
            } catch (failure: Exception) {
                error = failure.message ?: "Не удалось удалить AI-модель."
            } finally {
                removing = false
                active = null
                job = null
            }
        }
    }

    fun checkUpdates() {
        if (checking || active != null || updateChecking) return
        val generation = ++checkGeneration
        updateChecking = true
        error = null
        // Keep the local install/delete actions available even with no network.
        // A user-initiated operation cancels this advisory metadata check.
        updateJob = scope.launch {
            try {
                val verified = mapOf(
                    "lama" to lamaReady, "mobile" to mobileReady, "people" to peopleReady,
                    "glare" to glareReady, "reflection" to reflectionReady, "gigaam" to gigaamReady,
                )
                val results = OwnerModelCatalog.check(verified)
                if (generation == checkGeneration) updateResults = results
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                if (generation == checkGeneration) error = "Проверка версий недоступна."
            } finally {
                if (generation == checkGeneration) {
                    updateChecking = false
                    updateJob = null
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        try {
            lamaReady = lama.verifyInstalled()
            mobileReady = mobile.verifyInstalled()
            peopleReady = people.verifyInstalled()
            glareReady = glare.verifyInstalled()
            reflectionReady = reflection.verifyInstalled()
            gigaamReady = gigaam.verifyInstalled()
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { error = "Не удалось проверить локальные модели." }
        finally { checking = false }
        checkUpdates()
    }

    val actionColors = ButtonDefaults.textButtonColors(
        contentColor = MaterialTheme.colorScheme.primary,
    )

    if (pendingDelete != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Удалить ${when (pendingDelete) {
                "lama" -> "LaMa"; "mobile" -> "MobileSAM"; "people" -> "EfficientDet-Lite0"
                "glare" -> "SRNet"; "reflection" -> "DExNet + VGG19"; "gigaam" -> "GigaAM-v3 CTC"; else -> "AI-модель"
            }}?") },
            text = { Text("Медиафайлы и сохранённые результаты сохраняются. Для новой обработки потребуется повторная установка модели.") },
            confirmButton = { TextButton(colors = actionColors, onClick = {
                val kind = pendingDelete
                if (kind != null) delete(kind)
            }) { Text("Удалить") } },
            dismissButton = { TextButton(colors = actionColors, onClick = { pendingDelete = null }) { Text("Отмена") } },
        )
    }

    // Full-screen manager shares the same stores and jobs as the former dialog.
    // In editor routes the caller's scoped editor theme is retained.
    Dialog(onDismissRequest = { if (active == null) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false,
            dismissOnClickOutside = false)) {
        com.domenota.medialoader.ui.theme.TransparentWindowNavigation()
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss, enabled = active == null) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад")
                    }
                    Text("AI-модели", style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = ::checkUpdates, enabled = !checking && !updateChecking && active == null) {
                        if (updateChecking) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Rounded.Refresh, "Проверить обновления")
                    }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val ready = mapOf("lama" to lamaReady, "mobile" to mobileReady, "people" to peopleReady,
                        "glare" to glareReady, "reflection" to reflectionReady, "gigaam" to gigaamReady)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("Обработка на устройстве", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        Text(if (checking) "…" else "${ready.values.count { it }}/${AiModelCatalog.entries.size}",
                            fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                    }
                    val enabled = !checking && active == null
                    AiModelCatalog.entries.forEach { entry ->
                        val key = entry.kind.key
                        val icon = when (entry.kind) {
                            AiModelUpdateChecker.Kind.LAMA -> Icons.Rounded.AutoFixHigh
                            AiModelUpdateChecker.Kind.MOBILE_SAM -> Icons.Rounded.TouchApp
                            AiModelUpdateChecker.Kind.PEOPLE -> Icons.Rounded.PersonSearch
                            AiModelUpdateChecker.Kind.GLARE -> Icons.Rounded.LightMode
                            AiModelUpdateChecker.Kind.REFLECTION -> Icons.Rounded.Photo
                            AiModelUpdateChecker.Kind.GIGAAM -> Icons.Rounded.AutoFixHigh
                        }
                        ModelRow(entry, icon, ready[key] == true, enabled, checking, updateResults[key],
                            reflection.bundled(), onInstall = { download(key) }, onDelete = { pendingDelete = key },
                            onOfflineInstall = if (entry.kind == AiModelUpdateChecker.Kind.GLARE) ({ download(key, offline = true) }) else null,
                            onImport = if (entry.kind == AiModelUpdateChecker.Kind.REFLECTION) ({
                                reflectionFilePicker.launch(arrayOf("*/*"))
                            }) else null)
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Spacer(Modifier.height(4.dp))
                }
                // The footer stays accessible regardless of scroll position or font size.
                if (active != null) {
                    val label = when (active) {
                        "lama" -> "LaMa"; "mobile" -> "MobileSAM"; "people" -> "EfficientDet";
                        "glare" -> "SRNet"; "reflection" -> "DExNet"; "gigaam" -> "GigaAM"; else -> "AI-модель"
                    }
                    Text(if (removing) "Удаление · $label" else "$label · $progress%",
                        style = MaterialTheme.typography.bodySmall)
                    if (removing) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else {
                        LinearProgressIndicator(progress = { progress.coerceIn(0, 100) / 100f }, modifier = Modifier.fillMaxWidth())
                        TextButton(onClick = { job?.cancel() }) { Text("Отменить установку") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelRow(
    entry: AiModelCatalog.Entry,
    icon: ImageVector,
    ready: Boolean,
    enabled: Boolean,
    checking: Boolean,
    result: ModelUpdateResult?,
    reflectionBundled: Boolean,
    onInstall: () -> Unit,
    onDelete: () -> Unit,
    onOfflineInstall: (() -> Unit)? = null,
    onImport: (() -> Unit)? = null,
) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(entry.title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                Text(listOfNotNull(entry.purpose, entry.sizeLabel).joinToString(" · "),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                Text(AiModelCatalog.status(entry, ready, checking, result, reflectionBundled),
                    color = when {
                        result?.state == ModelUpdateState.REVIEW_REQUIRED -> MaterialTheme.colorScheme.primary
                        ready && !checking -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }, style = MaterialTheme.typography.bodySmall)
            }
            if (ready && !checking) {
                IconButton(onClick = onDelete, enabled = enabled) {
                    Icon(Icons.Rounded.DeleteOutline, "Удалить ${entry.title}",
                        tint = if (enabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else if (!checking) {
                if (onOfflineInstall != null) IconButton(onClick = onOfflineInstall, enabled = enabled) {
                    Icon(Icons.Rounded.SaveAlt, "Установить SRNet офлайн")
                }
                if (onImport != null) IconButton(onClick = onImport, enabled = enabled) {
                    Icon(Icons.Rounded.FolderOpen, "Импортировать DExNet ONNX")
                }
                val action = AiModelCatalog.installAction(entry, reflectionBundled)
                IconButton(onClick = onInstall, enabled = enabled) {
                    Icon(if (action == "Импортировать") Icons.Rounded.FolderOpen else Icons.Rounded.Download,
                        "$action ${entry.title}")
                }
            } else {
                Spacer(Modifier.size(48.dp))
            }
        }
    }
}
