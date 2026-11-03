package com.domenota.medialoader.ui.screens

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.domenota.medialoader.ui.model.DownloadUiItem
import com.domenota.medialoader.ui.model.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Read-only preview: never restore a trashed item just to display it. */
@Composable
internal fun TrashMediaPreview(item: DownloadUiItem, onClose: () -> Unit) {
    val context = LocalContext.current
    val uri = remember(item.savedUri) { Uri.parse(item.savedUri.orEmpty()) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black).padding(12.dp)) {
            TextButton(onClick = onClose) { Text("Закрыть", color = Color.White) }
            if (item.kind == MediaKind.VIDEO) {
                LocalVideoPlayer(item.id, uri, false, false, {}, {}, {}, {}, allowExternal = false,
                    modifier = Modifier.fillMaxWidth().weight(1f))
            } else if (item.kind == MediaKind.IMAGE) {
                var failed by remember(uri) { mutableStateOf(false) }
                val bitmap by produceState<android.graphics.Bitmap?>(null, uri) {
                    value = withContext(Dispatchers.IO) {
                        runCatching {
                            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                            var sample = 1
                            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
                            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null,
                                BitmapFactory.Options().apply { inSampleSize = sample }) }
                        }.getOrNull()
                    }
                    failed = value == null
                }
                bitmap?.let { Image(it.asImageBitmap(), item.title, Modifier.fillMaxWidth().weight(1f), contentScale = ContentScale.Fit) }
                    ?: Text(if (failed) "Android не предоставил доступ к файлу в корзине" else "Загрузка…", color = Color.White)
            } else Text("Для просмотра аудио восстановите файл", color = Color.White)
        }
    }
}
