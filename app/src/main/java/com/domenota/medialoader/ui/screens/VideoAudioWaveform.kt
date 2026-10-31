package com.domenota.medialoader.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** Real decoded audio envelope for the whole source (no invented sine-wave placeholder). */
@Composable
internal fun VideoAudioWaveform(uri: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var waveform by remember(uri) { mutableStateOf<Bitmap?>(null) }
    var unavailable by remember(uri) { mutableStateOf(false) }
    LaunchedEffect(uri) {
        waveform = null
        unavailable = false
        val folder = File(context.cacheDir, "video-waveform-${UUID.randomUUID()}")
        try {
            withContext(Dispatchers.IO) {
                check(folder.mkdirs())
                val input = File(folder, "source")
                context.contentResolver.openInputStream(Uri.parse(uri))?.use { source ->
                    input.outputStream().use { source.copyTo(it) }
                } ?: error("Не удалось прочитать аудиофайл")
                require(input.length() > 0L)
                val picture = File(folder, "wave.png")
                val args = arrayOf("-hide_banner", "-nostdin", "-y",
                    "-i", input.absolutePath,
                    "-filter_complex", "[0:a:0]showwavespic=s=480x64:colors=white[out]",
                    "-map", "[out]", "-frames:v", "1",
                    "-update", "1", picture.absolutePath)
                val ok = suspendCancellableCoroutine<Boolean> { continuation ->
                    val session = FFmpegKit.executeWithArgumentsAsync(args) { result ->
                        if (continuation.isActive)
                            continuation.resume(ReturnCode.isSuccess(result.returnCode))
                    }
                    continuation.invokeOnCancellation { FFmpegKit.cancel(session.sessionId) }
                }
                if (ok && picture.isFile) {
                    BitmapFactory.decodeFile(picture.absolutePath)
                } else null
            }?.let { waveform = it } ?: run { unavailable = true }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            unavailable = true
        } finally {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                folder.deleteRecursively()
            }
        }
    }
    val latestWaveform by rememberUpdatedState(waveform)
    DisposableEffect(uri) {
        onDispose { latestWaveform?.recycle() }
    }
    when {
        waveform != null -> Image(waveform!!.asImageBitmap(),
            contentDescription = "Форма звуковой волны",
            modifier = modifier.fillMaxWidth().height(42.dp),
            contentScale = ContentScale.FillBounds)
        unavailable -> Text("Форма волны недоступна для этого файла",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall)
        else -> Text("Анализируем звуковую волну…",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall)
    }
}
