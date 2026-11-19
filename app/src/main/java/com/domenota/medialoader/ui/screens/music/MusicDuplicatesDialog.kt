package com.domenota.medialoader.ui.screens.music

import com.domenota.medialoader.ui.model.FileNameDisplay
import com.domenota.medialoader.ui.model.LocalFileNameDisplay
import com.domenota.medialoader.ui.model.displayFileName
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import com.domenota.medialoader.core.model.RussianCounts
import com.domenota.medialoader.ui.components.CachedDownloadThumbnail
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.style.TextOverflow
import com.domenota.medialoader.ui.components.AudioWaveform
import com.domenota.medialoader.ui.components.loadAudioWaveform
import com.domenota.medialoader.ui.components.DownloadThumbnail
import com.domenota.medialoader.ui.components.DuplicateScanPanel
import com.domenota.medialoader.ui.components.DuplicateJournalDialog
import com.domenota.medialoader.ui.components.DuplicateJournalRow
import com.domenota.medialoader.ui.model.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
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
    // A recovered checkpoint is not a completed scan. Never promote it into the
    // completed report: both the checkpoint and acoustic cache must survive.
    var showingPartial by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val preliminaryListState=rememberLazyListState()
    val previewGeneration=remember(rows){Any()}
    var previewIndex by remember { mutableStateOf<Pair<Any,Map<String,DownloadEntity>>?>(null) }
    LaunchedEffect(previewGeneration){
        val source=rows
        val indexed=withContext(Dispatchers.Default){previewGeneration to source.filter{!it.isPrivate&&!it.hidden}.associateBy{it.id}}
        previewIndex=indexed
    }
    val previewRows=previewIndex?.takeIf{it.first===previewGeneration}?.second.orEmpty()
    var previous by remember { mutableStateOf<DuplicateSession?>(null) }
    var scannedAt by remember { mutableLongStateOf(0L) }
    val background by DuplicateBackgroundScan.state.collectAsState()
    val privacyRevision by DuplicatePrivacyGuard.changes.collectAsState()
    val progress = background.done to background.total
    var scanning by remember { mutableStateOf(true) }
    var verifying by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var scanRevision by remember { mutableIntStateOf(0) }
    var expandedScan by remember { mutableStateOf(DuplicateBackgroundScan.resumeExpanded(context)) }
    var replaceScan by remember { mutableStateOf(false) }
    var clearJournalConfirm by remember { mutableStateOf(false) }
    var clearPartialToo by remember { mutableStateOf(false) }
    var clearingJournal by remember { mutableStateOf(false) }
    var journalGeneration by remember { mutableLongStateOf(MusicDuplicateSessionStore.journalGeneration()) }
    var journal by remember{mutableStateOf(false)}
    var trashReport by remember{mutableStateOf<DuplicateTrashReport?>(null)}
    var showTrashReport by remember{mutableStateOf(false)}
    LaunchedEffect(privacyRevision){
        showTrashReport=false
        trashReport=MusicDuplicateSessionStore.trashReport(context)
    }
    var history by remember{mutableStateOf(emptyList<DuplicateSession>())}
    var storedPartial by remember{mutableStateOf<DuplicateSession?>(null)}
    var filter by remember{mutableStateOf<DuplicateKind?>(null)}
    var comparing by remember{mutableStateOf<DuplicateGroup?>(null)}
    LaunchedEffect(journal,background.running,privacyRevision){history=MusicDuplicateSessionStore.history(context);storedPartial=MusicDuplicateSessionStore.partial(context)}
    var kept by remember { mutableStateOf<Map<String,String>>(emptyMap()) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirm by remember { mutableStateOf(false) }
    var previewId by remember { mutableStateOf<String?>(null) }
    var previewPlaying by remember { mutableStateOf(false) }
    var previewPosition by remember { mutableLongStateOf(0L) }
    var previewDuration by remember { mutableLongStateOf(0L) }
    var previewSeekable by remember { mutableStateOf(false) }
    var previewError by remember { mutableStateOf<String?>(null) }
    var suspension by remember { mutableStateOf<AudioPlaybackService.Companion.ComparisonPlayback?>(null) }
    val player = remember(context) { ExoPlayer.Builder(context).build().apply {
        setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
        setHandleAudioBecomingNoisy(true)
    } }
    fun stopPreview(restore: Boolean = true) {
        player.stop(); player.clearMediaItems(); previewId = null; previewPlaying = false
        previewPosition=0;previewDuration=0;previewSeekable=false
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
    fun admissible(value: DuplicateScanResult?): Boolean = value != null && value.privacyEpoch==DuplicatePrivacyGuard.generation(context) && value.groups.all { group ->
        group.tracks.all { DuplicatePrivacyGuard.accepts(context,it.privacyEpoch,it.id) }
    }
    LaunchedEffect(privacyRevision,result,previous) {
        if (result?.groups?.any { g -> g.tracks.any { !DuplicatePrivacyGuard.accepts(context,it.privacyEpoch,it.id) } } == true ||
            previous?.result?.groups?.any { g -> g.tracks.any { !DuplicatePrivacyGuard.accepts(context,it.privacyEpoch,it.id) } } == true) {
            stopPreview(); comparing=null;journal=false;result = null; previous = null; selected = emptySet(); kept = emptyMap()
            error = "Поиск сброшен после переноса в Личное"
        }
    }
    LaunchedEffect(previewId) {
        while(previewId!=null){
            previewPosition=player.currentPosition.coerceAtLeast(0)
            previewDuration=player.duration.takeIf{it>0}?:0L
            previewSeekable=player.isCurrentMediaItemSeekable&&previewDuration>0
            delay(200)
        }
    }
    fun seekPreview(t:DuplicateTrack,ms:Long){
        if(previewId!=t.id||!DuplicatePrivacyGuard.accepts(context,t.privacyEpoch,t.id))return
        if(!player.isCurrentMediaItemSeekable||player.duration<=0){previewError="Перемотка недоступна";return}
        try{player.seekTo(ms.coerceIn(0L,player.duration));previewPosition=player.currentPosition}
        catch(_:Exception){previewError="Не удалось перемотать трек"}
    }
    fun play(t: DuplicateTrack,seekMs:Long?=null) {
        if (!DuplicatePrivacyGuard.accepts(context,t.privacyEpoch,t.id)) { stopPreview(); return }
        previewError = null
        if (previewId == t.id) {
            if(seekMs!=null){player.seekTo(seekMs.coerceAtLeast(0));player.play();return}
            if (player.playWhenReady) player.pause() else { if (player.playbackState == Player.STATE_ENDED) player.seekTo(0); player.play() }
            return
        }
        try {
            if (previewId == null) suspension = AudioPlaybackService.pauseForComparison()
            previewPosition=0;previewDuration=0;previewSeekable=false
            previewId = t.id
            player.setMediaItem(MediaItem.fromUri(Uri.parse(t.uri))); if(seekMs!=null)player.seekTo(seekMs.coerceAtLeast(0));player.prepare(); player.play()
        } catch (e: Exception) { previewError = e.message ?: "Прослушивание недоступно"; stopPreview() }
    }
    LaunchedEffect(scanRevision) {
        journalGeneration = MusicDuplicateSessionStore.journalGeneration()
        scanning = true; showingPartial = false; result = null; previous = null; error = null; selected = emptySet(); kept = emptyMap()
        try {
            if (!DuplicateBackgroundScan.state.value.running) {
                if (scanRevision == 0) {
                    val saved = MusicDuplicateSessionStore.load(context)
                    if (saved != null && admissible(saved.result)) { previous = saved; return@LaunchedEffect }
                    // The initial route restores a report or offers an explicit mode choice; it never starts implicitly.
                    return@LaunchedEffect
                }
                DuplicateBackgroundScan.start(context,latestRows,linkedIds,latestCurrent,expandedScan)
            }
            val finished = DuplicateBackgroundScan.state.first { !it.running }
            val saved = finished.session
            if (saved == null) {
                error = finished.error
                previous = MusicDuplicateSessionStore.load(context)?.takeIf { admissible(it.result) }
                return@LaunchedEffect
            }
            val r = MusicDuplicateScanner.restore(context,saved.result,latestRows,linkedIds,latestCurrent)
            check(admissible(r)) { "Поиск сброшен после переноса в Личное" }
            scannedAt = saved.scannedAt
            kept = r.groups.associate { it.key to MusicDuplicatePolicy.recommended(it) }; result = r
        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message ?: "Ошибка проверки" }
        finally { scanning = false }
    }
    fun snapshot(): DuplicateSession? = result?.takeIf(::admissible)?.let { DuplicateSession(it,selected,kept,listState.firstVisibleItemIndex,listState.firstVisibleItemScrollOffset,scannedAt) }
    LaunchedEffect(result,journalGeneration,showingPartial) {
        if (result == null) return@LaunchedEffect
        val generation = journalGeneration
        snapshotFlow { snapshot() }.debounce(350L).collect { saved ->
            if (saved != null && !verifying) {
                val savedOk = if (showingPartial) withContext(Dispatchers.IO) { MusicDuplicateSessionStore.savePartial(context,saved,generation) }
                    else MusicDuplicateSessionStore.save(context,saved,generation)
                if (!savedOk && generation==MusicDuplicateSessionStore.journalGeneration()) error = "Не удалось сохранить состояние"
            }
        }
    }
    fun restorePrevious(saved: DuplicateSession, fromPartial: Boolean = false) {
        if (scanning||clearingJournal) return
        journalGeneration = MusicDuplicateSessionStore.journalGeneration()
        scanning = true; error = null
        scope.launch {
            try {
                check(latestRows.isNotEmpty() || saved.result.groups.isEmpty()) { "Библиотека ещё не загружена" }
                val r = MusicDuplicateScanner.restore(context,saved.result,latestRows,linkedIds,latestCurrent)
                check(admissible(r)) { "Поиск сброшен после переноса в Личное" }
                val oldKeys = saved.result.groups.map { it.key }.toSet()
                val recommendations = r.groups.associate { g -> g.key to (saved.kept[g.key]?.takeIf { id -> g.tracks.any { it.id == id } }
                    ?: MusicDuplicatePolicy.recommended(g)) }
                val safe = r.groups.filter { it.key in oldKeys }.flatMap { g -> g.tracks.filter { it.id != recommendations[g.key] && it.id != latestCurrent }.map { it.id } }.toSet()
                selected = saved.selected.intersect(safe); kept = recommendations; scannedAt = saved.scannedAt
                showingPartial = fromPartial; previous = null; result = r
                if (r.groups.isNotEmpty()) listState.requestScrollToItem(saved.index.coerceIn(0,r.groups.lastIndex),saved.offset)
                if (r.skipped > saved.result.skipped) error = "Некоторые файлы изменились. Проверьте результаты или запустите новый поиск."
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Не удалось восстановить поиск" }
            finally { scanning = false }
        }
    }
    // Filter during composition as well as async admission; effects may run before a suspended restore returns.
    val groups = result?.takeIf(::admissible)?.groups.orEmpty()
    val displayPrevious = previous?.takeIf { admissible(it.result) }
    val partial=(if(background.running)background.partial else background.partial?:storedPartial?.result)?.takeIf(::admissible)
    val visibleGroups=groups.filter{filter==null||it.kind==filter}
    val unfinished=DuplicateBackgroundScan.interrupted(context)||partial!=null
    val unfinishedExpanded=DuplicateBackgroundScan.resumeExpanded(context)
    fun resume(){stopPreview();expandedScan=unfinishedExpanded;scanRevision++}
    fun startSelected(){
        if(scanning||verifying||clearingJournal)return
        if(unfinished&&expandedScan!=unfinishedExpanded){replaceScan=true;return}
        stopPreview();scanRevision++
    }
    fun exactBytes(value:List<DuplicateGroup>)=value.filter{it.kind==DuplicateKind.EXACT}.sumOf{g->g.tracks.sumOf{it.bytes}-(g.tracks.firstOrNull()?.bytes?:0L)}
    val liveRows=remember(rows){rows.associateBy{it.id}}
    fun eligible(t:DuplicateTrack):Boolean= t.id!=latestCurrent&&liveRows[t.id]?.let{row->
        row.savedUri==t.uri&&!row.hidden&&!row.isPrivate&&
            row.state==com.domenota.medialoader.core.model.DownloadState.COMPLETED
    }==true&&DuplicatePrivacyGuard.accepts(context,t.privacyEpoch,t.id)
    fun keepVersion(group:DuplicateGroup,id:String){
        val before=kept[group.key]?:MusicDuplicatePolicy.recommended(group)
        selected=MusicDuplicatePolicy.switchKeeper(group,selected,before,id,
            group.tracks.filter(::eligible).map{it.id}.toSet())
        kept=kept+(group.key to id)
    }
    val allSelected = MusicDuplicatePolicy.selectAll(groups,kept).intersect(groups.flatMap{it.tracks}.filter(::eligible).map{it.id}.toSet())
    LaunchedEffect(currentId){currentId?.let{selected=selected-it}}
    val size = groups.sumOf { g -> g.tracks.filter { it.id in selected }.sumOf { it.bytes } }
    fun close() {
        stopPreview()
        val saved = snapshot()
        if (saved == null) onDismiss() else scope.launch {
            if (showingPartial) withContext(Dispatchers.IO) { MusicDuplicateSessionStore.savePartial(context,saved,journalGeneration) } else MusicDuplicateSessionStore.save(context,saved,journalGeneration)
            onDismiss()
        }
    }
    BackHandler(enabled = true) { if (!verifying&&!clearingJournal) close() }
    // This is a NavHost destination within the Activity's bounded Scaffold.
    // No independent Dialog window, no duplicate system-bar fitting.
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().testTag("duplicates-route")) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ close() }, enabled = !verifying&&!clearingJournal) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") }
                    Text("Дубликаты треков", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge,maxLines=1,overflow=TextOverflow.Ellipsis)
                    if(trashReport!=null)IconButton({showTrashReport=true},enabled=!verifying){
                        Icon(Icons.Rounded.DeleteOutline,"Последнее перемещение")
                    }
                    IconButton({journal=true},enabled=!verifying&&!clearingJournal){Icon(Icons.Rounded.History,"Журнал поиска")}
                    // Starting a new scan belongs to the explicit mode controls below.
                }
                if (scanning) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    if(background.running)DuplicateScanPanel(background.phase,background.done,background.total,background.startedAt,background.phaseStartedAt,true,partial?.groups?.size?:0,exactBytes(partial?.groups.orEmpty()),showSummary=partial==null)
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (background.running) {
                        Button({ close() }, Modifier.fillMaxWidth()) { Text("Продолжить в фоне") }
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                            OutlinedButton({DuplicateBackgroundScan.pause(context)},enabled=!background.committing,modifier=Modifier.weight(1f)){Text("Пауза")}
                            TextButton({DuplicateBackgroundScan.cancel(context)},enabled=!background.committing){Text("Остановить")}
                        }

                    } else TextButton({ close() }) { Text("Закрыть") }
                    }
                    if(background.running&&partial!=null)PreliminaryMusicGroups(partial.groups,exactBytes(partial.groups),previewRows,
                        Modifier.weight(1f).padding(horizontal=16.dp),preliminaryListState)
                } else if (displayPrevious != null) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    val saved = displayPrevious
                    Text("Последний поиск", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 12.dp))
                    Text(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(saved.scannedAt)))
                    Text("Групп: ${saved.result.groups.size} · файлов: ${saved.result.groups.sumOf { it.tracks.size }}")
                    val savedBytes = saved.result.groups.sumOf { g -> g.tracks.filter { it.id in saved.selected }.sumOf { it.bytes } }
                    Text("Выбрано: ${saved.selected.size} · ${android.text.format.Formatter.formatFileSize(context,savedBytes)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button({ restorePrevious(saved) }, Modifier.fillMaxWidth(), enabled=!clearingJournal) { Text("Продолжить проверку") }
                    MusicScanModeControls(expandedScan,{expandedScan=it},unfinished,unfinishedExpanded,::startSelected,!verifying&&!clearingJournal)
                    MusicScanStatusCard(DuplicateBackgroundScan.outcome(context),partial?.groups?.size,error?:background.error)
                    }
                } else {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Text("Групп: ${groups.size} · файлов: ${groups.sumOf { it.tracks.size }}", style = MaterialTheme.typography.bodyMedium)
                    MusicScanModeControls(expandedScan,{expandedScan=it},unfinished,unfinishedExpanded,::startSelected,!verifying&&!clearingJournal)
                    if(result==null&&partial!=null) {
                        Text("Сохранено групп: ${partial.groups.size}. Поиск можно продолжить отдельно.")
                        if (!background.running && storedPartial != null) Button(
                            { restorePrevious(requireNotNull(storedPartial), fromPartial = true) },
                            Modifier.fillMaxWidth().testTag("duplicate-open-partial"),
                            enabled = !clearingJournal
                        ) { Text("Открыть сохранённые результаты") }
                    }
                    if (showingPartial) Text(
                        "Предварительный отчёт · ${groups.size} групп. Акустическая проверка не завершена; перед корзиной проверяется каждый файл и сохраняемый экземпляр.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    MusicScanStatusCard(DuplicateBackgroundScan.outcome(context),partial?.groups?.size,error?:background.error)
                    Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){
                        FilterChip(filter==null,{filter=null},{Text("Все")})
                        DuplicateKind.entries.forEach{k->FilterChip(filter==k,{filter=k},{Text(when(k){DuplicateKind.EXACT->"Точные";DuplicateKind.ACOUSTIC->"Акустика";DuplicateKind.POSSIBLE->"Версии"})})}
                    }
                    result?.let{r->Text(if(r.expanded)"Режим: расширенный" else "Режим: быстрый",style=MaterialTheme.typography.labelMedium)
                        if(r.acousticUnavailable>0)Text("Без акустического отпечатка: ${r.acousticUnavailable}. Точные совпадения сохранены.",style=MaterialTheme.typography.bodySmall)}
                    if ((result?.skipped ?: 0) > 0) Text("Недоступно файлов: ${result!!.skipped}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    previewError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (result!=null && groups.isEmpty() && error == null && !unfinished) Text("Совпадений не найдено", Modifier.padding(vertical = 24.dp))
                    if (groups.any { it.kind != DuplicateKind.EXACT }) Text("Акустические и возможные совпадения выбирайте вручную после прослушивания.", style = MaterialTheme.typography.bodySmall)
                    if (allSelected.isNotEmpty() || selected.isNotEmpty()) {
                        OutlinedButton({ selected = if (selected == allSelected || allSelected.isEmpty()) emptySet() else allSelected }, Modifier.fillMaxWidth(), enabled = !verifying&&!clearingJournal) { Text(if (selected == allSelected || allSelected.isEmpty()) "Снять выделение" else "Выделить точные") }
                    }
                    }
                    Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
                    LazyColumn(Modifier.fillMaxSize().padding(end = if (groups.size > 1) musicFastScrollLaneWidth else 0.dp), state = listState,
                        verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                        items(visibleGroups, key = { it.key }) { group ->
                            val keep = kept[group.key] ?: MusicDuplicatePolicy.recommended(group)
                            Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(16.dp)) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(group.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    Text("${MusicDuplicatePolicy.shortReason(group)} · ${RussianCounts.files(group.tracks.size)}", style = MaterialTheme.typography.bodySmall, color = musicPlaybackAccent)
                                    TextButton({comparing=group}){Text("Сравнить записи")}
                                    group.tracks.forEach { t ->
                                        HorizontalDivider()
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            IconButton({ play(t) }, enabled = !verifying) { Icon(if (previewId == t.id && previewPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Прослушать ${t.filename}") }
                                            DownloadThumbnail(t.uri,null,MediaKind.AUDIO,Modifier.size(44.dp))
                                            Column(Modifier.weight(1f).padding(start=8.dp)) {
                                                Text(t.title, fontWeight = FontWeight.Medium,maxLines=2,overflow=TextOverflow.Ellipsis)
                                                Text(displayFileName(t.filename,MediaKind.AUDIO),style=MaterialTheme.typography.labelSmall,maxLines=1,overflow=TextOverflow.Ellipsis)
                                                Text(listOfNotNull(t.artist, t.duration?.let { "%d:%02d".format(it/60000,it/1000%60) }, t.bitrate?.let { "${it/1000} кбит/с" }, android.text.format.Formatter.formatFileSize(context,t.bytes)).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                                                t.folder?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                                MusicDuplicatePolicy.version(t)?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                                                if (t.linked) Text("Есть связи библиотеки", style = MaterialTheme.typography.labelSmall, color = musicPlaybackAccent)
                                                if(t.id !in selected)Text("Остаётся",color=musicPlaybackAccent,style=MaterialTheme.typography.labelLarge)
                                                if(t.id!=keep||group.tracks.any{it.id!=t.id&&eligible(it)&&it.id !in selected})
                                                    TextButton({keepVersion(group,t.id)},enabled=!verifying&&
                                                        liveRows[t.id]?.let{it.savedUri==t.uri&&!it.hidden&&!it.isPrivate}==true,
                                                        modifier=Modifier.heightIn(min=48.dp)){
                                                        Text("Оставить эту версию",style=MaterialTheme.typography.labelMedium)
                                                    }
                                                if(t.id==currentId)Text("Текущий трек",style=MaterialTheme.typography.labelSmall)
                                                else if(!eligible(t))Text("Недоступен для корзины",style=MaterialTheme.typography.labelSmall)
                                            }
                                            val checked = t.id in selected
                                            val enabled = t.id != keep && eligible(t) && !verifying&&!clearingJournal
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
                                        if(previewId==t.id)DuplicatePreviewSeek(t.id,previewPosition,previewDuration,previewSeekable&&!verifying,
                                            onSeek={seekPreview(t,it)})
                                    }
                                }
                            }
                        }
                    }
                    MusicFastScroll(listState, emptyList(), Modifier.align(Alignment.CenterEnd),
                        tag = "duplicates-fast-scroll", description = "Быстрая прокрутка дубликатов")
                    }
                    if (groups.isNotEmpty()&&!clearingJournal) {
                        DuplicateResultsFooter(selected.size, android.text.format.Formatter.formatFileSize(context,size), verifying) { confirm = true }
                    }
                }
            }
        }
    }
    if(showTrashReport)trashReport?.takeIf{it.epoch==privacyRevision&&
        it.rows.all{row->DuplicatePrivacyGuard.accepts(context,it.epoch,row.id)}}?.let{report->
        AlertDialog(onDismissRequest={showTrashReport=false},title={Text("Последнее перемещение")},text={
            Column(Modifier.heightIn(max=360.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
                Text(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(report.at)))
                Text("Перемещено в корзину: ${report.rows.count{it.success}}")
                Text("Не перемещено: ${report.rows.count{!it.success}}")
                report.rows.filter{!it.success||it.reason!="Перемещено"}.forEach{row->
                    HorizontalDivider();Text(row.name,style=MaterialTheme.typography.bodyMedium)
                    Text(row.reason,style=MaterialTheme.typography.bodySmall)
                }
            }
        },confirmButton={TextButton({showTrashReport=false}){Text("Закрыть")}})
    }
    if(journal)DuplicateJournalDialog("Журнал музыки",history.map{DuplicateJournalRow(it.scannedAt,it.result.groups.size,it.result.groups.sumOf{g->g.tracks.size},mode=musicScanModeName(it.result.expanded))},
        storedPartial?.let{DuplicateJournalRow(it.scannedAt,it.result.groups.size,it.result.groups.sumOf{g->g.tracks.size} ,DuplicateBackgroundScan.journalStatus(context),musicScanModeName(it.result.expanded))},
        onOpen={at->journal=false;history.firstOrNull{it.scannedAt==at}?.let{if(!scanning&&!clearingJournal){stopPreview();restorePrevious(it)}}},
        onResume={journal=false;if(!scanning&&!clearingJournal)resume()},onDismiss={journal=false},onPartial={journal=false;storedPartial?.let { if(!scanning&&!clearingJournal) restorePrevious(it,fromPartial=true) }},resumeEnabled=!scanning&&!clearingJournal,
        onClear={clearPartialToo=false;clearJournalConfirm=true},clearEnabled=!scanning&&!verifying&&!background.running&&!background.committing&&!clearingJournal)
    if(replaceScan)AlertDialog(onDismissRequest={replaceScan=false},title={Text("Заменить незавершённый поиск?")},
        text={Text("Сохранённый предварительный отчёт будет удалён. Акустические отпечатки сохранятся. Запустить ${musicScanModeName(expandedScan).lowercase()}?")},
        dismissButton={TextButton({replaceScan=false}){Text("Отмена")}},confirmButton={TextButton({
            replaceScan=false
            if(!scanning&&!verifying&&!clearingJournal){
                if(DuplicateBackgroundScan.discardPartial(context)){storedPartial=null;stopPreview();scanRevision++}
                else error="Не удалось удалить незавершённый поиск"
            }
        }){Text("Запустить")}})
    if(clearJournalConfirm)AlertDialog(onDismissRequest={clearJournalConfirm=false},title={Text("Очистить журнал?")},text={
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text("Будут удалены сохранённые отчёты поиска. Музыкальные файлы останутся на устройстве.")
            if(unfinished)Row(verticalAlignment=Alignment.CenterVertically){Checkbox(clearPartialToo,{clearPartialToo=it});Text("Также удалить незавершённый поиск")}
        }
    },dismissButton={TextButton({clearJournalConfirm=false}){Text("Отмена")}},confirmButton={TextButton({
        clearJournalConfirm=false
        if(!scanning&&!verifying&&!background.running&&!background.committing&&!clearingJournal){
            clearingJournal=true
            val includePartial=clearPartialToo
            stopPreview();comparing=null
            scope.launch{
                try{
                    val ok=withContext(Dispatchers.IO){DuplicateBackgroundScan.clearJournal(context,includePartial)}
                    journalGeneration=MusicDuplicateSessionStore.journalGeneration()
                    // Never allow an already captured UI result to repopulate a cleared journal.
                    result=null;previous=null;selected=emptySet();kept=emptyMap();scannedAt=0L
                    history=MusicDuplicateSessionStore.history(context);storedPartial=MusicDuplicateSessionStore.partial(context)
                    if(includePartial&&ok)expandedScan=false
                    error=if(ok)null else "Не удалось полностью очистить журнал"
                }finally{clearingJournal=false}
            }
        }
    }){Text("Очистить",color=MaterialTheme.colorScheme.error)}})
    comparing?.takeIf{g->g.tracks.all{DuplicatePrivacyGuard.accepts(context,it.privacyEpoch,it.id)}}?.let{g->
        DuplicateMusicComparison(g,kept[g.key]?:MusicDuplicatePolicy.recommended(g),previewId,previewPlaying,player,
            onPlay={t,at->play(t,at)},onKeep={id->
                keepVersion(g,id)
            },onClose={stopPreview();comparing=null})
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
                        for(id in groupChoice){
                            try{
                                check(latestCurrent!=id){"Текущий трек выбран"}
                                MusicDuplicateScanner.verifyMember(context,group,id,choice,latestRows)
                                verified+=id
                            }catch(e:CancellationException){throw e}
                            catch(e:Exception){failures[id]=e.message?:"Проверка файла не прошла"}
                        }
                    }
                    latestCurrent?.takeIf{it in verified}?.let{verified-=it;failures[it]="Текущий трек изменился"}
                    val saved = requireNotNull(snapshot()).copy(selected=choice)
                    check(if (showingPartial) withContext(Dispatchers.IO) { MusicDuplicateSessionStore.savePartial(context,saved,journalGeneration) }
                        else MusicDuplicateSessionStore.save(context,saved,journalGeneration)) { "Не удалось сохранить состояние" }
                    onTrash(DuplicateTrashSelection(groups, choice, verified, failures)); onDismiss()
                } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message ?: "Проверка не прошла" }
                finally { verifying = false }
            }
        }) { Text("В корзину") }
    })
}

@OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun DuplicateMusicComparison(group:DuplicateGroup,keeper:String,previewId:String?,playing:Boolean,player:ExoPlayer,
    onPlay:(DuplicateTrack,Long?)->Unit,onKeep:(String)->Unit,onClose:()->Unit){
    val context=LocalContext.current
    var active by remember(group.key){mutableStateOf(group.tracks.first().id)}
    val track=group.tracks.first{it.id==active}
    var waveform by remember(active){mutableStateOf<FloatArray?>(null)}
    var position by remember{mutableLongStateOf(0)}
    val reference=group.tracks.first()
    var targetId by remember(group.key){mutableStateOf(group.tracks[1].id)}
    val target=group.tracks.first{it.id==targetId}
    var evidence by remember(targetId){mutableStateOf<AcousticAlignment?>(null)}
    fun choose(t:DuplicateTrack){active=t.id;if(t.id!=reference.id)targetId=t.id;onPlay(t,0L)}
    LaunchedEffect(active){
        val job=kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
        waveform=withContext(Dispatchers.IO){
            if(!DuplicatePrivacyGuard.accepts(context,track.privacyEpoch,track.id))FloatArray(0)
            else try{
                val uri=Uri.parse(track.uri)
                val duration=runCatching{
                    val retriever=android.media.MediaMetadataRetriever()
                    try{retriever.setDataSource(context,uri);retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()}
                    finally{retriever.release()}
                }.getOrNull()?.takeIf{it>0}?:track.duration?.takeIf{it>0}
                if(duration==null)FloatArray(0) else {
                    // Share the player/editor's PCM RMS v6 cache and cancellation gate.
                    // The legacy packet-size fallback is not a true audio waveform.
                    val cached=com.domenota.medialoader.ui.components.readCachedAudioWaveform(context,uri,duration)
                    val peaks=cached?:loadAudioWaveform(context,uri,duration,isCancelled={job?.isActive==false})
                    if(peaks.isEmpty()||!DuplicatePrivacyGuard.accepts(context,track.privacyEpoch,track.id))FloatArray(0)
                    else com.domenota.medialoader.ui.components.waveformColumnPeaks(peaks,256)
                }
            }catch(e:CancellationException){throw e}catch(_:Exception){FloatArray(0)}
        }
    }
    LaunchedEffect(targetId){
        evidence=withContext(Dispatchers.Default){val a=reference.acoustic;val b=target.acoustic
            if(a!=null&&b!=null)AcousticFingerprints.alignment(a,b) else null}
    }
    LaunchedEffect(previewId,playing,active){while(true){position=if(previewId==active)player.currentPosition else 0;delay(250)}}
    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false)){
        Surface(Modifier.fillMaxSize()){
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp)){
                Row(verticalAlignment=Alignment.CenterVertically){
                    IconButton(onClose){Icon(Icons.AutoMirrored.Rounded.ArrowBack,"Назад")}
                    Text("Сравнение треков",style=MaterialTheme.typography.titleLarge)
                }
                Text(MusicDuplicatePolicy.shortReason(group),style=MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)){
                    items(group.tracks,key={it.id}){t->
                        Card(onClick={choose(t)}){
                            Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically){
                                DownloadThumbnail(t.uri,null,MediaKind.AUDIO,Modifier.size(52.dp))
                                Column(Modifier.weight(1f).padding(horizontal=8.dp)){
                                    Text(t.title,maxLines=2,overflow=TextOverflow.Ellipsis)
                                    Text(listOfNotNull(t.artist,t.duration?.let{"${it/60000}:${"%02d".format(it/1000%60)}"},t.bitrate?.let{"${it/1000} кбит/с"}).joinToString(" · "),style=MaterialTheme.typography.bodySmall)
                                    if(t.id==keeper)Text("Остаётся",color=MaterialTheme.colorScheme.primary)
                                }
                                RadioButton(t.id==active,{choose(t)})
                            }
                        }
                    }
                }
                Text(track.title,style=MaterialTheme.typography.titleMedium,maxLines=1,overflow=TextOverflow.Ellipsis)
                val peaks=waveform
                if(peaks==null)LinearProgressIndicator(Modifier.fillMaxWidth())
                else if(peaks.any{it>0f})AudioWaveform(peaks,(position.toDouble()/(track.duration?:1L).coerceAtLeast(1)).toFloat().coerceIn(0f,1f),
                    {fraction->onPlay(track,((track.duration?:0L)*fraction).toLong())})
                else Text("Волна недоступна")
                Row(verticalAlignment=Alignment.CenterVertically){
                    IconButton({onPlay(track,null)}){Icon(if(previewId==active&&playing)Icons.Rounded.Pause else Icons.Rounded.PlayArrow,"Прослушать")}
                    Text("${position/60000}:${"%02d".format(position/1000%60)}",Modifier.weight(1f))
                    Button({onKeep(active)}){Text("Оставить эту версию")}
                }
                evidence?.let{match->
                    Text("Подтверждённое смещение фрагмента",style=MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
                        TextButton({active=reference.id;onPlay(reference,match.firstMs)}){Text("Первый фрагмент")}
                        TextButton({active=target.id;onPlay(target,match.secondMs)}){Text("Второй фрагмент")}
                    }
                }
            }
        }
    }
}

@Composable
internal fun DuplicatePreviewSeek(id:String,position:Long,duration:Long,enabled:Boolean,onSeek:(Long)->Unit){
    var scrub by remember(id){mutableStateOf<Float?>(null)}
    val max=duration.coerceAtLeast(1L).toFloat()
    fun time(ms:Long)="%d:%02d".format(ms.coerceAtLeast(0)/60000,ms.coerceAtLeast(0)/1000%60)
    Column(Modifier.fillMaxWidth().testTag("duplicate-preview-seek-$id")){
        com.domenota.medialoader.ui.components.AppSlider(value=(scrub?:position.toFloat()).coerceIn(0f,max),
            onValueChange={scrub=it},modifier=Modifier.fillMaxWidth().testTag("duplicate-seek-slider-$id"),
            enabled=enabled&&duration>0,valueRange=0f..max,
            onValueChangeFinished={scrub?.let{onSeek(it.toLong())};scrub=null},accentColor=musicPlaybackAccent)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
            Text(time(scrub?.toLong()?:position),style=MaterialTheme.typography.labelSmall)
            Text(if(duration>0)time(duration) else "—:—",style=MaterialTheme.typography.labelSmall)
        }
        if(!enabled)Text("Перемотка пока недоступна",style=MaterialTheme.typography.labelSmall)
    }
}


/** Provisional groups are read-only, and never trigger decoding or network artwork fetching. */
@Composable
internal fun PreliminaryMusicGroups(groups:List<DuplicateGroup>,exactBytes:Long,rows:Map<String,DownloadEntity>,
    modifier:Modifier=Modifier,state:androidx.compose.foundation.lazy.LazyListState=rememberLazyListState()){
    val context=LocalContext.current
    LazyColumn(modifier.fillMaxWidth().testTag("music-preliminary-groups"),state=state,
        verticalArrangement=Arrangement.spacedBy(8.dp),contentPadding=PaddingValues(bottom=12.dp)){
        item(key="preliminary-summary"){
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                    Text("Найденные группы",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
                    Text(groups.size.toString(),color=musicPlaybackAccent,style=MaterialTheme.typography.titleMedium)
                }
                Surface(shape=RoundedCornerShape(14.dp),color=MaterialTheme.colorScheme.surfaceVariant){
                    Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
                        Text("Объём точных копий",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(android.text.format.Formatter.formatFileSize(context,exactBytes),style=MaterialTheme.typography.titleLarge,color=musicPlaybackAccent)
                        HorizontalDivider(Modifier.padding(vertical=3.dp))
                        Text("Только просмотр. Удаление доступно после завершения проверки.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        items(groups,key={"preliminary:${it.key}"}){group->PreliminaryMusicGroup(group,rows)}
    }
}

@Composable
private fun PreliminaryMusicGroup(group:DuplicateGroup,rows:Map<String,DownloadEntity>){
    val context=LocalContext.current
    var open by rememberSaveable(group.key){mutableStateOf(false)}
    var allOpen by rememberSaveable(group.key){mutableStateOf(false)}
    val hide=LocalFileNameDisplay.current.hide
    val artist=remember(group.tracks){group.tracks.mapNotNull{it.artist?.trim()?.takeIf{a->a.isNotEmpty()}}.distinct().singleOrNull()}
    val shape=RoundedCornerShape(14.dp)
    val artwork=remember(group.tracks,rows){group.tracks.firstNotNullOfOrNull{track->preliminaryArtworkRow(track,rows)}}
    Surface(Modifier.fillMaxWidth().clip(shape),shape=shape,color=MaterialTheme.colorScheme.surfaceVariant){
        Column{
            Row(Modifier.fillMaxWidth().testTag("music-preliminary-${group.key}").clickable{open=!open}
                .heightIn(min=64.dp).padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
                Box(Modifier.size(42.dp).clip(RoundedCornerShape(10.dp)).background(musicPlaybackAccent.copy(alpha=.13f)),contentAlignment=Alignment.Center){
                    if(artwork!=null)CachedDownloadThumbnail(artwork.savedUri,artwork.recognitionArtworkUrl?:artwork.sourceAudioArtworkUrl?:artwork.previewUrl,MediaKind.AUDIO,Modifier.fillMaxSize())
                    else Icon(Icons.Rounded.MusicNote,null,Modifier.size(24.dp),tint=musicPlaybackAccent)
                }
                Column(Modifier.weight(1f)){
                    Text(group.title,style=MaterialTheme.typography.titleSmall,maxLines=2,overflow=TextOverflow.Ellipsis)
                    artist?.let{Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)}
                    Text(RussianCounts.files(group.tracks.size),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(if(open)Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,if(open)"Свернуть" else "Развернуть")
            }
            if(open){
                HorizontalDivider(Modifier.padding(horizontal=12.dp))
                group.tracks.take(12).forEach{track->
                    androidx.compose.runtime.key(track.id){
                        Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=7.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
                            Icon(Icons.Rounded.AudioFile,null,Modifier.size(22.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
                            Column(Modifier.weight(1f)){
                                Text(FileNameDisplay.name(track.filename,MediaKind.AUDIO,hide),style=MaterialTheme.typography.bodyMedium,maxLines=2,overflow=TextOverflow.Ellipsis)
                                Text(listOfNotNull(track.bytes.takeIf{it>0}?.let{android.text.format.Formatter.formatFileSize(context,it)},
                                    track.duration?.takeIf{it>0L}?.let{ms->"%d:%02d".format(ms/60000,ms/1000%60)}).joinToString(" · "),
                                    style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                if(group.tracks.size>12)TextButton({allOpen=true},Modifier.fillMaxWidth()) { Text("Показать все ${RussianCounts.files(group.tracks.size)}") }
            }
        }
    }
    if(allOpen)AlertDialog(onDismissRequest={allOpen=false},title={Text(group.title)},
        text={LazyColumn(Modifier.heightIn(max=400.dp).testTag("music-preliminary-all-files"),verticalArrangement=Arrangement.spacedBy(8.dp)){
            items(group.tracks,key={it.id}){track->
                Text(FileNameDisplay.name(track.filename,MediaKind.AUDIO,hide),style=MaterialTheme.typography.bodyMedium)
            }
        }},confirmButton={TextButton({allOpen=false}){Text("Закрыть")}})
}

/** Metadata is for display only; never use a stale or non-public row to select cached artwork. */
internal fun preliminaryArtworkRow(track:DuplicateTrack,rows:Map<String,DownloadEntity>):DownloadEntity? =
    rows[track.id]?.takeIf{row->row.id==track.id&&row.savedUri==track.uri&&row.originalName==track.filename&&
        row.mediaType==com.domenota.medialoader.core.model.MediaType.AUDIO&&
        row.state==com.domenota.medialoader.core.model.DownloadState.COMPLETED&&
        !row.isPrivate&&!row.hidden&&(row.sizeBytes==null||row.sizeBytes==track.bytes)}
