package com.domenota.medialoader.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.domenota.medialoader.core.recognition.MusicRecognitionResult
import com.domenota.medialoader.data.MediaRepository
import com.domenota.medialoader.data.recognition.SavedVideoRecognitionStage
import com.domenota.medialoader.data.search.FullSongSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

private val recognitionAccent: Color
    @Composable get() = if (com.domenota.medialoader.ui.theme.isDarkPalette) MaterialTheme.colorScheme.primary else Color(0xFF8456EC)
private val recognitionAccentSoft: Color
    @Composable get() = if (com.domenota.medialoader.ui.theme.isDarkPalette) MaterialTheme.colorScheme.primary else Color(0xFF9573FF)

/** Compact progress UI; the stage is reported by the decoder / recognizer, not guessed by a timer. */
@Composable
fun VideoSongSearchDialog(savedUri: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var busy by remember(savedUri) { mutableStateOf(true) }
    var result by remember(savedUri) { mutableStateOf<MusicRecognitionResult?>(null) }
    var message by remember(savedUri) { mutableStateOf<String?>(null) }
    var stage by remember(savedUri) { mutableStateOf(SavedVideoRecognitionStage.EXTRACTING_AUDIO) }
    var elapsedSeconds by remember(savedUri) { mutableLongStateOf(0L) }

    LaunchedEffect(savedUri) {
        try {
            result = MediaRepository.get(context).recognizeSavedVideo(savedUri) { stage = it }
            message = if (result == null)
                "Композицию определить не удалось. Введите исполнителя и название вручную."
            else "Трек распознан по звуку видео"
        } catch (e: CancellationException) {
            throw e
        } catch (e: com.domenota.medialoader.data.recognition.SavedVideoRecognitionFailure) {
            val rateLimit = e.cause as? com.domenota.medialoader.data.recognition.ShazamRateLimitException
            message = (rateLimit?.message ?: e.stage.userMessage) +
                " Можно ввести название вручную."
        } catch (e: Exception) {
            com.domenota.medialoader.core.logging.AppLog.e(
                "VideoRecognition", "Could not start recognition", e)
            message = "Не удалось запустить распознавание видео. Можно ввести название вручную."
        } finally {
            busy = false
        }
    }
    LaunchedEffect(savedUri, busy) {
        while (busy) {
            delay(1_000L)
            elapsedSeconds += 1L
        }
    }

    if (busy) {
        Dialog(onDismissRequest = onDismiss) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(26.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(22.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(
                            modifier = Modifier.size(52.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(16.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Rounded.GraphicEq, contentDescription = null,
                                tint = recognitionAccentSoft, modifier = Modifier.size(28.dp))
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("Распознаём музыку", style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold)
                            Text("Анализируем звуковой фрагмент",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    RecognitionWaveform()

                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp,
                            color = recognitionAccent)
                        Text(if (stage == SavedVideoRecognitionStage.EXTRACTING_AUDIO)
                                "Извлекаем аудио…" else "Ищем совпадение…",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f))
                        Text("%02d:%02d".format(elapsedSeconds / 60, elapsedSeconds % 60),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        NeutralTextButton(onDismiss) { Text("Отмена") }
                    }
                }
            }
        }
    } else {
        FullSongSearchDialog(
            source = FullSongSource.fromStored(context.getSharedPreferences("ui", 0)
                .getString(FullSongSource.PREF_KEY, null)),
            initialArtist = result?.artist.orEmpty(),
            initialTitle = result?.title.orEmpty(),
            recognizedArtworkUrl = result?.artworkUrl,
            recognitionMessage = message,
            onDismiss = onDismiss,
        )
    }
}

@Composable
private fun RecognitionWaveform() {
    val heights = remember { listOf(4, 9, 16, 24, 13, 27, 35, 18, 28, 12, 20, 8, 5) }
    val transition = rememberInfiniteTransition(label = "music-recognition-wave")
    Box(Modifier.fillMaxWidth().height(52.dp), contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            heights.forEachIndexed { index, height ->
                val scale by transition.animateFloat(
                    initialValue = 0.60f, targetValue = 1.15f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 540 + index * 57),
                        repeatMode = RepeatMode.Reverse),
                    label = "wave-$index",
                )
                Box(Modifier.width(5.dp).height((height * scale).dp)
                    .background(if (index % 3 == 0) recognitionAccentSoft else recognitionAccent,
                        RoundedCornerShape(10.dp)))
            }
        }
    }
}
