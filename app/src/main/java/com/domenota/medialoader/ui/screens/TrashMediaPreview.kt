package com.domenota.medialoader.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.domenota.medialoader.ui.model.DownloadUiItem
import com.domenota.medialoader.ui.model.MediaKind
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.*

@Composable
internal fun TrashMediaPreview(item: DownloadUiItem, queue: List<DownloadUiItem>, onClose: () -> Unit) {
    fun entity(row: DownloadUiItem) = DownloadEntity(row.id, "device", row.title,
        if (row.kind == MediaKind.VIDEO) MediaType.VIDEO else MediaType.PHOTO,
        DownloadState.COMPLETED, row.savedUri, null, 0)
    val rows = remember(queue) { queue.filter { it.kind != MediaKind.AUDIO }.map(::entity) }
    val first = remember(item) { entity(item) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black)) {
            TextButton(onClick = onClose) { Text("Закрыть", color = Color.White) }
            if (item.kind == MediaKind.AUDIO) Text("Для просмотра аудио восстановите файл", color = Color.White)
            else MediaSwipePages(first, rows, {}, Modifier.fillMaxWidth().weight(1f))
        }
    }
}
