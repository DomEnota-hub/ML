package com.domenota.medialoader.ui.screens.music

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.data.playback.AudioPlaybackMetadata
import com.domenota.medialoader.data.playback.AudioQueuePlayback
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Edits only the service timeline. Saved playlists and files are untouched. */
@Composable
internal fun EditableAudioQueue(items: List<MediaItem>, player: Player?, currentIndex: Int,
    playing: Boolean, lookup: List<DownloadEntity>, blocked: Set<String>, onSelect: (Int) -> Unit) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val rowHeight = with(LocalDensity.current) { 76.dp.toPx() }
    var dragDistance by remember { mutableFloatStateOf(0f) }
    var query by remember { mutableStateOf("") }
    val filtered = remember(items, lookup, blocked, query) {
        items.withIndex().filter { (_, media) ->
            if (query.isBlank()) true else {
                val record = AudioPlaybackMetadata.publicRow(media, lookup, blocked)
                record != null && listOfNotNull(
                    record.originalName, record.recognizedTitle, record.sourceAudioTitle,
                    record.recognizedArtist, record.sourceAudioArtist,
                ).any { it.contains(query.trim(), ignoreCase = true) }
            }
        }
    }
    LaunchedEffect(currentIndex, query.isBlank()) {
        if (query.isBlank()) {
            val position = filtered.indexOfFirst { it.index == currentIndex }
            if (position >= 0) listState.scrollToItem(position)
        }
    }
    Column {
      OutlinedTextField(query, { query = it },
          Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
          singleLine = true, label = { Text("Поиск в очереди") },
          leadingIcon = { Icon(Icons.Rounded.Search, null) })
      LazyColumn(Modifier.heightIn(max = 440.dp), state = listState) {
        if (filtered.isEmpty()) item { Text(if (items.isEmpty()) "Очередь пуста" else "Ничего не найдено", Modifier.padding(24.dp)) }
        itemsIndexed(filtered, key = { _, entry -> entry.value.mediaId }) { _, entry ->
            val index = entry.index
            val media = entry.value
            val row = AudioPlaybackMetadata.publicRow(media, lookup, blocked)
            if (row != null) MusicLibraryTrackRow(row, { onSelect(index) }, index == currentIndex, playing) {
                var menu by remember { mutableStateOf(false) }
                fun liveIndex(): Int = player?.let { active ->
                    (0 until active.mediaItemCount).firstOrNull { active.getMediaItemAt(it).mediaId == media.mediaId }
                } ?: -1
                fun move(delta: Int) { player?.let { active ->
                    val from = liveIndex()
                    AudioQueuePlayback.move(active, from, from + delta)
                } }
                Icon(Icons.Rounded.DragHandle, "Изменить порядок", Modifier.size(40.dp).padding(8.dp)
                    .pointerInput(media.mediaId, player) {
                        detectDragGestures(onDragStart = { dragDistance = 0f },
                            onDragCancel = { dragDistance = 0f }, onDragEnd = { dragDistance = 0f }) { change, amount ->
                            change.consume()
                            dragDistance += amount.y
                            if (abs(dragDistance) >= rowHeight) {
                                val direction = if (dragDistance > 0) 1 else -1
                                val from = liveIndex()
                                val target = from + direction
                                player?.let { active ->
                                    if (AudioQueuePlayback.move(active, from, target)) {
                                        val visible = listState.layoutInfo.visibleItemsInfo
                                        if (target <= (visible.firstOrNull()?.index ?: 0) || target >= (visible.lastOrNull()?.index ?: 0))
                                            scope.launch { listState.scrollBy(direction * rowHeight) }
                                    }
                                }
                                dragDistance -= direction * rowHeight
                            }
                        }
                    })
                Box {
                    IconButton({ menu = true }, Modifier.size(40.dp)) { Icon(Icons.Rounded.MoreVert, "Действия в очереди") }
                    DropdownMenu(menu, { menu = false }) {
                        if (index != currentIndex) DropdownMenuItem({ Text("Играть далее") }, {
                            menu = false
                            player?.let { AudioQueuePlayback.enqueue(it, media, true) }
                        }, leadingIcon = { Icon(Icons.Rounded.SkipNext, null) })
                        DropdownMenuItem({ Text("Выше") }, { menu = false; move(-1) }, enabled = index > 0,
                            leadingIcon = { Icon(Icons.Rounded.ArrowUpward, null) })
                        DropdownMenuItem({ Text("Ниже") }, { menu = false; move(1) }, enabled = index < items.lastIndex,
                            leadingIcon = { Icon(Icons.Rounded.ArrowDownward, null) })
                        DropdownMenuItem({ Text("Убрать из очереди") }, {
                            menu = false; player?.let { AudioQueuePlayback.remove(it, liveIndex()) }
                        }, leadingIcon = { Icon(Icons.Rounded.PlaylistRemove, null) })
                    }
                }
            }
        }
      }
    }
}
