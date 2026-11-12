package com.domenota.medialoader.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.domenota.medialoader.data.editor.AiModelUpdateChecker
import com.domenota.medialoader.data.editor.ModelUpdateResult
import com.domenota.medialoader.data.editor.ModelUpdateState
import com.domenota.medialoader.data.editor.LaMaModelStore
import com.domenota.medialoader.data.editor.MobileSamModelStore
import com.domenota.medialoader.data.editor.PersonDetectorModelStore
import com.domenota.medialoader.data.editor.SpecularSrNetModelStore
import com.domenota.medialoader.data.editor.ReflectionDExModelStore
import com.domenota.medialoader.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Optional AI weights, never downloaded implicitly when a photo is opened. */
@Composable
internal fun AiModelManagerDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val lama = remember(context) { LaMaModelStore(context.applicationContext) }
    val mobile = remember(context) { MobileSamModelStore(context.applicationContext) }
    val people = remember(context) { PersonDetectorModelStore(context.applicationContext) }
    val glare = remember(context) { SpecularSrNetModelStore(context.applicationContext) }
    val reflection = remember(context) { ReflectionDExModelStore(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var lamaReady by remember { mutableStateOf(false) }
    var mobileReady by remember { mutableStateOf(false) }
    var peopleReady by remember { mutableStateOf(false) }
    var glareReady by remember { mutableStateOf(false) }
    var reflectionReady by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(true) }
    var active by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var job by remember { mutableStateOf<Job?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    var updateChecking by remember { mutableStateOf(false) }
    var updateResults by remember { mutableStateOf<Map<String, ModelUpdateResult>>(emptyMap()) }

    LaunchedEffect(Unit) {
        lamaReady = lama.verifyInstalled()
        mobileReady = mobile.verifyInstalled()
        peopleReady = people.verifyInstalled()
        glareReady = glare.verifyInstalled()
        reflectionReady = reflection.verifyInstalled()
        checking = false
    }
    val reflectionFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null && active == null && !checking && !updateChecking) {
            error = null
            progress = 0
            active = "reflection"
            job = scope.launch {
                try {
                    reflection.installFromUri(uri) { progress = it }
                    reflectionReady = reflection.verifyInstalled()
                } catch (cancel: CancellationException) {
                    error = "Импорт модели отменён."
                } catch (failure: Exception) {
                    error = failure.message ?: "Не удалось проверить файл DExNet."
                } finally {
                    active = null
                    job = null
                }
            }
        }
    }

    fun download(kind: String) {
        if (active != null || checking || updateChecking) return
        if (kind == "reflection" && !reflection.bundled()) {
            reflectionFilePicker.launch(arrayOf("*/*"))
            return
        }
        updateResults = emptyMap()
        error = null
        progress = 0
        active = kind
        job = scope.launch {
            try {
                if (kind == "lama") {
                    lama.install { progress = it }
                    lamaReady = lama.verifyInstalled()
                } else if (kind == "mobile") {
                    mobile.install { progress = it }
                    mobileReady = mobile.verifyInstalled()
                } else if (kind == "people") {
                    people.install { progress = it }
                    peopleReady = people.verifyInstalled()
                } else if (kind == "glare") {
                    glare.install { progress = it }
                    glareReady = glare.verifyInstalled()
                } else if (kind == "reflection" && BuildConfig.REFLECTION_MODEL_BUNDLED) {
                    reflection.install { progress = it }
                    reflectionReady = reflection.verifyInstalled()
                }
            } catch (cancel: CancellationException) {
                error = "Установка отменена."
            } catch (failure: Exception) {
                error = failure.message ?: "Не удалось установить AI-модель."
            } finally {
                active = null
                job = null
                progress = 0
            }
        }
    }
    fun delete(kind: String) {
        if (active != null || updateChecking) return
        updateResults = emptyMap()
        error = null
        removing = true
        active = kind
        pendingDelete = null
        job = scope.launch {
            try {
                if (kind == "lama") {
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
        if (checking || updateChecking || active != null) return
        error = null
        updateResults = emptyMap()
        updateChecking = true
        scope.launch {
            try {
                // Never download weights here. Compare upstream file digests with reviewed pins.
                val verifiedLaMa = lama.verifyInstalled()
                val verifiedMobile = mobile.verifyInstalled()
                lamaReady = verifiedLaMa
                mobileReady = verifiedMobile
                val verifiedPeople = people.verifyInstalled()
                peopleReady = verifiedPeople
                glareReady = glare.verifyInstalled()
                reflectionReady = reflection.verifyInstalled()
                val lamaResult = AiModelUpdateChecker.check(
                    AiModelUpdateChecker.Kind.LAMA, verifiedLaMa)
                val mobileResult = AiModelUpdateChecker.check(
                    AiModelUpdateChecker.Kind.MOBILE_SAM, verifiedMobile)
                val peopleResult = AiModelUpdateChecker.check(
                    AiModelUpdateChecker.Kind.PEOPLE, verifiedPeople)
                updateResults = mapOf("lama" to lamaResult, "mobile" to mobileResult,
                    "people" to peopleResult)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                error = "Не удалось проверить обновления моделей."
            } finally {
                updateChecking = false
            }
        }
    }

    val actionColors = ButtonDefaults.textButtonColors(
        contentColor = MaterialTheme.colorScheme.primary,
    )

    if (pendingDelete != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Удалить ${when (pendingDelete) {
                "lama" -> "LaMa"; "mobile" -> "MobileSAM"; "people" -> "EfficientDet-Lite0"
                "glare" -> "SRNet"; "reflection" -> "DExNet + VGG19"; else -> "AI-модель"
            }}?") },
            text = { Text("Фотографии сохраняются. Повторная установка или импорт модели потребуется для обработки.") },
            confirmButton = { TextButton(colors = actionColors, onClick = {
                val kind = pendingDelete
                if (kind != null) delete(kind)
            }) { Text("Удалить") } },
            dismissButton = { TextButton(colors = actionColors, onClick = { pendingDelete = null }) { Text("Отмена") } },
        )
    }

    // Full-screen manager shares the same stores and jobs as the former dialog.
    // In editor routes the caller's scoped editor theme is retained.
    Dialog(onDismissRequest = { if (active == null && !updateChecking) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false,
            dismissOnClickOutside = false)) {
        com.domenota.medialoader.ui.theme.TransparentWindowNavigation()
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss, enabled = active == null && !updateChecking) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад")
                    }
                    Text("AI-модели", style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold)
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val count = listOf(lamaReady, mobileReady, peopleReady, glareReady, reflectionReady).count { it }
                    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Psychology, null, Modifier.size(36.dp),
                                    tint = MaterialTheme.colorScheme.primary)
                                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                    Text("Локальная обработка", fontWeight = FontWeight.SemiBold)
                                    Text("Модели на устройстве", style = MaterialTheme.typography.bodySmall)
                                }
                                Text(if (checking) "…" else "$count/5", style = MaterialTheme.typography.headlineSmall,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                            if (checking) LinearProgressIndicator(Modifier.fillMaxWidth())
                            else LinearProgressIndicator(progress = { count / 5f }, modifier = Modifier.fillMaxWidth())
                            Text("Локальная обработка фотографий · без отправки в облако", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    val enabled = !checking && !updateChecking && active == null
                    ModelRow("LaMa", "Удаление объектов и текста", "Восстановление фона в выделенной области.",
                        Icons.Rounded.AutoFixHigh, lamaReady, enabled, checking,
                        onInstall = { download("lama") }, onDelete = { pendingDelete = "lama" })
                    ModelRow("MobileSAM", "Выделение касанием · 44,7 МБ", "Сегментация объекта по точке касания.",
                        Icons.Rounded.TouchApp, mobileReady, enabled, checking,
                        onInstall = { download("mobile") }, onDelete = { pendingDelete = "mobile" })
                    ModelRow("EfficientDet-Lite0", "Обнаружение людей · 13,8 МБ", "Обнаружение людей для последующего построения масок.",
                        Icons.Rounded.PersonSearch, peopleReady, enabled, checking,
                        onInstall = { download("people") }, onDelete = { pendingDelete = "people" })
                    ModelRow("SRNet", "Удаление бликов · 230 КБ", "Модель поставляется с приложением. Установка без сети; обновление весов — с новой версией.",
                        Icons.Rounded.LightMode, glareReady, enabled, checking,
                        onInstall = { download("glare") }, onDelete = { pendingDelete = "glare" })
                    ModelRow("DExNet + VGG19", "Удаление отражений · 91 МБ",
                        if (BuildConfig.REFLECTION_MODEL_BUNDLED) "Установка из QA-приложения." else "Импорт проверенного ONNX-файла.",
                        Icons.Rounded.Photo, reflectionReady, enabled, checking,
                        installDescription = if (BuildConfig.REFLECTION_MODEL_BUNDLED) "Установить" else "Импортировать ONNX",
                        onInstall = { download("reflection") }, onDelete = { pendingDelete = "reflection" })
                    if (updateChecking) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("Проверка версий и SHA-256…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    updateResults.forEach { (kind, result) ->
                        Text("${when (kind) { "lama" -> "LaMa"; "mobile" -> "MobileSAM"; else -> "EfficientDet" }}: ${result.detail}",
                            color = if (result.state == ModelUpdateState.REVIEW_REQUIRED)
                                MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Spacer(Modifier.height(4.dp))
                }
                // The footer stays accessible regardless of scroll position or font size.
                if (active != null) {
                    val label = when (active) {
                        "lama" -> "LaMa"; "mobile" -> "MobileSAM"; "people" -> "EfficientDet";
                        "glare" -> "SRNet"; "reflection" -> "DExNet"; else -> "AI-модель"
                    }
                    Text(if (removing) "Удаление · $label" else "$label · $progress%",
                        style = MaterialTheme.typography.bodySmall)
                    if (removing) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else {
                        LinearProgressIndicator(progress = { progress.coerceIn(0, 100) / 100f }, modifier = Modifier.fillMaxWidth())
                        TextButton(onClick = { job?.cancel() }) { Text("Отменить установку") }
                    }
                }
                Button(onClick = ::checkUpdates, enabled = !checking && !updateChecking && active == null,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                    Icon(Icons.Rounded.Refresh, null)
                    Spacer(Modifier.width(8.dp)); Text("Проверить обновления")
                }
            }
        }
    }
}

@Composable
private fun ModelRow(
    title: String,
    detail: String,
    explanation: String,
    icon: ImageVector,
    ready: Boolean,
    enabled: Boolean,
    checking: Boolean,
    installDescription: String = "Установить",
    onInstall: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                        Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
                Column(Modifier.weight(1f).clickable { expanded = !expanded }) {
                    Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                    Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Действия · $title") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("О модели") }, onClick = { menu = false; expanded = true })
                        if (ready) DropdownMenuItem(text = { Text("Удалить модель", color = MaterialTheme.colorScheme.error) },
                            enabled = enabled, onClick = { menu = false; onDelete() })
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (checking) "Проверка…" else if (ready) "Установлена" else "Не установлена",
                    color = if (ready && !checking) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                if (!ready && !checking) TextButton(onClick = onInstall, enabled = enabled) { Text(installDescription) }
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, "Сведения о модели $title")
                }
            }
            if (expanded) {
                HorizontalDivider()
                Text(explanation, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
