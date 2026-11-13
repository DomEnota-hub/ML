package com.domenota.medialoader.ui.screens.music

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Color
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.data.library.*
import com.domenota.medialoader.data.playback.AudioPlaybackService
import com.domenota.medialoader.ui.theme.musicPlaybackAccent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Read-only scan, comparison playback, then explicit recoverable deletion. */
@OptIn(androidx.media3.common.util.UnstableApi::class, kotlinx.coroutines.FlowPreview::class)
@Composable
internal fun MusicDuplicatesDialog(rows: List<DownloadEntity>, linkedIds: Set<String>, currentId: String?, mainPlaying: Boolean,
    onDismiss: () -> Unit, onTrash: (DuplicateTrashSelection) -> Unit) {
    val context = LocalContext.current
    val latestRows by rememberUpdatedState(rows)
    val latestCurrent by rememberUpdatedState(currentId)
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<DuplicateScanResult?>(null) }
    val listState = rememberLazyListState()
    var previous by remember { mutableStateOf<DuplicateSession?>(null) }
    var scannedAt by remember { mutableLongStateOf(0L) }
    val background by DuplicateBackgroundScan.state.collectAsState()
    val progress = background.done to background.total
    var scanning by remember { mutableStateOf(true) }
    var verifying by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var scanRevision by remember { mutableIntStateOf(0) }
    var kept by remember { mutableStateOf<Map<String,String>>(emptyMap()) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirm by remember { mutableStateOf(false) }
    var previewId by remember { mutableStateOf<String?>(null) }
    var previewPlaying by remember { mutableStateOf(false) }
    var previewError by remember { mutableStateOf<String?>(null) }
    var suspension by remember { mutableStateOf<AudioPlaybackService.Companion.ComparisonPlayback?>(null) }
    val player = remember(context) { ExoPlayer.Builder(context).build().apply {
        setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
        setHandleAudioBecomingNoisy(true)
    } }
    fun stopPreview(restore: Boolean = true) {
        player.stop(); player.clearMediaItems(); previewId = null; previewPlaying = false
        if (restore) AudioPlaybackService.finishComparison(suspension)
        suspension = null
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { previewPlaying = isPlaying }
            override fun onPlayerError(error: PlaybackException) { previewError = "Прослушивание недоступно: ${error.errorCodeName}"; stopPreview() }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener); player.release(); AudioPlaybackService.finishComparison(suspension); suspension = null }
    }
    // Notification or another controller can resume the main player. Do not play two streams.
    LaunchedEffect(mainPlaying) { if (mainPlaying && previewId != null) stopPreview(restore = false) }
    fun play(t: DuplicateTrack) {
        previewError = null
        if (previewId == t.id) {
            if (player.playWhenReady) player.pause() else { if (player.playbackState == Player.STATE_ENDED) player.seekTo(0); player.play() }
            return
        }
        try {
            if (previewId == null) suspension = AudioPlaybackService.pauseForComparison()
            previewId = t.id
            player.setMediaItem(MediaItem.fromUri(Uri.parse(t.uri))); player.prepare(); player.play()
        } catch (e: Exception) { previewError = e.message ?: "Прослушивание недоступно"; stopPreview() }
    }
    LaunchedEffect(scanRevision) {
        scanning = true; result = null; previous = null; error = null; selected = emptySet(); kept = emptyMap()
        try {
            if (!DuplicateBackgroundScan.state.value.running) {
                if (scanRevision == 0) {
                    val saved = MusicDuplicateSessionStore.load(context)
                    if (saved != null) { previous = saved; return@LaunchedEffect }
                }
                DuplicateBackgroundScan.start(context,latestRows,linkedIds,latestCurrent)
            }
            val finished = DuplicateBackgroundScan.state.first { !it.running }
            val saved = finished.session
            if (saved == null) {
                error = finished.error
                previous = MusicDuplicateSessionStore.load(context)
                return@LaunchedEffect
            }
            val r = MusicDuplicateScanner.restore(context,saved.result,latestRows,linkedIds,latestCurrent)
            scannedAt = saved.scannedAt
            kept = r.groups.associate { it.key to MusicDuplicatePolicy.recommended(it) }; result = r
        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message ?: "Ошибка проверки" }
        finally { scanning = false }
    }
    fun snapshot(): DuplicateSession? = result?.let { DuplicateSession(it,selected,kept,listState.firstVisibleItemIndex,listState.firstVisibleItemScrollOffset,scannedAt) }
    LaunchedEffect(result) {
        if (result == null) return@LaunchedEffect
        snapshotFlow { snapshot() }.debounce(350L).collect { saved ->
            if (saved != null && !verifying && !MusicDuplicateSessionStore.save(context,saved)) error = "Не удалось сохранить состояние"
        }
    }
    fun restorePrevious(saved: DuplicateSession) {
        if (scanning) return
        scanning = true; error = null
        scope.launch {
            try {
                check(latestRows.isNotEmpty() || saved.result.groups.isEmpty()) { "Библиотека ещё не загружена" }
                val r = MusicDuplicateScanner.restore(context,saved.result,latestRows,linkedIds,latestCurrent)
                val oldKeys = saved.result.groups.map { it.key }.toSet()
                val recommendations = r.groups.associate { g -> g.key to (saved.kept[g.key]?.takeIf { id -> g.tracks.any { it.id == id } }
                    ?: MusicDuplicatePolicy.recommended(g)) }
                val safe = r.groups.filter { it.key in oldKeys }.flatMap { g -> g.tracks.filter { it.id != recommendations[g.key] && it.id != latestCurrent }.map { it.id } }.toSet()
                selected = saved.selected.intersect(safe); kept = recommendations; scannedAt = saved.scannedAt
                previous = null; result = r
                if (r.groups.isNotEmpty()) listState.requestScrollToItem(saved.index.coerceIn(0,r.groups.lastIndex),saved.offset)
                if (r.skipped > saved.result.skipped) error = "Некоторые файлы изменились. Проверьте результаты или запустите новый поиск."
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Не удалось восстановить поиск" }
            finally { scanning = false }
        }
    }
    val groups = result?.groups.orEmpty()
    val allSelected = MusicDuplicatePolicy.selectAll(groups,kept)
    val size = groups.sumOf { g -> g.tracks.filter { it.id in selected }.sumOf { it.bytes } }
    fun close() {
        stopPreview()
        val saved = snapshot()
        if (saved == null) onDismiss() else scope.launch {
            MusicDuplicateSessionStore.save(context,saved); onDismiss()
        }
    }
    BackHandler(enabled = true) { if (!verifying) close() }
    // This is a NavHost destination within the Activity's bounded Scaffold.
    // No independent Dialog window, no duplicate system-bar fitting.
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().testTag("duplicates-route")) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ close() }, enabled = !verifying) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") }
                    Text("Дубликаты треков", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    IconButton({ stopPreview(); scanRevision++ }, enabled = !scanning && !verifying) { Icon(Icons.Rounded.Refresh, "Повторить поиск") }
                }
                if (scanning) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    LinearProgressIndicator(progress = { if (progress.second == 0) 0f else progress.first.toFloat()/progress.second }, modifier = Modifier.fillMaxWidth())
                    Text("Проверка файлов · ${progress.first}/${progress.second}", Modifier.padding(vertical = 12.dp))
                    if (background.running) {
                        Button({ close() }, Modifier.fillMaxWidth()) { Text("Продолжить в фоне") }
                        TextButton({ DuplicateBackgroundScan.cancel(context) }, enabled = !background.committing) { Text("Отменить поиск") }
                    } else TextButton({ close() }) { Text("Закрыть") }
                    }
                } else if (previous != null) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    val saved = previous!!
                    Text("Последний поиск", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 12.dp))
                    Text(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(saved.scannedAt)))
                    Text("Групп: ${saved.result.groups.size} · файлов: ${saved.result.groups.sumOf { it.tracks.size }}")
                    val savedBytes = saved.result.groups.sumOf { g -> g.tracks.filter { it.id in saved.selected }.sumOf { it.bytes } }
                    Text("Выбрано: ${saved.selected.size} · ${android.text.format.Formatter.formatFileSize(context,savedBytes)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Button({ restorePrevious(saved) }, Modifier.fillMaxWidth()) { Text("Продолжить проверку") }
                    OutlinedButton({ scanRevision++ }, Modifier.fillMaxWidth()) { Text(if (DuplicateBackgroundScan.interrupted(context)) "Продолжить незавершённый поиск" else "Новый поиск") }
                    }
                } else {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Text("Групп: ${groups.size} · файлов: ${groups.sumOf { it.tracks.size }}", style = MaterialTheme.typography.bodyMedium)
                    if ((result?.skipped ?: 0) > 0) Text("Недоступно файлов: ${result!!.skipped}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    previewError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (groups.isEmpty() && error == null) Text("Совпадений не найдено", Modifier.padding(vertical = 24.dp))
                    if (groups.isNotEmpty()) {
                        OutlinedButton({ selected = if (selected == allSelected) emptySet() else allSelected }, Modifier.fillMaxWidth(), enabled = !verifying) { Text(if (selected == allSelected) "Снять выделение" else "Выделить все") }
                    }
                    }
                    Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
                    LazyColumn(Modifier.fillMaxSize().padding(end = if (groups.size > 1) musicFastScrollLaneWidth else 0.dp), state = listState,
                        verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                        items(groups, key = { it.key }) { group ->
                            val keep = kept[group.key] ?: MusicDuplicatePolicy.recommended(group)
                            Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(16.dp)) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(group.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    Text("${group.tracks.size} файлов · ${group.reason}", style = MaterialTheme.typography.bodySmall, color = musicPlaybackAccent)
                                    group.tracks.forEach { t ->
                                        HorizontalDivider()
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            IconButton({ play(t) }, enabled = !verifying) { Icon(if (previewId == t.id && previewPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Прослушать ${t.filename}") }
                                            Column(Modifier.weight(1f)) {
                                                Text(t.filename, fontWeight = FontWeight.Medium)
                                                Text(listOfNotNull(t.artist, t.duration?.let { "%d:%02d".format(it/60000,it/1000%60) }, t.bitrate?.let { "${it/1000} кбит/с" }, android.text.format.Formatter.formatFileSize(context,t.bytes)).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                                                t.folder?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                                MusicDuplicatePolicy.version(t)?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                                                if (t.linked) Text("Есть связи библиотеки", style = MaterialTheme.typography.labelSmall, color = musicPlaybackAccent)
                                                if (t.id == keep) Text("Оставить", color = musicPlaybackAccent, style = MaterialTheme.typography.labelMedium)
                                                else TextButton({
                                                    selected = MusicDuplicatePolicy.switchKeeper(group, selected, keep, t.id)
                                                    kept = kept + (group.key to t.id)
                                                }, enabled = !verifying) { Text("Оставить эту версию") }
                                            }
                                            val checked = t.id in selected
                                            val enabled = t.id != keep && !verifying
                                            val mark = if (checked) musicPlaybackAccent else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else .55f)
                                            Box(Modifier.size(48.dp).testTag("duplicate-select-${t.id}")
                                                .toggleable(value = checked, enabled = enabled, role = Role.Checkbox,
                                                    onValueChange = { value -> selected = if (value) selected + t.id else selected - t.id }),
                                                contentAlignment = Alignment.Center) {
                                                Box(Modifier.size(24.dp).border(2.dp, mark, CircleShape)
                                                    .background(if (checked) mark else Color.Transparent, CircleShape), contentAlignment = Alignment.Center) {
                                                    if (checked) Icon(Icons.Rounded.Check, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.surface)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    MusicFastScroll(listState, emptyList(), Modifier.align(Alignment.CenterEnd),
                        tag = "duplicates-fast-scroll", description = "Быстрая прокрутка дубликатов")
                    }
                    if (groups.isNotEmpty()) {
                        DuplicateResultsFooter(selected.size, android.text.format.Formatter.formatFileSize(context,size), verifying) { confirm = true }
                    }
                }
            }
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Переместить в корзину?") }, text = {
        Text("Файлов: ${selected.size}. Объём: ${android.text.format.Formatter.formatFileSize(context,size)}. Выбор может включать разные версии композиции и треки со связями. Все неотмеченные экземпляры сохраняются.")
    }, dismissButton = { TextButton({ confirm = false }) { Text("Отмена") } }, confirmButton = {
        TextButton({
            confirm = false; val choice = selected.toSet()
            stopPreview(); verifying = true
            scope.launch {
                try {
                    check(MusicDuplicatePolicy.validSelection(groups,choice)) { "Недопустимое выделение" }
                    val verified = mutableSetOf<String>()
                    val failures = linkedMapOf<String,String>()
                    for (group in groups) {
                        val groupChoice = choice.intersect(group.tracks.map { it.id }.toSet())
                        if (groupChoice.isEmpty()) continue
                        try {
                            check(latestCurrent !in groupChoice) { "Текущий трек выбран" }
                            MusicDuplicateScanner.verify(context,listOf(group),groupChoice,latestRows)
                            verified += groupChoice
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { groupChoice.forEach { failures[it] = e.message ?: "Проверка группы не прошла" } }
                    }
                    check(latestCurrent !in verified) { "Текущий трек изменился" }
                    val saved = requireNotNull(snapshot())
                    check(MusicDuplicateSessionStore.save(context,saved)) { "Не удалось сохранить состояние" }
                    onTrash(DuplicateTrashSelection(groups, choice, verified, failures)); onDismiss()
                } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message ?: "Проверка не прошла" }
                finally { verifying = false }
            }
        }) { Text("В корзину") }
    })
}
