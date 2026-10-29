package com.domenota.medialoader.ui.components

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.domenota.medialoader.ui.components.NeutralOutlinedButton as OutlinedButton
import androidx.compose.material3.Text
import com.domenota.medialoader.ui.components.NeutralTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import java.util.concurrent.atomic.AtomicBoolean
import com.domenota.medialoader.core.logging.AppLog
import com.domenota.medialoader.core.recognition.RecognitionAudioSpec
import com.domenota.medialoader.data.MediaRepository
import com.domenota.medialoader.data.search.FullSongSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class MicStage { IDLE, RECORDING, MATCHING, NO_MATCH, ERROR, PERMISSION_DENIED }

private data class MusicSearchLaunch(val artist: String, val title: String,
    val artworkUrl: String? = null, val message: String? = null)

/** Home entry point; the microphone is active only while its dialog is open. */
@Composable
fun MusicHomeSection() {
    val context = LocalContext.current
    val repository = remember(context) { MediaRepository.get(context) }
    val scope = rememberCoroutineScope()
    var stage by remember { mutableStateOf(MicStage.IDLE) }
    var recordedSeconds by remember { mutableIntStateOf(0) }
    var audioLevel by remember { mutableFloatStateOf(0f) }
    val finishRequested = remember { AtomicBoolean(false) }
    var searchLaunch by remember { mutableStateOf<MusicSearchLaunch?>(null) }
    var recognitionJob by remember { mutableStateOf<Job?>(null) }
    var generation by remember { mutableIntStateOf(0) }

    fun stopListening() {
        generation++
        finishRequested.set(true)
        recognitionJob?.cancel()
        recognitionJob = null
        stage = MicStage.IDLE
    }

    fun openManual(message: String? = null) {
        stopListening()
        searchLaunch = MusicSearchLaunch("", "", message = message)
    }

    fun startListening() {
        stopListening()
        val request = ++generation
        finishRequested.set(false)
        recordedSeconds = 0
        audioLevel = 0f
        stage = MicStage.RECORDING
        recognitionJob = scope.launch {
            try {
                val result = repository.recognizeLiveMusic(
                    onProgress = { seconds ->
                        withContext(Dispatchers.Main.immediate) {
                            if (request == generation) {
                                recordedSeconds = seconds
                                if (seconds >= RecognitionAudioSpec.TARGET_SECONDS)
                                    stage = MicStage.MATCHING
                            }
                        }
                    }, shouldFinish = finishRequested::get,
                    onLevel = { level -> withContext(Dispatchers.Main.immediate) {
                        if (request == generation && stage == MicStage.RECORDING)
                            audioLevel = audioLevel * 0.55f + level * 0.45f
                    } },
                )
                if (request != generation) return@launch
                stage = MicStage.IDLE
                if (result == null) stage = MicStage.NO_MATCH
                else searchLaunch = MusicSearchLaunch(result.artist, result.title,
                    result.artworkUrl, "Трек распознан по звуку")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (request == generation) {
                    AppLog.w("Recognition", "Live music recognition failed: ${error.javaClass.simpleName}")
                    stage = MicStage.ERROR
                }
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startListening() else stage = MicStage.PERMISSION_DENIED
    }
    fun requestListening() {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED) startListening()
        else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Text("Поиск музыки", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            Text("Узнайте трек по звучанию или найдите его по названию",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Button(onClick = ::requestListening, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Mic, contentDescription = null)
                Text("Распознать по звуку", Modifier.padding(start = 8.dp))
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { openManual() }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Search, contentDescription = null)
                Text("Найти по названию", Modifier.padding(start = 8.dp))
            }
        }
    }

    if (stage != MicStage.IDLE) AlertDialog(
        onDismissRequest = ::stopListening,
        title = { Text("Распознать музыку") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (stage) {
                MicStage.RECORDING -> {
                    Text("Слушаю… $recordedSeconds с")
                    MicrophoneWave(audioLevel)
                    Text(if (recordedSeconds < RecognitionAudioSpec.MIN_SECONDS)
                        "Распознавание доступно через ${RecognitionAudioSpec.MIN_SECONDS} с"
                        else "Можно распознать сейчас · до ${RecognitionAudioSpec.TARGET_SECONDS} с запись завершится сама",
                        style = MaterialTheme.typography.bodySmall)
                }
                MicStage.MATCHING -> {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator()
                        Text("Ищу совпадение…")
                    }
                }
                MicStage.NO_MATCH -> Text("Трек не определён. Можно повторить запись или поискать по названию.")
                MicStage.ERROR -> Text("Не удалось распознать звук. Проверьте микрофон и интернет или найдите трек по названию.")
                MicStage.PERMISSION_DENIED -> Text("Для поиска по звучанию разрешите доступ к микрофону в настройках приложения.")
                MicStage.IDLE -> Unit
            }
        } },
        confirmButton = {
            when (stage) {
                MicStage.RECORDING -> if (recordedSeconds >= RecognitionAudioSpec.MIN_SECONDS)
                    TextButton(onClick = {
                        finishRequested.set(true)
                        stage = MicStage.MATCHING
                    }) { Text("Распознать сейчас") }
                MicStage.NO_MATCH, MicStage.ERROR ->
                    TextButton(onClick = ::requestListening) { Text("Повторить") }
                MicStage.PERMISSION_DENIED -> TextButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${context.packageName}")))
                    stopListening()
                }) { Text("Настройки") }
                else -> Unit
            }
        },
        dismissButton = {
            TextButton(onClick = {
                if (stage == MicStage.NO_MATCH || stage == MicStage.ERROR) openManual()
                else stopListening()
            }) { Text(if (stage == MicStage.NO_MATCH || stage == MicStage.ERROR)
                "По названию" else "Отмена") }
        },
    )

    searchLaunch?.let { launch ->
        FullSongSearchDialog(
            source = FullSongSource.AUTO,
            initialArtist = launch.artist,
            initialTitle = launch.title,
            recognizedArtworkUrl = launch.artworkUrl,
            recognitionMessage = launch.message,
            onDismiss = { searchLaunch = null },
        )
    }
}

@Composable
private fun MicrophoneWave(level: Float) {
    val intensity by animateFloatAsState(level.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 120), label = "Уровень звука")
    val color = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(56.dp)) {
        val count = 21
        val step = size.width / count
        val width = step * 0.42f
        repeat(count) { index ->
            val position = index.toFloat() / (count - 1)
            val envelope = 0.45f + (1f - kotlin.math.abs(position * 2f - 1f)) * 0.55f
            val variation = 0.7f + (index * 13 % 7) / 20f
            val height = 5.dp.toPx() + (size.height - 5.dp.toPx()) * intensity * envelope * variation
            drawRoundRect(color, topLeft = Offset(index * step + (step - width) / 2f,
                (size.height - height) / 2f), size = Size(width, height),
                cornerRadius = CornerRadius(width / 2f))
        }
    }
}
