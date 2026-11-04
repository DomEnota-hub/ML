package com.domenota.medialoader.ui.screens

import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.core.database.DownloadEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
internal fun MusicFilePropertiesDialog(track: DownloadEntity, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var details by remember(track.id) { mutableStateOf<List<Pair<String,String>>?>(null) }
    LaunchedEffect(track.id, track.savedUri) {
        details = withContext(Dispatchers.IO) {
            val uri = track.savedUri?.let(Uri::parse)
            var size = track.sizeBytes
            var modified: Long? = null
            var location: String? = null
            var mime: String? = null
            var artist = track.recognizedArtist ?: track.sourceAudioArtist
            var title = track.recognizedTitle ?: track.sourceAudioTitle
            var album: String? = null
            var bitrate: String? = null
            if (uri?.scheme == "file") uri.path?.let { path ->
                val file = File(path)
                location = file.absolutePath
                if (file.exists()) { size = file.length(); modified = file.lastModified() }
            }
            if (uri?.scheme == "content") {
                mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
                // Providers vary: a rejected optional column must not hide all other properties.
                fun column(name: String): String? = runCatching {
                    context.contentResolver.query(uri,arrayOf(name),null,null,null)?.use { cursor ->
                        if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
                    }
                }.getOrNull()
                size = column(MediaStore.MediaColumns.SIZE)?.toLongOrNull() ?: size
                modified = column(MediaStore.MediaColumns.DATE_MODIFIED)?.toLongOrNull()?.times(1000)
                location = column(MediaStore.MediaColumns.RELATIVE_PATH)?.let { it + track.originalName }
            }
            if (uri != null) runCatching {
                val reader = MediaMetadataRetriever()
                try {
                    reader.setDataSource(context,uri)
                    artist = artist ?: reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    title = title ?: reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                    album = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                    bitrate = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()?.let { "${it/1000} кбит/с" }
                    mime = mime ?: reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                } finally { reader.release() }
            }
            buildList {
                add("Имя файла" to track.originalName)
                add("Размер" to (size?.let { android.text.format.Formatter.formatFileSize(context,it) } ?: "Неизвестно"))
                add("Дата изменения" to (modified?.let { java.text.SimpleDateFormat("dd.MM.yyyy HH:mm",java.util.Locale.getDefault()).format(java.util.Date(it)) } ?: "Неизвестно"))
                add("Расположение" to (location ?: track.savedUri ?: "Неизвестно"))
                add("Формат" to (mime ?: track.originalName.substringAfterLast('.',"Неизвестно").uppercase()))
                title?.takeIf { it.isNotBlank() }?.let { add("Название" to it) }
                artist?.takeIf { it.isNotBlank() }?.let { add("Исполнитель" to it) }
                album?.takeIf { it.isNotBlank() }?.let { add("Альбом" to it) }
                bitrate?.let { add("Битрейт" to it) }
            }
        }
    }
    AlertDialog(onDismissRequest=onDismiss,title={Text("Свойства")},text={
        Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if (details == null) Text("Читаем свойства…")
            details?.forEach { (label,value) -> Column {
                Text(label,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                androidx.compose.foundation.text.selection.SelectionContainer { Text(value) }
            } }
        }
    },confirmButton={TextButton(onClick=onDismiss){Text("Готово")}})
}
