package com.domenota.medialoader.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.*
import com.domenota.medialoader.data.library.*
import com.domenota.medialoader.ui.components.*
import com.domenota.medialoader.ui.model.toUi
import com.domenota.medialoader.ui.screens.music.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun MusicModeScreen(
    items: List<DownloadEntity>, allAudio: List<DownloadEntity>, playlists: List<MediaPlaylist>, header: @Composable () -> Unit,
    loading: Boolean, message: String?, onOpen: (String, List<String>) -> Unit, onOpenPrivate: () -> Unit,
    onCreate: suspend (String) -> String, onRename: suspend (String, String) -> Unit, onDeletePlaylist: suspend (String) -> Unit,
    onAddTracks: suspend (String, List<String>) -> Unit, onRemoveTrack: suspend (String, String) -> Unit,
    onMoveTrack: suspend (String, String, Int) -> Unit, onShare: (String) -> Unit, onDeleteFile: (String) -> Unit,
    searchVisible: Boolean? = null, tabOverride: Int? = null, onTabChange: (Int) -> Unit = {},
    onPreview: ((String, List<String>) -> Unit)? = null, onAddPlaylistTracks: ((String) -> Unit)? = null,
    onEnqueue: ((String, Boolean) -> Unit)? = null,
    onDetailChange: (Boolean) -> Unit = {}, currentPlayingId: String? = null, playing: Boolean = false,
) {
    var localTab by rememberSaveable { mutableIntStateOf(0) }
    val tab = tabOverride ?: localTab
    fun selectTab(value: Int) { localTab = value; onTabChange(value) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var search by rememberSaveable { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<MediaPlaylist?>(null) }
    var deleteTarget by remember { mutableStateOf<MediaPlaylist?>(null) }
    var addTrack by remember { mutableStateOf<String?>(null) }
    var addingPlaylist by rememberSaveable { mutableStateOf<String?>(null) }
    var fallbackSource by rememberSaveable { mutableStateOf(LibrarySource.APP) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun mutate(action: suspend () -> Unit) { scope.launch {
        busy = true
        try { action(); error = null } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "Не удалось выполнить действие" }
        finally { busy = false }
    } }
    val available = remember(allAudio) { allAudio.filter(::availableAudio).associateBy { it.id } }
    val tracks = remember(items) { items.filter(::availableAudio).distinctBy { it.id } }
    val playlist = playlists.firstOrNull { it.id == selectedId }.takeIf { tab == 1 }
    LaunchedEffect(searchVisible) { if (searchVisible == false) search = "" }
    LaunchedEffect(playlist?.id, addingPlaylist) { onDetailChange(playlist != null || addingPlaylist != null) }
    DisposableEffect(Unit) { onDispose { onDetailChange(false) } }
    BackHandler(playlist != null || tab == 1) { if (playlist != null) selectedId = null else selectTab(0) }
    val toAdd = playlists.firstOrNull { it.id == addingPlaylist }
    if (toAdd != null) {
        PlaylistTracksScreen(toAdd, fallbackSource, { fallbackSource = it }, tracks, available.values.toList(), emptyMap(),
            { addingPlaylist = null }, onPreview ?: onOpen, { onAddTracks(toAdd.id, it) })
        return
    }
    val displayed = (if (tab == 0) tracks else playlist?.trackIds?.mapNotNull(available::get).orEmpty())
        .filter { matchesMusicSearch(it, search) }
    val displayedIds = if (playlist == null) displayed.map { it.id } else playlist.trackIds.filter { id ->
        available[id]?.let { matchesMusicSearch(it, search) } ?: search.isBlank()
    }
    val queue = displayed.map { it.id }
    fun preview(id: String) { (onPreview ?: onOpen)(id, queue) }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        LazyColumn(Modifier.weight(1f).testTag("music-library"), contentPadding = PaddingValues(bottom = 12.dp)) {
            item(key = "music-controls") {
                Column {
                    if (playlist == null) header() else Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton({ selectedId = null }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Все плейлисты") }
                        Text(playlist.name, Modifier.weight(1f), fontSize = 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        PlaylistActions(playlist, { renameTarget = playlist }, { deleteTarget = playlist })
                    }
                    if (searchVisible != false && playlist == null) OutlinedTextField(search, { search = it },
                        Modifier.fillMaxWidth().padding(horizontal = 17.dp).padding(bottom = 12.dp), singleLine = true,
                        label = { Text("Поиск музыки") }, leadingIcon = { Icon(Icons.Rounded.Search, null) }, shape = RoundedCornerShape(12.dp))
                    Box(Modifier.padding(horizontal = 17.dp)) { LibraryStatus(loading, message, error,
                        tab == 0 && displayed.isEmpty(), "Нет аудиофайлов") }
                }
            }
            if (tab == 1 && playlist == null) {
                item(key = "playlists-title") { Text("Мои плейлисты", Modifier.padding(horizontal = 17.dp, vertical = 15.dp), fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
                items(playlists.chunked(2), key = { it.first().id }) { pair ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 17.dp).padding(bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        pair.forEach { entry ->
                            Column(Modifier.weight(1f).clickable { selectedId = entry.id }) {
                                PlaylistCover(entry, available, Modifier.fillMaxWidth().aspectRatio(1.25f))
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(entry.name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("${entry.trackIds.size} треков", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    PlaylistActions(entry, { renameTarget = entry }, { deleteTarget = entry })
                                }
                            }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                item(key = "playlist-create") {
                    OutlinedButton({ creating = true }, Modifier.fillMaxWidth().padding(horizontal = 17.dp), shape = RoundedCornerShape(18.dp)) {
                        Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(8.dp)); Text("Создать плейлист")
                    }
                    if (playlists.isEmpty()) Text("Пока нет плейлистов", Modifier.padding(17.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                if (playlist != null) item(key = "playlist-summary") {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 19.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(17.dp)) {
                        PlaylistCover(playlist, available, Modifier.size(100.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            val minutes = playlist.trackIds.mapNotNull(available::get).sumOf { (it.durationMs ?: 0) / 60_000 }
                            Text("${playlist.trackIds.size} треков" + if (minutes > 0) " · $minutes минут" else "", fontSize = 13.sp)
                            Text("Музыка для тебя", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Button(enabled = displayed.isNotEmpty(), onClick = { displayed.firstOrNull()?.let { preview(it.id) } }, shape = RoundedCornerShape(18.dp)) {
                                Icon(Icons.Rounded.PlayArrow, null); Text("Слушать", fontSize = 13.sp)
                            }
                        }
                    }
                    OutlinedButton({ if (onAddPlaylistTracks != null) onAddPlaylistTracks(playlist.id) else addingPlaylist = playlist.id },
                        Modifier.fillMaxWidth().padding(horizontal = 17.dp, vertical = 12.dp), shape = RoundedCornerShape(15.dp)) {
                        Icon(Icons.Rounded.Add, "Добавить треки"); Spacer(Modifier.width(8.dp)); Text("Добавить треки")
                    }
                    val missing = playlist.trackIds.count { it !in available }
                    if (missing > 0) Text("Недоступно треков: $missing. Записи сохранены в плейлисте.", Modifier.padding(horizontal = 17.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(displayedIds, key = { it }) { id ->
                    val track = available[id] ?: tracks.firstOrNull { it.id == id }
                    if (track == null && playlist != null) UnavailablePlaylistTrackRow(playlist.trackIds.indexOf(id) + 1) {
                        mutate { onRemoveTrack(playlist.id, id) }
                    } else if (track != null) MusicLibraryTrackRow(track, { preview(id) }, id == currentPlayingId, playing) {
                        MusicTrackActions(track, onEnqueue?.let { action -> { next -> action(id, next) } }, { onOpen(id, queue) }, { addTrack = id }, { onShare(id) }, { onDeleteFile(id) },
                            playlist?.let { entry -> { mutate { onRemoveTrack(entry.id, id) } } },
                            playlist?.takeIf { it.trackIds.indexOf(id) > 0 }?.let { entry -> { mutate { onMoveTrack(entry.id, id, -1) } } },
                            playlist?.takeIf { it.trackIds.indexOf(id) < it.trackIds.lastIndex }?.let { entry -> { mutate { onMoveTrack(entry.id, id, 1) } } })
                    }
                }
            }
        }
        if (tabOverride == null && playlist == null) LibraryBottomSwitcher(AppMode.MUSIC, tab, ::selectTab)
    }
    if (creating) LibraryNameDialog("Новый плейлист", "", { creating = false }) { name -> selectedId = onCreate(name); selectTab(1); creating = false }
    renameTarget?.let { target -> LibraryNameDialog("Переименовать плейлист", target.name, { renameTarget = null }) {
        onRename(target.id, it); renameTarget = null
    } }
    deleteTarget?.let { target -> AlertDialog(onDismissRequest = { if (!busy) deleteTarget = null },
        title = { Text("Удалить плейлист «${target.name}»?") }, text = { Text("Аудиофайлы останутся на телефоне. Удалится только плейлист.") },
        confirmButton = { TextButton(enabled = !busy, onClick = { mutate {
            onDeletePlaylist(target.id); if (selectedId == target.id) selectedId = null; deleteTarget = null
        } }) { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error); Spacer(Modifier.width(8.dp)); Text("Удалить плейлист", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton({ deleteTarget = null }, enabled = !busy) { Text("Отмена") } }) }
    addTrack?.let { id -> AlertDialog(onDismissRequest = { addTrack = null }, title = { Text("Добавить в плейлист") },
        text = { Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
            if (playlists.isEmpty()) Text("Сначала создайте плейлист")
            playlists.forEach { entry -> TextButton(enabled = !busy && id !in entry.trackIds, onClick = { mutate { onAddTracks(entry.id, listOf(id)); addTrack = null } }) { Text(entry.name) } }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = { TextButton({ addTrack = null }) { Text("Закрыть") } }) }
}

private fun availableAudio(item: DownloadEntity) = item.state == DownloadState.COMPLETED && !item.hidden && !item.isPrivate && item.mediaType == MediaType.AUDIO
private fun matchesMusicSearch(track: DownloadEntity, search: String) = listOfNotNull(track.originalName, track.recognizedArtist,
    track.recognizedTitle, track.sourceAudioArtist, track.sourceAudioTitle).any { it.contains(search, true) }

@Composable
private fun PlaylistCover(playlist: MediaPlaylist, available: Map<String, DownloadEntity>, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer), contentAlignment = Alignment.Center) {
        val cover = playlist.trackIds.firstNotNullOfOrNull(available::get)
        if (cover != null) DownloadThumbnail(cover.savedUri, cover.recognitionArtworkUrl ?: cover.sourceAudioArtworkUrl ?: cover.previewUrl,
            cover.toUi().kind, Modifier.fillMaxSize())
        else Icon(Icons.Rounded.LibraryMusic, null, Modifier.size(32.dp), MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun UnavailablePlaylistTrackRow(position: Int, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 17.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Rounded.MusicOff, null, Modifier.size(53.dp), MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Недоступный трек", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        IconButton(onRemove) { Icon(Icons.Rounded.PlaylistRemove, "Убрать недоступный трек $position из плейлиста") }
    }
}

@Composable
private fun PlaylistActions(playlist: MediaPlaylist, onRename: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, "Действия с плейлистом ${playlist.name}") }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem({ Text("Переименовать плейлист") }, { menu = false; onRename() }, leadingIcon = { Icon(Icons.Rounded.Edit, null) })
            DropdownMenuItem({ Text("Удалить плейлист", color = MaterialTheme.colorScheme.error) }, { menu = false; onDelete() },
                leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) })
        }
    }
}

@Composable
private fun MusicTrackActions(track: DownloadEntity, onEnqueue: ((Boolean) -> Unit)?, onOpen: () -> Unit, onAdd: () -> Unit, onShare: () -> Unit, onDelete: () -> Unit,
                              onRemove: (() -> Unit)?, onUp: (() -> Unit)?, onDown: (() -> Unit)?) {
    var menu by remember { mutableStateOf(false) }
    Box {
        IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, "Действия: ${track.originalName}") }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem({ Text("Открыть") }, { menu = false; onOpen() }, leadingIcon = { Icon(Icons.Rounded.PlayArrow, null) })
            if (onEnqueue != null) {
                DropdownMenuItem({ Text("Играть следующим") }, { menu = false; onEnqueue(true) }, leadingIcon = { Icon(Icons.Rounded.SkipNext, null) })
                DropdownMenuItem({ Text("Добавить в конец очереди") }, { menu = false; onEnqueue(false) }, leadingIcon = { Icon(Icons.Rounded.QueueMusic, null) })
            }
            DropdownMenuItem({ Text("Добавить в плейлист") }, { menu = false; onAdd() }, leadingIcon = { Icon(Icons.Rounded.PlaylistAdd, null) })
            if (onRemove != null) DropdownMenuItem({ Text("Убрать из плейлиста") }, { menu = false; onRemove() }, leadingIcon = { Icon(Icons.Rounded.PlaylistRemove, null) })
            if (onUp != null) DropdownMenuItem({ Text("Выше") }, { menu = false; onUp() }, leadingIcon = { Icon(Icons.Rounded.ArrowUpward, null) })
            if (onDown != null) DropdownMenuItem({ Text("Ниже") }, { menu = false; onDown() }, leadingIcon = { Icon(Icons.Rounded.ArrowDownward, null) })
            DropdownMenuItem({ Text("Поделиться") }, { menu = false; onShare() }, leadingIcon = { Icon(Icons.Rounded.Share, null) })
            DropdownMenuItem({ Text("Удалить файл", color = MaterialTheme.colorScheme.error) }, { menu = false; onDelete() },
                leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) })
        }
    }
}
