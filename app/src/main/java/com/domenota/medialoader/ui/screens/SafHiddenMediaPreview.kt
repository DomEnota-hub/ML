package com.domenota.medialoader.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.domenota.medialoader.data.catalog.visibility.NomediaSafEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Media is opened directly from the user-granted SAF URI.
 * No synthetic DownloadEntity, MediaStore rebind, gallery cache, thumbnails
 * outside this dialog or mutations of the original document.
 */
@Composable
internal fun SafHiddenMediaPreview(
    entry:NomediaSafEntry, uri:Uri, onClose:()->Unit,
) {
    AlertDialog(
        onDismissRequest=onClose,
        title={Text(entry.displayName.take(100),style=MaterialTheme.typography.titleMedium)},
        text={Column(Modifier.fillMaxWidth().testTag("saf-hidden-preview"),
            verticalArrangement=Arrangement.spacedBy(12.dp)) {
            when {
                entry.mimeType.startsWith("image/") -> SafStillImage(uri)
                entry.mimeType.startsWith("video/") -> SafVideoPreview(uri)
                entry.mimeType.startsWith("audio/") -> SafAudioPreview(uri)
                else -> Text("Формат не поддерживается")
            }
            Text("Только просмотр через SAF. Файл, дата и история не изменены.",
                style=MaterialTheme.typography.bodySmall,
                color=MaterialTheme.colorScheme.onSurfaceVariant)
        }},
        confirmButton={TextButton(onClick=onClose){Text("Закрыть")}},
    )
}

@Composable
private fun SafStillImage(uri:Uri) {
    val context=LocalContext.current
    var preview by remember(uri) { mutableStateOf<Bitmap?>(null) }
    var failure by remember(uri) { mutableStateOf(false) }
    LaunchedEffect(uri) {
        try {
            val decoded=withContext(Dispatchers.IO) {
                val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it,null,bounds)
                }
                check(bounds.outWidth>0 && bounds.outHeight>0) { "Повреждённое изображение" }
                var sample=1
                while(maxOf(bounds.outWidth/sample,bounds.outHeight/sample)>1536) sample*=2
                val options=BitmapFactory.Options().apply { inSampleSize=sample }
                checkNotNull(context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it,null,options)
                })
            }
            preview=decoded
        } catch(cancelled:CancellationException) { throw cancelled }
        catch(_:Exception) { failure=true }
    }
    DisposableEffect(uri) {
        onDispose { preview=null }
    }
    when {
        preview!=null -> Image(bitmap=checkNotNull(preview).asImageBitmap(),
            contentDescription="Изображение SAF",contentScale=ContentScale.Fit,
            modifier=Modifier.fillMaxWidth().heightIn(max=360.dp))
        failure -> Text("Изображение недоступно через SAF")
        else -> CircularProgressIndicator()
    }
}

@Composable
private fun SafVideoPreview(uri:Uri) {
    val context=LocalContext.current
    val video=remember(uri) {
        VideoView(context).apply {
            setVideoURI(uri)
            setMediaController(MediaController(context))
        }
    }
    DisposableEffect(video) { onDispose { video.stopPlayback() } }
    AndroidView(factory={ video },modifier=Modifier.fillMaxWidth().height(260.dp))
    TextButton(onClick={video.start()}) { Text("Воспроизвести") }
}

@Composable
private fun SafAudioPreview(uri:Uri) {
    val context=LocalContext.current
    var prepared by remember(uri) { mutableStateOf(false) }
    var playing by remember(uri) { mutableStateOf(false) }
    var failure by remember(uri) { mutableStateOf(false) }
    val player=remember(uri) { MediaPlayer() }
    DisposableEffect(uri,player) {
        player.setOnPreparedListener { prepared=true }
        player.setOnCompletionListener { playing=false }
        player.setOnErrorListener { _,_,_ -> failure=true;playing=false;true }
        try {
            player.setDataSource(context,uri)
            player.prepareAsync()
        } catch(_:Exception) { failure=true }
        onDispose {
            runCatching { player.stop() }
            runCatching { player.release() }
            playing=false
        }
    }
    if(failure) Text("Аудиофайл недоступен через SAF")
    else Text(if(prepared) "Аудиофайл готов к воспроизведению" else "Чтение аудиофайла…")
    TextButton(enabled=prepared && !failure,onClick={
        try {
            if(player.isPlaying) { player.pause();playing=false }
            else { player.start();playing=true }
        } catch(_:Exception) { failure=true }
    }) { Text(if(playing) "Пауза" else "Воспроизвести") }
}
