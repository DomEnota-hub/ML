package com.domenota.medialoader.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.domenota.medialoader.data.editor.AiModelUpdateChecker
import com.domenota.medialoader.data.editor.ModelUpdateResult
import com.domenota.medialoader.data.editor.ModelUpdateState
import com.domenota.medialoader.data.editor.LaMaModelStore
import com.domenota.medialoader.data.editor.MobileSamModelStore
import com.domenota.medialoader.data.editor.PersonDetectorModelStore
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
    val scope = rememberCoroutineScope()
    var lamaReady by remember { mutableStateOf(false) }
    var mobileReady by remember { mutableStateOf(false) }
    var peopleReady by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(true) }
    var active by remember { mutableStateOf<String?>(null) }
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
        checking = false
    }
    fun download(kind: String) {
        if (active != null || checking || updateChecking) return
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
                }
            } catch (failure: Exception) {
                error = failure.message ?: "Не удалось удалить AI-модель."
            } finally {
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

    // Explicit white action colours: do not inherit the app-wide purple Material accent.
    val whiteActionColors = ButtonDefaults.textButtonColors(
        contentColor = Color.White,
        disabledContentColor = Color.White.copy(alpha = 0.38f),
    )

    if (pendingDelete != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Удалить AI-модель?") },
            text = { Text("Фотографии сохранятся. Для повторного использования модель нужно будет скачать заново.") },
            confirmButton = { TextButton(colors = whiteActionColors, onClick = {
                val kind = pendingDelete
                if (kind != null) delete(kind)
            }) { Text("Удалить") } },
            dismissButton = { TextButton(colors = whiteActionColors, onClick = { pendingDelete = null }) { Text("Отмена") } },
        )
    }

    Dialog(onDismissRequest = { if (active == null && !updateChecking) onDismiss() }) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = androidx.compose.ui.graphics.Color(0xFF1D1E25),
        ) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("AI-модели", fontWeight = FontWeight.Bold,
                    color = androidx.compose.ui.graphics.Color.White)
                Text("Загружаются отдельно. Фотографии обрабатываются на устройстве.",
                    color = androidx.compose.ui.graphics.Color.LightGray)
                if (checking) CircularProgressIndicator()
                ModelRow(
                    title = "LaMa", detail = "Удаление объектов и текста",
                    ready = lamaReady, enabled = !checking && !updateChecking && active == null,
                    onInstall = { download("lama") },
                    onDelete = { pendingDelete = "lama" },
                )
                HorizontalDivider()
                ModelRow(
                    title = "MobileSAM", detail = "Выделение касанием · 44,7 МБ",
                    ready = mobileReady, enabled = !checking && !updateChecking && active == null,
                    onInstall = { download("mobile") },
                    onDelete = { pendingDelete = "mobile" },
                )
                HorizontalDivider()
                ModelRow(
                    title = "EfficientDet-Lite0",
                    detail = "Автоматическое обнаружение людей · 13,8 МБ",
                    ready = peopleReady, enabled = !checking && !updateChecking && active == null,
                    onInstall = { download("people") },
                    onDelete = { pendingDelete = "people" },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(colors = whiteActionColors, onClick = ::checkUpdates,
                        enabled = !checking && !updateChecking && active == null) {
                        Text("Проверить обновления")
                    }
                }
                if (updateChecking) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("Проверяем версии и SHA-256 в каталоге разработчиков моделей…",
                        color = Color.LightGray)
                }
                updateResults["lama"]?.let { result ->
                    Text("LaMa: ${result.detail}",
                        color = if (result.state == ModelUpdateState.REVIEW_REQUIRED)
                            Color(0xFFFFD180) else Color.LightGray)
                }
                updateResults["mobile"]?.let { result ->
                    Text("MobileSAM: ${result.detail}",
                        color = if (result.state == ModelUpdateState.REVIEW_REQUIRED)
                            Color(0xFFFFD180) else Color.LightGray)
                }
                updateResults["people"]?.let { result ->
                    Text("EfficientDet: ${result.detail}",
                        color = if (result.state == ModelUpdateState.REVIEW_REQUIRED)
                            Color(0xFFFFD180) else Color.LightGray)
                }
                if (active != null && job?.isActive == true) {
                    Text(when (active) {
                        "lama" -> "LaMa · $progress%"
                        "mobile" -> "MobileSAM · $progress%"
                        else -> "EfficientDet · $progress%"
                    },
                        color = androidx.compose.ui.graphics.Color.White)
                    LinearProgressIndicator(
                        progress = { progress.toFloat() / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(colors = whiteActionColors, onClick = { job?.cancel() }) { Text("Отменить загрузку") }
                }
                error?.let { Text(it, color = androidx.compose.ui.graphics.Color(0xFFFFB4AB)) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(colors = whiteActionColors, onClick = onDismiss,
                        enabled = active == null && !updateChecking) {
                        Text("Закрыть")
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelRow(
    title: String,
    detail: String,
    ready: Boolean,
    enabled: Boolean,
    onInstall: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, color = androidx.compose.ui.graphics.Color.White,
                fontWeight = FontWeight.SemiBold)
            Text(detail, color = androidx.compose.ui.graphics.Color.LightGray)
            Text(if (ready) "Установлена" else "Не установлена",
                color = androidx.compose.ui.graphics.Color.LightGray)
        }
        if (ready) {
            IconButton(onClick = onDelete, enabled = enabled) {
                Icon(Icons.Rounded.DeleteOutline, "Удалить $title",
                    tint = androidx.compose.ui.graphics.Color.White)
            }
        } else {
            IconButton(onClick = onInstall, enabled = enabled) {
                Icon(Icons.Rounded.Download, "Скачать $title",
                    tint = androidx.compose.ui.graphics.Color.White)
            }
        }
    }
}
