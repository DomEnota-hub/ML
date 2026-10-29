package com.domenota.medialoader.ui.screens.music

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.*
import com.domenota.medialoader.data.library.*
import com.domenota.medialoader.ui.components.LibrarySourceSelector
import com.domenota.medialoader.ui.screens.audioTitle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun PlaylistTracksScreen(
    playlist: MediaPlaylist, source: LibrarySource, onSource: (LibrarySource) -> Unit,
    appTracks: List<DownloadEntity>, allTracks: List<DownloadEntity>, aliases: Map<String, String>,
    onBack: () -> Unit, onPreview: (String, List<String>) -> Unit, onAdd: suspend (List<String>) -> Unit,
    validateSelection: suspend (List<String>) -> List<String> = { it }, miniPlayer: @Composable () -> Unit = {},
    loading: Boolean = false, message: String? = null, onAccess: () -> Unit = {}, onSettings: (() -> Unit)? = null,
    currentPlayingId: String? = null, playing: Boolean = false,
) {
    val saver = remember { listSaver<PlaylistTrackSelectionState, String>(save = { it.selectedKeys }, restore = { PlaylistTrackSelectionState(it) }) }
    var selection by rememberSaveable(playlist.id, stateSaver = saver) { mutableStateOf(PlaylistTrackSelectionState()) }
    var search by rememberSaveable(playlist.id) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var more by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    BackHandler { if (!busy) onBack() }
    LaunchedEffect(aliases) { selection = selection.canonicalize(aliases) }
    fun playable(row: DownloadEntity) = !row.isPrivate && !row.hidden && row.state == DownloadState.COMPLETED &&
        row.mediaType == MediaType.AUDIO && !row.savedUri.isNullOrBlank()
    val available = remember(appTracks, allTracks) { (appTracks + allTracks).filter(::playable).distinctBy { it.id } }
    val existing = playlist.trackIds.mapTo(mutableSetOf()) { aliases[it] ?: it }
    val visible = remember(source, appTracks, available, search) {
        (if (source == LibrarySource.APP) appTracks.filter(::playable) else available).filter { row ->
            listOfNotNull(row.originalName, row.recognizedTitle, row.recognizedArtist, row.sourceAudioTitle, row.sourceAudioArtist)
                .any { it.contains(search.trim(), true) }
        }
    }
    val pending = selection.confirm(available.mapTo(mutableSetOf()) { it.id }, existing)
    val queue = visible.map { it.id }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f)) {
            item(key = "add-controls") {
                Column(Modifier.padding(horizontal = 17.dp)) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onBack, enabled = !busy) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") }
                        Text("Добавить треки", Modifier.weight(1f), fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                        if (onSettings != null) Box {
                            IconButton({ more = true }) { Icon(Icons.Rounded.MoreVert, "Дополнительно") }
                            DropdownMenu(more, { more = false }) {
                                DropdownMenuItem({ Text("Настройки") }, { more = false; onSettings() }, leadingIcon = { Icon(Icons.Rounded.Settings, null) })
                            }
                        }
                    }
                    LibrarySourceSelector(source, onSource)
                    OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth().padding(vertical = 8.dp), singleLine = true,
                        placeholder = { Text("Название или исполнитель", fontSize = 13.sp) }, shape = RoundedCornerShape(12.dp))
                    Text("Нажми на трек, чтобы послушать. На +, чтобы выбрать.", Modifier.padding(bottom = 12.dp),
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (source == LibrarySource.PHONE && message != null) {
                        Text(message, fontSize = 12.sp)
                        TextButton(onAccess) { Text("Разрешить доступ") }
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                    if (pending.unavailableKeys.isNotEmpty()) Text("Недоступно выбранных треков: ${pending.unavailableKeys.size}",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!loading && visible.isEmpty()) Text("Нет подходящих треков", Modifier.padding(vertical = 16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(visible, key = { it.id }) { track ->
                val included = track.id in existing
                val selected = track.id in selection.selectedKeys
                MusicLibraryTrackRow(track, { onPreview(track.id, queue) }, track.id == currentPlayingId, playing) {
                    IconButton(onClick = { selection = selection.toggle(track.id, existing, aliases) }, enabled = !busy && !included,
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = if (selected) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent,
                            contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)) {
                        Icon(if (selected || included) Icons.Rounded.Check else Icons.Rounded.Add,
                            "${if (included) "В плейлисте" else if (selected) "Убрать из выбора" else "Добавить"} ${audioTitle(track)}")
                    }
                }
            }
        }
        miniPlayer()
        Button(enabled = pending.addedKeys.isNotEmpty() && !busy, shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 17.dp, vertical = 12.dp).heightIn(min = 48.dp),
            onClick = {
                busy = true
                scope.launch {
                    try {
                        val valid = validateSelection(pending.addedKeys).toSet()
                        val ready = pending.addedKeys.filter { it in valid }
                        if (ready.isEmpty()) { error = "Выбранные треки больше недоступны"; return@launch }
                        onAdd(ready)
                        if (ready.size != pending.addedKeys.size) Toast.makeText(context,
                            "Добавлено: ${ready.size}. Недоступно: ${pending.addedKeys.size - ready.size}", Toast.LENGTH_LONG).show()
                        onBack()
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { error = e.message ?: "Не удалось добавить треки" }
                    finally { busy = false }
                }
            }) { Text("Добавить · ${pending.addedKeys.size}", fontSize = 13.sp) }
    }
}
