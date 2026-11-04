package com.domenota.medialoader.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.domenota.medialoader.core.recognition.MusicRecognitionResult
import com.domenota.medialoader.data.MediaRepository
import com.domenota.medialoader.data.search.FullSongSource
import kotlinx.coroutines.CancellationException

/** A short temporary sample is decoded on IO; dismissing cancels extraction/recognition. */
@Composable
fun VideoSongSearchDialog(savedUri: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var busy by remember(savedUri) { mutableStateOf(true) }
    var result by remember(savedUri) { mutableStateOf<MusicRecognitionResult?>(null) }
    var message by remember(savedUri) { mutableStateOf<String?>(null) }
    LaunchedEffect(savedUri) {
        try {
            result = MediaRepository.get(context).recognizeSavedVideo(savedUri)
            message = if (result == null) "Композицию определить не удалось. Введите исполнителя и название вручную."
                else "Трек распознан по звуку видео"
        } catch (e: CancellationException) {
            throw e
        } catch (e: com.domenota.medialoader.data.recognition.SavedVideoRecognitionFailure) {
            message = e.stage.userMessage + " Можно ввести название вручную."
        } catch (e: Exception) {
            com.domenota.medialoader.core.logging.AppLog.e("VideoRecognition", "Could not start recognition", e)
            message = "Не удалось запустить распознавание видео. Можно ввести название вручную."
        } finally { busy = false }
    }
    if (busy) AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ищем трек") },
        text = { CircularProgressIndicator() },
        confirmButton = { NeutralTextButton(onDismiss) { Text("Отмена") } },
    ) else FullSongSearchDialog(
        source = FullSongSource.fromStored(context.getSharedPreferences("ui", 0)
            .getString(FullSongSource.PREF_KEY, null)),
        initialArtist = result?.artist.orEmpty(), initialTitle = result?.title.orEmpty(),
        recognizedArtworkUrl = result?.artworkUrl, recognitionMessage = message,
        onDismiss = onDismiss,
    )
}
