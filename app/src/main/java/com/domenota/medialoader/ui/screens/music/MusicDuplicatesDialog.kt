package com.domenota.medialoader.ui.screens.music

import com.domenota.medialoader.ui.model.FileNameDisplay
import com.domenota.medialoader.ui.model.LocalFileNameDisplay
import com.domenota.medialoader.ui.model.displayFileName
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.ensureActive
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
import com.domenota.medialoader.ui.components.DownloadThumbnail
import com.domenota.medialoader.ui.components.DuplicateScanPanel
import com.domenota.medialoader.ui.components.DuplicateJournalDialog
import com.domenota.medialoader.ui.components.DuplicateJournalRow
import com.domenota.medialoader.ui.model.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import android.os.SystemClock
import com.domenota.medialoader.core.logging.AppLog
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

/** A Compose key with reference semantics; acoustic payloads must never participate in UI equality. */
internal class DuplicateReferenceKey(private val target: Any?) {
    override fun equals(other: Any?): Boolean = other is DuplicateReferenceKey && target === other.target
    override fun hashCode(): Int = System.identityHashCode(target)
}

/** Read-only scan, comparison playback, then explicit recoverable deletion. */
@OptIn(androidx.media3.common.util.UnstableApi::class, kotlinx.coroutines.FlowPreview::class)
@Composable
internal fun MusicDuplicatesDialog(rows: List<DownloadEntity>, linkedIds: Set<String>, currentId: String?, mainPlaying: Boolean,
    onDismiss: () -> Unit, onTrash: (DuplicateTrashSelection) -> Unit) {
    val context = LocalContext.current
    val latestRows by rememberUpdatedState(rows)
    val latestCurrent by rememberUpdatedState(currentId)
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<DuplicateScanResult?>(null, referentialEqualityPolicy()) }
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
    var previous by remember { mutableStateOf<DuplicateSession?>(null, referentialEqualityPolicy()) }
    var scannedAt by remember { mutableLongStateOf(0L) }
    val background by DuplicateBackgroundScan.state.collectAsState()
    val privacyRevision by DuplicatePrivacyGuard.changes.collectAsState()
    val progress = background.done to background.total
    var scanning by remember { mutableStateOf(true) }
    var restoringSaved by remember { mutableStateOf(false) }
    var quickSummary by remember { mutableStateOf<DuplicateSavedSummary?>(null) }
    var restoreDone by remember { mutableIntStateOf(0) }
    var restoreTotal by remember { mutableIntStateOf(0) }
    var scanControlsExpanded by rememberSaveable { mutableStateOf(false) }
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
    var history by remember{mutableStateOf(emptyList<DuplicateSession>(),referentialEqualityPolicy())}
    var storedPartial by remember{mutableStateOf<DuplicateSession?>(null,referentialEqualityPolicy())}
    var filter by rememberSaveable{mutableStateOf<DuplicateKind?>(null)}
    var expandedGroupKey by rememberSaveable { mutableStateOf<String?>(null) }
    var initialGroupExpanded by rememberSaveable { mutableStateOf(false) }
    var comparing by remember{mutableStateOf<DuplicateGroup?>(null, referentialEqualityPolicy())}
    // Do not inflate all acoustic reports merely because this route was opened.
    LaunchedEffect(journal,background.running,privacyRevision) {
        if (journal) history=MusicDuplicateSessionStore.history(context)
        storedPartial=MusicDuplicateSessionStore.partial(context)
    }
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
    LaunchedEffect(privacyRevision,DuplicateReferenceKey(result),DuplicateReferenceKey(previous)) {
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
        scanning = true; restoringSaved = false; showingPartial = false; result = null; previous = null; error = null; selected = emptySet(); kept = emptyMap()
        initialGroupExpanded=false; expandedGroupKey=null
        if(scanRevision != 0) quickSummary = null
        try {
            if(scanRevision == 0) quickSummary = withContext(Dispatchers.IO) { MusicDuplicateShardStore.summary(context) }
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
    LaunchedEffect(DuplicateReferenceKey(result),journalGeneration,showingPartial) {
        val report=result ?: return@LaunchedEffect
        val generation=journalGeneration
        val partialReport=showingPartial
        // A scroll update writes only tiny mutable UI state, not the 3k-track scan.
        snapshotFlow { Triple(selected,kept,
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset) }
            .debounce(950L).collect { (picked,keepers,position) ->
                if(!verifying) {
                    val saved=DuplicateSession(report,picked,keepers,position.first,position.second,scannedAt)
                    val savedOk=MusicDuplicateSessionStore.saveInteraction(context,saved,partialReport,generation)
                    if(!savedOk&&generation==MusicDuplicateSessionStore.journalGeneration())
                        error="Не удалось сохранить выбор"
                }
            }
    }
    fun restorePrevious(saved: DuplicateSession, fromPartial: Boolean = false) {
        if (scanning||clearingJournal) return
        journalGeneration = MusicDuplicateSessionStore.journalGeneration()
        quickSummary = DuplicateSavedSummary(saved.scannedAt,saved.result.groups.size,
            saved.result.groups.sumOf{it.tracks.size},saved.selected.size,
            saved.result.groups.sumOf{g->g.tracks.filter{it.id in saved.selected}.sumOf{it.bytes.coerceAtLeast(0L)}},
            saved.result.privacyEpoch)
        scanning = true; restoringSaved = true
        restoreDone = 0; restoreTotal = saved.result.groups.sumOf{it.tracks.size}
        error = null
        scope.launch {
            try {
                check(latestRows.isNotEmpty() || saved.result.groups.isEmpty()) { "Библиотека ещё не загружена" }
                val r = MusicDuplicateScanner.restore(context,saved.result,latestRows,linkedIds,latestCurrent) { done,total ->
                    restoreDone=done;restoreTotal=total
                }
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
            finally { scanning = false; restoringSaved = false }
        }
    }
    // Filter during composition as well as async admission; effects may run before a suspended restore returns.
    val groups=remember(DuplicateReferenceKey(result),privacyRevision){result?.takeIf(::admissible)?.groups.orEmpty()}
    LaunchedEffect(DuplicateReferenceKey(result),privacyRevision){
        if(!initialGroupExpanded&&groups.isNotEmpty()){
            expandedGroupKey=groups.first().key
            initialGroupExpanded=true
        }
    }
    val displayPrevious=remember(DuplicateReferenceKey(previous),privacyRevision){previous?.takeIf{admissible(it.result)}}
    val partial=remember(background.running,background.partial,storedPartial,privacyRevision){
        (if(background.running)background.partial else background.partial?:storedPartial?.result)?.takeIf(::admissible)
    }
    val visibleGroups=remember(DuplicateReferenceKey(result),privacyRevision,filter){groups.filter{filter==null||it.kind==filter}}
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
            MusicDuplicateSessionStore.saveInteraction(context,saved,showingPartial,journalGeneration)
            onDismiss()
        }
    }
    BackHandler(enabled = true) { if (!verifying&&!clearingJournal) close() }
    // This is a NavHost destination within the Activity's bounded Scaffold.
    // No independent Dialog window, no duplicate system-bar fitting.
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().testTag("duplicates-route")) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().heightIn(min = 58.dp).padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ close() }, enabled = !verifying&&!clearingJournal) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") }
                    Text("Дубликаты треков", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge,maxLines=1,overflow=TextOverflow.Ellipsis)
                    if(trashReport!=null)IconButton({showTrashReport=true},enabled=!verifying){
                        Icon(Icons.Rounded.DeleteOutline,"Последнее перемещение")
                    }
                    OutlinedIconButton({journal=true},Modifier.size(44.dp),enabled=!verifying&&!clearingJournal){Icon(Icons.Rounded.History,"Журнал поиска")}
                    // Starting a new scan belongs to the explicit mode controls below.
                }
                if (scanning) {
                    if (background.running) {
                        // Measure the entire progress header. Never clip it to 220dp or
                        // scroll it independently of the preliminary results.
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                            DuplicateScanPanel(background.phase,background.done,background.total,
                                background.startedAt,background.phaseStartedAt,true,partial?.groups?.size?:0,
                                exactBytes(partial?.groups.orEmpty()),showSummary=partial==null,
                                completedMetadata=background.completedMetadata)
                            Button({ close() }, Modifier.fillMaxWidth()) { Text("Продолжить в фоне") }
                            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                                OutlinedButton({DuplicateBackgroundScan.pause(context)},enabled=!background.committing,modifier=Modifier.weight(1f)){Text("Пауза")}
                                TextButton({DuplicateBackgroundScan.cancel(context)},enabled=!background.committing){Text("Остановить")}
                            }
                        }
                        // Only the list of preliminary groups scrolls below the full header.
                        if(partial!=null)PreliminaryMusicGroups(partial.groups,exactBytes(partial.groups),previewRows,
                            Modifier.weight(1f).padding(horizontal=16.dp),preliminaryListState)
                    } else {
                        // Restoration retains its normal page scroll, independent of scan UI.
                        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                            quickSummary?.let{ DuplicateSavedSummaryPanel(it,null) }
                            if(restoringSaved || scanRevision==0) {
                                Spacer(Modifier.height(12.dp))
                                DuplicateRestorePanel(restoreDone,restoreTotal,restoringSaved,::close)
                                Spacer(Modifier.height(12.dp))
                                DuplicateGroupSkeletons()
                            } else LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    }
                } else if (displayPrevious != null) {
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                    val saved = displayPrevious
                    val savedBytes = remember(DuplicateReferenceKey(saved)) {
                        saved.result.groups.sumOf { g -> g.tracks.filter { it.id in saved.selected }.sumOf { it.bytes.coerceAtLeast(0L) } }
                    }
                    DuplicateSavedSummaryPanel(DuplicateSavedSummary(saved.scannedAt,saved.result.groups.size,
                        saved.result.groups.sumOf { it.tracks.size },saved.selected.size,savedBytes,
                        saved.result.privacyEpoch),if(clearingJournal)null else {{restorePrevious(saved)}})
                    TextButton({scanControlsExpanded=!scanControlsExpanded},
                        modifier=Modifier.testTag("duplicate-toggle-search-controls")) {
                        Text("Управление поиском")
                        Icon(if(scanControlsExpanded)Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,null)
                    }
                    if(scanControlsExpanded) {
                        MusicScanModeControls(expandedScan,{expandedScan=it},unfinished,unfinishedExpanded,
                            ::startSelected,!verifying&&!clearingJournal)
                        MusicScanStatusCard(DuplicateBackgroundScan.outcome(context),partial?.groups?.size,error?:background.error)
                    }
                    }
                } else {
                    BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                    Column(Modifier.fillMaxSize()) {
                    // Do not cap the search controls at 48% or create a nested scroller:
                    // show the whole search header; only the result list scrolls.
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    val fileCount = remember(DuplicateReferenceKey(result),privacyRevision) { groups.sumOf{it.tracks.size} }
                    Text(if(showingPartial)
                        "Предварительный · ${groups.size} групп · $fileCount файлов"
                        else "Групп: ${groups.size} · файлов: $fileCount",
                        style = MaterialTheme.typography.bodyMedium)
                    if(result!=null) TextButton({scanControlsExpanded=!scanControlsExpanded},
                        modifier=Modifier.testTag("duplicate-toggle-search-controls")) {
                        Text(if(scanControlsExpanded)"Скрыть управление поиском" else "Управление поиском")
                        Icon(if(scanControlsExpanded)Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,null)
                    }
                    if(result==null||scanControlsExpanded)
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
                        "Акустическая проверка не завершена. Перед корзиной проверяются выбранные файлы.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if(result==null||scanControlsExpanded) MusicScanStatusCard(DuplicateBackgroundScan.outcome(context),partial?.groups?.size,error?:background.error)
                    val counts=remember(DuplicateReferenceKey(result),privacyRevision){
                        groups.groupingBy{it.kind}.eachCount()
                    }
                    // Two complete rows, no clipped fourth filter or horizontal scrolling.
                    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(2.dp)){
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                            FilterChip(filter==null,{filter=null},{Text("Все")},
                                modifier=Modifier.weight(1f))
                            FilterChip(filter==DuplicateKind.EXACT,{filter=DuplicateKind.EXACT},
                                {Text("Точные · "+(counts[DuplicateKind.EXACT]?:0))},
                                modifier=Modifier.weight(1f))
                        }
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                            FilterChip(filter==DuplicateKind.ACOUSTIC,{filter=DuplicateKind.ACOUSTIC},
                                {Text("Акустика · "+(counts[DuplicateKind.ACOUSTIC]?:0))},
                                modifier=Modifier.weight(1f))
                            FilterChip(filter==DuplicateKind.POSSIBLE,{filter=DuplicateKind.POSSIBLE},
                                {Text("Версии · "+(counts[DuplicateKind.POSSIBLE]?:0))},
                                modifier=Modifier.weight(1f))
                        }
                    }
                    result?.let{r->Text(if(r.expanded)"Режим: расширенный" else "Режим: быстрый",style=MaterialTheme.typography.labelMedium)
                        if(r.acousticUnavailable>0)Text("Без акустического отпечатка: ${r.acousticUnavailable}. Точные совпадения сохранены.",style=MaterialTheme.typography.bodySmall)}
                    if ((result?.skipped ?: 0) > 0) Text("Недоступно файлов: ${result!!.skipped}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    previewError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (result!=null && groups.isEmpty() && error == null && !unfinished) Text("Совпадений не найдено", Modifier.padding(vertical = 24.dp))
                    if (groups.any { it.kind != DuplicateKind.EXACT } && filter!=DuplicateKind.EXACT) Text("Разные версии проверяйте прослушиванием.", style = MaterialTheme.typography.labelSmall)
                    if(groups.isNotEmpty()){
                        val clearBulk=selected.isNotEmpty()&&(selected==allSelected||allSelected.isEmpty())
                        OutlinedButton({selected=if(clearBulk)emptySet() else allSelected},
                            Modifier.fillMaxWidth(),enabled=(allSelected.isNotEmpty()||selected.isNotEmpty())&&!verifying&&!clearingJournal){
                            Text(if(clearBulk)"Снять выделение" else "Выбрать всё")
                        }
                        Text("Автоматически отмечаются только лишние точные копии. Похожие записи проверяйте вручную.",
                            style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    }
                    Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
                    LazyColumn(Modifier.fillMaxSize().padding(end = if (groups.size > 1) musicFastScrollLaneWidth else 0.dp), state = listState,
                        verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                        items(visibleGroups, key = { it.key }) { group ->
                            val keep = kept[group.key] ?: MusicDuplicatePolicy.recommended(group)
                            Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(16.dp)) {
                                Column(Modifier.padding(horizontal=10.dp,vertical=6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    val expanded=expandedGroupKey==group.key
                                    Row(Modifier.fillMaxWidth().heightIn(min=56.dp)
                                        .testTag("duplicate-group-${group.key}").clickable {
                                            expandedGroupKey=if(expanded)null else group.key
                                        },verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
                                        Icon(Icons.Rounded.MusicNote,null,Modifier.size(24.dp),tint=musicPlaybackAccent)
                                        Column(Modifier.weight(1f)){
                                            Text(group.title,style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis)
                                            Text("${MusicDuplicatePolicy.shortReason(group)} · ${RussianCounts.files(group.tracks.size)}",
                                                style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                            Text("Выбрано: ${group.tracks.count{it.id in selected}}",style=MaterialTheme.typography.labelSmall,color=musicPlaybackAccent)
                                        }
                                        Icon(if(expanded)Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,if(expanded)"Свернуть" else "Развернуть")
                                    }
                                    TextButton({comparing=group},enabled=!verifying&&!clearingJournal){Text("Сравнить записи")}
                                    if(expanded) group.tracks.forEach { t ->
                                        HorizontalDivider()
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            IconButton({ play(t) }, enabled = !verifying) { Icon(if (previewId == t.id && previewPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Прослушать ${t.filename}") }
                                            DownloadThumbnail(t.uri,null,MediaKind.AUDIO,Modifier.size(36.dp))
                                            Column(Modifier.weight(1f).padding(start=8.dp)) {
                                                Text(t.title, fontWeight = FontWeight.Medium,maxLines=2,overflow=TextOverflow.Ellipsis)
                                                Text(displayFileName(t.filename,MediaKind.AUDIO),style=MaterialTheme.typography.labelSmall,maxLines=1,overflow=TextOverflow.Ellipsis)
                                                Text(listOfNotNull(t.artist, t.duration?.let { "%d:%02d".format(it/60000,it/1000%60) }, t.bitrate?.let { "${it/1000} кбит/с" }, android.text.format.Formatter.formatFileSize(context,t.bytes)).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                                                t.folder?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                                MusicDuplicatePolicy.version(t)?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                                                if (t.linked) Text("Есть связи библиотеки", style = MaterialTheme.typography.labelSmall, color = musicPlaybackAccent)
                                                if(t.id==keep)Text("Остаётся",color=musicPlaybackAccent,style=MaterialTheme.typography.labelLarge)
                                                if(t.id!=keep||group.tracks.any{it.id!=t.id&&eligible(it)&&it.id !in selected})
                                                    TextButton({keepVersion(group,t.id)},enabled=!verifying&&!clearingJournal&&
                                                        liveRows[t.id]?.let{it.savedUri==t.uri&&!it.hidden&&!it.isPrivate}==true){
                                                        Text(if(t.id==keep)"Отметить остальные" else "Оставить этот",style=MaterialTheme.typography.labelMedium)
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
                    }
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
                    check(MusicDuplicateSessionStore.saveInteraction(context,saved,showingPartial,journalGeneration)) { "Не удалось сохранить состояние" }
                    onTrash(DuplicateTrashSelection(groups, choice, verified, failures)); onDismiss()
                } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message ?: "Проверка не прошла" }
                finally { verifying = false }
            }
        }) { Text("В корзину") }
    })
}

private data class PairAnalysis(
    val first:List<ComparisonFrame>, val second:List<ComparisonFrame>,
    val aligned:AlignedAudioComparison?,
)
private data class PairMediaKey(val id:String,val uri:String,val bytes:Long,val modified:Long?,
    val sha:String,val epoch:Long)
private fun DuplicateTrack.pairKey()=PairMediaKey(id,uri,bytes,modified,sha,privacyEpoch)
private fun pairTime(ms:Long):String="%d:%02d.%01d".format(ms/60000,ms/1000%60,ms%1000/100)

@OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun DuplicateMusicComparison(group:DuplicateGroup,keeper:String,previewId:String?,playing:Boolean,player:ExoPlayer,
    onPlay:(DuplicateTrack,Long?)->Unit,onKeep:(String)->Unit,onClose:()->Unit){
    val context=LocalContext.current
    var referenceId by remember(group.key){mutableStateOf(group.tracks.firstOrNull{it.id==keeper}?.id?:group.tracks.first().id)}
    var targetId by remember(group.key){mutableStateOf(group.tracks.first{it.id!=referenceId}.id)}
    var active by remember(group.key){mutableStateOf(referenceId)}
    var pickingReference by remember(group.key){mutableStateOf<Boolean?>(null)}
    val reference=group.tracks.first{it.id==referenceId}
    val target=group.tracks.first{it.id==targetId}
    val track=group.tracks.first{it.id==active}
    // One grid per group: switching target must not invalidate the reference features.
    val step=AudioPairAlignment.stepMs(group.tracks.maxOfOrNull { it.duration ?: 0L },null)
    val identity=Triple(reference.pairKey(),target.pairKey(),step)
    // Keep all reference/target pairs in a ten-track group when memory permits.
    // Only bounded features and alignments, never decoded PCM or unauthorized media.
    val cache=remember(group.key){LinkedHashMap<Triple<PairMediaKey,PairMediaKey,Long>,PairAnalysis>(12,.75f,true)}
    var analysis by remember(identity){mutableStateOf<PairAnalysis?>(null, referentialEqualityPolicy())}
    var failed by remember(identity){mutableStateOf(false)}
    var zoneIndex by remember(identity){mutableIntStateOf(-1)}
    var focusZoom by remember(identity){mutableIntStateOf(1)}
    var technicalDetails by remember(identity){mutableStateOf(false)}
    var position by remember{mutableLongStateOf(0L)}
    val latestOnPlay by rememberUpdatedState(onPlay)
    fun allowed(t:DuplicateTrack)=DuplicatePrivacyGuard.accepts(context,t.privacyEpoch,t.id)
    fun choose(t:DuplicateTrack){
        active=t.id
        if(t.id!=referenceId)targetId=t.id
    }
    fun makeReference(t:DuplicateTrack){
        val pair=comparisonPairWithReference(group.tracks.map{it.id},referenceId,targetId,t.id)
            ?:return
        referenceId=pair.first
        targetId=pair.second
        active=t.id
    }
    LaunchedEffect(identity){
        try {
            val started=SystemClock.elapsedRealtime()
            if(!allowed(reference)||!allowed(target)){failed=true;return@LaunchedEffect}
            val cached=cache[identity]
            val verified=withContext(Dispatchers.IO){
                val beforeFirst=MusicDuplicateScanner.comparisonStamp(context,reference)?:return@withContext null
                val beforeSecond=MusicDuplicateScanner.comparisonStamp(context,target)?:return@withContext null
                val cacheSafe=reference.modified!=null&&target.modified!=null&&
                    beforeFirst.second!=null&&beforeSecond.second!=null
                val value=cached?.takeIf{cacheSafe}?:run{
                    val (first,second)=coroutineScope {
                        val left=async { MusicComparisonFrames.load(context,reference,beforeFirst,step) }
                        val right=async { MusicComparisonFrames.load(context,target,beforeSecond,step) }
                        left.await() to right.await()
                    }
                    if(first==null||second==null)return@withContext null
                    val aligned=withContext(Dispatchers.Default){
                        val job=kotlin.coroutines.coroutineContext
                        val started=SystemClock.elapsedRealtime()
                        AudioPairAlignment.align(first,second,step){job.ensureActive()}.also {
                            AppLog.i("MusicCompare","alignment first=${first.size} second=${second.size} stepMs=$step elapsedMs=${SystemClock.elapsedRealtime()-started} aligned=${it!=null} cells=${it?.evaluatedCells ?: -1}")
                        }
                    }
                    PairAnalysis(first,second,aligned)
                }
                if(MusicDuplicateScanner.comparisonStamp(context,reference)!=beforeFirst||
                    MusicDuplicateScanner.comparisonStamp(context,target)!=beforeSecond)return@withContext null
                value to cacheSafe
            }
            if(verified==null||!allowed(reference)||!allowed(target)){failed=true;return@LaunchedEffect}
            val (value,cacheSafe)=verified
            if(cacheSafe){
                cache[identity]=value
                while(cache.size>12)cache.remove(cache.keys.first())
            }else cache.remove(identity)
            analysis=value
            AppLog.i("MusicCompare","pair ready elapsedMs=${SystemClock.elapsedRealtime()-started} pairCache=${cached!=null&&cacheSafe}")
        }catch(e:CancellationException){throw e}
        catch(_:Exception){failed=true}
    }
    LaunchedEffect(previewId,playing,identity){
        while(true){
            position=if(previewId==referenceId||previewId==targetId)player.currentPosition else 0L
            delay(200)
        }
    }
    val result=analysis?.aligned
    val zones=result?.zones.orEmpty()
    val shownZoneIndex=if(zones.size==1&&zoneIndex<0)0 else zoneIndex
    val zone=zones.getOrNull(shownZoneIndex)
    fun seekFrame(first:Boolean):Long? {
        val selected=zone?:return null
        val aligned=result?:return null
        val col=aligned.columns.subList(selected.start,selected.endExclusive)
        val frame=col.firstNotNullOfOrNull{if(first)it.first else it.second}?:return null
        return frame*aligned.stepMs
    }
    fun play(t:DuplicateTrack,at:Long?=null){
        if(allowed(t))latestOnPlay(t,at)
    }
    fun tapWave(zoneAt:Int,column:Int,first:Boolean){
        val aligned=result?:return
        if(zoneAt>=0)zoneIndex=zoneAt
        val frame=comparisonSeekFrame(aligned.columns,column,first)?:return
        val selectedTrack=if(first)reference else target
        active=selectedTrack.id
        play(selectedTrack,frame*aligned.stepMs)
    }
    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)){
        Surface(Modifier.fillMaxSize()){
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal=16.dp,vertical=6.dp)){
                Row(verticalAlignment=Alignment.CenterVertically){
                    IconButton(onClose){Icon(Icons.AutoMirrored.Rounded.ArrowBack,"Назад")}
                    Text("Сравнение треков",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge,
                        maxLines=1,overflow=TextOverflow.Ellipsis)
                }
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                    .testTag("comparison-scroll-body"),verticalArrangement=Arrangement.spacedBy(10.dp)){
                    Text(MusicDuplicatePolicy.shortReason(group),style=MaterialTheme.typography.labelMedium,
                        color=MaterialTheme.colorScheme.onSurfaceVariant)
                    ComparisonTrackSelector("Основа",reference,referenceId==active,referenceId==keeper,
                        Modifier.testTag("comparison-pick-reference")){pickingReference=true}
                    ComparisonTrackSelector("Сравниваемая",target,targetId==active,targetId==keeper,
                        Modifier.testTag("comparison-pick-target")){pickingReference=false}
                    if(group.tracks.size>2){
                        Text("Быстро сравнить с:",style=MaterialTheme.typography.labelMedium,
                            color=MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                            .testTag("comparison-target-shortcuts"),
                            horizontalArrangement=Arrangement.spacedBy(8.dp)){
                            val targetIds=comparisonTargetIds(group.tracks.map{it.id},referenceId).toSet()
                            group.tracks.filter{it.id in targetIds}.forEach{candidate->
                                OutlinedButton(onClick={if(allowed(candidate))choose(candidate)},
                                    enabled=allowed(candidate),
                                    border=androidx.compose.foundation.BorderStroke(
                                        if(candidate.id==targetId)1.5.dp else 1.dp,
                                        if(candidate.id==targetId)musicPlaybackAccent else MaterialTheme.colorScheme.outline)){
                                    Text(candidate.title,Modifier.widthIn(max=160.dp),
                                        maxLines=1,overflow=TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                    HorizontalDivider()
                            Text("Различия между записями",style=MaterialTheme.typography.titleMedium)
                            if(failed)Text("Анализ недоступен",style=MaterialTheme.typography.bodySmall,
                                color=MaterialTheme.colorScheme.onSurfaceVariant)
                            else if(analysis==null){
                                Text("Синхронизация записей…",style=MaterialTheme.typography.labelMedium)
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                            }else if(result==null){
                                Text("Синхронизация не подтверждена",style=MaterialTheme.typography.labelMedium)
                                // A fallback may show independent waveform envelopes,
                                // never invented matches or difference regions.
                                val pair=analysis
                                if(pair!=null){
                                    listOf(reference to pair.first,target to pair.second).forEach{(t,frames)->
                                        Text(t.title,style=MaterialTheme.typography.bodySmall,maxLines=1,overflow=TextOverflow.Ellipsis)
                                        val max=frames.maxOfOrNull{it.rms}?:0f
                                        if(max>0f)AudioWaveform(FloatArray(frames.size){frames[it].rms/max},
                                            if(previewId==t.id)comparisonWaveformProgress(position,t.duration)else 0f,
                                            {fraction->t.duration?.takeIf{it>0}?.let{play(t,(it*fraction).toLong())}})
                                    }
                                }
                            }else{
                                val firstMatch=result.columns.firstOrNull{!it.different&&it.first!=null&&it.second!=null}
                                val offset=firstMatch?.let{(requireNotNull(it.second)-requireNotNull(it.first))*result.stepMs}
                                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                                    Text("Синхронизировано",style=MaterialTheme.typography.labelMedium)
                                    if(offset!=null)Text("Смещение: ${if(offset>=0)"+" else "−"}${kotlin.math.abs(offset)/1000.0} с",
                                        style=MaterialTheme.typography.labelSmall)
                                }
                                Surface(shape=RoundedCornerShape(14.dp),color=MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.55f)){
                                    Column(Modifier.fillMaxWidth().padding(10.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){
                                        Text("Обзор записи",style=MaterialTheme.typography.labelMedium)
                                        ComparisonTwoWaveforms(result,shownZoneIndex,previewId==referenceId,
                                            previewId==targetId,position,
                                            range=0 until result.columns.size,referenceName=reference.title,
                                            targetName=target.title){zoneAt,column,first->tapWave(zoneAt,column,first)}
                                    }
                                }
                                Row(verticalAlignment=Alignment.CenterVertically,
                                    horizontalArrangement=Arrangement.spacedBy(8.dp)){
                                    Box(Modifier.size(10.dp).background(musicPlaybackAccent,RoundedCornerShape(2.dp)))
                                    Text("Жёлтым выделены места расхождения двух записей.",
                                        style=MaterialTheme.typography.labelSmall,
                                        color=MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                val evaluated=remember(DuplicateReferenceKey(result)){
                                    zones.groupingBy{result.zoneKind(it)}.eachCount()
                                }
                                val quietEdges=(evaluated[ComparisonZoneKind.QUIET_EDGE_UNCERTAIN]?:0)+
                                    (evaluated[ComparisonZoneKind.SILENT_PADDING]?:0)
                                Text(if(zones.isEmpty())"Различий не обнаружено" else
                                    "Найдено участков: ${zones.size}",style=MaterialTheme.typography.labelMedium)
                                if(quietEdges>0)Text("Тихие края: $quietEdges. Прослушайте перед решением.",
                                    style=MaterialTheme.typography.labelSmall,
                                    color=MaterialTheme.colorScheme.onSurfaceVariant)
                                if(zones.isEmpty())Text("Сходство не гарантирует идентичность файлов.",
                                    style=MaterialTheme.typography.labelSmall,
                                    color=MaterialTheme.colorScheme.onSurfaceVariant)
                                if(zones.isNotEmpty()){
                                    Surface(shape=RoundedCornerShape(12.dp),
                                        color=MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.42f)){
                                        Row(Modifier.fillMaxWidth().padding(horizontal=8.dp,vertical=4.dp),
                                            verticalAlignment=Alignment.CenterVertically){
                                            if(zones.size>1){
                                                IconButton(onClick={zoneIndex=if(shownZoneIndex<=0)zones.lastIndex else shownZoneIndex-1}){
                                                    Icon(Icons.Rounded.SkipPrevious,"Предыдущий участок")
                                                }
                                            }
                                            Text("Участок ${shownZoneIndex.coerceAtLeast(0)+1} из ${zones.size}",
                                                Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
                                            if(zones.size>1){
                                                IconButton(onClick={zoneIndex=if(shownZoneIndex>=zones.lastIndex)0 else shownZoneIndex+1}){
                                                    Icon(Icons.Rounded.SkipNext,"Следующий участок")
                                                }
                                            }
                                        }
                                    }
                                    if(zone!=null){
                                        val kind=result.zoneKind(zone)
                                        val range=comparisonFocusRange(result.columns.size,zone.start,zone.endExclusive,focusZoom)
                                        Surface(shape=RoundedCornerShape(14.dp),color=MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.55f)){
                                            Column(Modifier.fillMaxWidth().padding(10.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
                                                Text("Отличие ${shownZoneIndex+1}",color=musicPlaybackAccent,
                                                    style=MaterialTheme.typography.titleSmall)
                                                Text("Послушайте этот момент в обеих записях.",
                                                    style=MaterialTheme.typography.bodySmall)
                                                Text("Основа: ${seekFrame(true)?.let(::pairTime)?:"нет фрагмента"} · " +
                                                    "Версия: ${seekFrame(false)?.let(::pairTime)?:"нет фрагмента"}",
                                                    style=MaterialTheme.typography.bodySmall)
                                                TextButton(onClick={technicalDetails=!technicalDetails}){
                                                    Text(if(technicalDetails)"Скрыть подробности" else "Подробнее об участке")
                                                }
                                                if(technicalDetails){
                                                    Text(comparisonZoneLabel(kind),
                                                        style=MaterialTheme.typography.bodySmall,
                                                        color=MaterialTheme.colorScheme.onSurfaceVariant)
                                                    if(kind==ComparisonZoneKind.QUIET_EDGE_UNCERTAIN ||
                                                        kind==ComparisonZoneKind.SILENT_PADDING){
                                                        Text("На границе почти тишина: возможны хвост кодека, шум " +
                                                            "или настоящий тихий звук. Отличие не подтверждает порчу записи.",
                                                            style=MaterialTheme.typography.bodySmall,
                                                            color=MaterialTheme.colorScheme.onSurfaceVariant)
                                                    }
                                                }
                                                Text("Увеличенный участок · ${pairTime((range.last-range.first+1)*result.stepMs)}",
                                                    style=MaterialTheme.typography.labelSmall)
                                                ComparisonTwoWaveforms(result,shownZoneIndex,previewId==referenceId,
                                                    previewId==targetId,position,range=range,focus=true,
                                                    referenceName=reference.title,targetName=target.title){
                                                        zoneAt,column,first->tapWave(zoneAt,column,first)
                                                    }
                                                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,
                                                    verticalAlignment=Alignment.CenterVertically){
                                                    TextButton({focusZoom=(focusZoom-1).coerceAtLeast(1)},enabled=focusZoom>1){
                                                        Text("− Отдалить")
                                                    }
                                                    Text("Масштаб: $focusZoom/4",style=MaterialTheme.typography.labelSmall)
                                                    TextButton({focusZoom=(focusZoom+1).coerceAtMost(4)},enabled=focusZoom<4){
                                                        Text("+ Приблизить")
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            Text("Прослушивание выбранного момента",style=MaterialTheme.typography.labelMedium,
                                color=MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                                val firstPlaying=previewId==referenceId&&playing
                                val secondPlaying=previewId==targetId&&playing
                                OutlinedButton(onClick={play(reference,if(firstPlaying)null else seekFrame(true))},
                                    enabled=zone==null||seekFrame(true)!=null,modifier=Modifier.weight(1f),
                                    border=androidx.compose.foundation.BorderStroke(1.4.dp,
                                        if(firstPlaying)musicPlaybackAccent else MaterialTheme.colorScheme.outline)){
                                    Icon(if(firstPlaying)Icons.Rounded.Pause else Icons.Rounded.PlayArrow,null)
                                    Spacer(Modifier.width(4.dp))
                                    Text(if(firstPlaying)"Пауза" else "Основа",maxLines=1,
                                        overflow=TextOverflow.Ellipsis)
                                }
                                OutlinedButton(onClick={play(target,if(secondPlaying)null else seekFrame(false))},
                                    enabled=zone==null||seekFrame(false)!=null,modifier=Modifier.weight(1f),
                                    border=androidx.compose.foundation.BorderStroke(1.4.dp,
                                        if(secondPlaying)musicPlaybackAccent else MaterialTheme.colorScheme.outline)){
                                    Icon(if(secondPlaying)Icons.Rounded.Pause else Icons.Rounded.PlayArrow,null)
                                    Spacer(Modifier.width(4.dp))
                                    Text(if(secondPlaying)"Пауза" else "Версия",maxLines=1,
                                        overflow=TextOverflow.Ellipsis)
                                }
                            }
                        }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().padding(top=4.dp).testTag("comparison-fixed-footer"),verticalAlignment=Alignment.CenterVertically){
                    IconButton(onClick={play(track)}){Icon(if(previewId==active&&playing)Icons.Rounded.Pause else Icons.Rounded.PlayArrow,"Прослушать выбранный трек")}
                    Column(Modifier.weight(1f)){
                        Text(if(active==referenceId)"Основа" else "Версия",style=MaterialTheme.typography.labelSmall)
                        Text(pairTime(if(previewId==active)position else 0L),style=MaterialTheme.typography.bodySmall)
                    }
                    Button({if(allowed(track))onKeep(active)},modifier=Modifier.testTag("comparison-keep")){
                        Text("Оставить выбранный")
                    }
                }
            }
        }
    }
    pickingReference?.let{original->
        AlertDialog(onDismissRequest={pickingReference=null},
            title={Text(if(original)"Выбрать основу" else "Выбрать сравниваемую запись")},
            text={
                LazyColumn(Modifier.fillMaxWidth().heightIn(max=400.dp).testTag("comparison-track-list"),
                    verticalArrangement=Arrangement.spacedBy(4.dp)){
                    items(group.tracks.filter{original||it.id!=referenceId},key={it.id}){t->
                        val chosen=if(original)t.id==referenceId else t.id==targetId
                        val select: () -> Unit ={
                            if(allowed(t)){
                                if(original){makeReference(t);active=t.id}else choose(t)
                                pickingReference=null
                            }
                        }
                        Row(Modifier.fillMaxWidth().clickable(onClick=select).padding(vertical=6.dp),
                            verticalAlignment=Alignment.CenterVertically){
                            RadioButton(chosen,select,modifier=Modifier.testTag(
                                if(original)"comparison-reference-${t.id}" else "comparison-select-${t.id}"))
                            Column(Modifier.weight(1f)){
                                Text(t.title,maxLines=2,overflow=TextOverflow.Ellipsis)
                                Text(listOfNotNull(t.duration?.let(::pairTime),t.bitrate?.let{"${it/1000} кбит/с"},
                                    android.text.format.Formatter.formatFileSize(context,t.bytes)).joinToString(" · "),
                                    style=MaterialTheme.typography.labelSmall)
                                Text(displayFileName(t.filename,MediaKind.AUDIO),style=MaterialTheme.typography.labelSmall,maxLines=1,overflow=TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            },confirmButton={TextButton({pickingReference=null}){Text("Закрыть")}})
    }
}

@Composable
private fun ComparisonTrackSelector(label:String,track:DuplicateTrack,active:Boolean,keeper:Boolean,
    modifier:Modifier=Modifier,onClick:()->Unit){
    Surface(onClick=onClick,modifier=modifier.fillMaxWidth(),shape=RoundedCornerShape(12.dp),
        color=MaterialTheme.colorScheme.surfaceVariant,
        border=if(active)androidx.compose.foundation.BorderStroke(1.dp,musicPlaybackAccent)else null){
        Row(Modifier.fillMaxWidth().padding(10.dp),verticalAlignment=Alignment.CenterVertically,
            horizontalArrangement=Arrangement.spacedBy(8.dp)){
            Icon(Icons.Rounded.MusicNote,null,tint=musicPlaybackAccent,modifier=Modifier.size(22.dp))
            Column(Modifier.weight(1f)){
                Text(label+(if(keeper)" · Остаётся" else ""),style=MaterialTheme.typography.labelSmall,color=musicPlaybackAccent)
                Text(track.title,style=MaterialTheme.typography.titleSmall,maxLines=1,overflow=TextOverflow.Ellipsis)
                Text(listOfNotNull(track.duration?.let(::pairTime),track.bitrate?.let{"${it/1000} кбит/с"},
                    displayFileName(track.filename,MediaKind.AUDIO)).joinToString(" · "),
                    style=MaterialTheme.typography.labelSmall,maxLines=1,overflow=TextOverflow.Ellipsis)
            }
            Icon(Icons.Rounded.ExpandMore,"Выбрать $label")
        }
    }
}

private fun comparisonZoneLabel(kind:ComparisonZoneKind)=when(kind){
    ComparisonZoneKind.SILENT_PADDING->"Тишина на границе"
    ComparisonZoneKind.QUIET_EDGE_UNCERTAIN->"Тихий хвост · отличие не подтверждено"
    ComparisonZoneKind.EXTRA_IN_VERSION->"Добавленный фрагмент"
    ComparisonZoneKind.MISSING_IN_VERSION->"Отсутствующий фрагмент"
    ComparisonZoneKind.CHANGED_CONTENT->"Изменённое звучание"
}

/** One yellow overlay, two independent aligned recordings, a shared comparison timeline.
 * Tapping either row seeks THAT row's recording using the corresponding frame. */
@Composable
private fun ComparisonTwoWaveforms(result:AlignedAudioComparison,selected:Int,
    playingFirst:Boolean,playingSecond:Boolean,position:Long,range:IntRange,
    referenceName:String,targetName:String,focus:Boolean=false,
    onTap:(Int,Int,Boolean)->Unit){
    Column(verticalArrangement=Arrangement.spacedBy(4.dp)){
        Text("Основа · $referenceName",style=MaterialTheme.typography.labelSmall,
            maxLines=1,overflow=TextOverflow.Ellipsis)
        AlignedPairWaveform(result,selected,playingFirst,playingSecond,position,
            range=range,focus=focus,laneFirst=true){zone,column->onTap(zone,column,true)}
        Spacer(Modifier.height(4.dp))
        Text("Сравниваемая · $targetName",style=MaterialTheme.typography.labelSmall,
            maxLines=1,overflow=TextOverflow.Ellipsis)
        AlignedPairWaveform(result,selected,playingFirst,playingSecond,position,
            range=range,focus=focus,laneFirst=false){zone,column->onTap(zone,column,false)}
    }
}

@Composable
private fun AlignedPairWaveform(result:AlignedAudioComparison,selected:Int,playingFirst:Boolean,playingSecond:Boolean,
    position:Long,range:IntRange,focus:Boolean=false,laneFirst:Boolean,onTap:(Int,Int)->Unit){
    if(range.isEmpty())return
    val accent=musicPlaybackAccent
    val other=MaterialTheme.colorScheme.onSurfaceVariant
    // Only the selected area is colored. The categories remain metadata, not a color code.
    val firstMax=remember(result){(result.first.maxOfOrNull{it.rms}?:0f).coerceAtLeast(1e-6f)}
    val secondMax=remember(result){(result.second.maxOfOrNull{it.rms}?:0f).coerceAtLeast(1e-6f)}
    val latestTap by rememberUpdatedState(onTap)
    val values=remember(result){result.columns.map{c->
        (c.first?.let{result.first[it].rms/firstMax}?:0f) to
            (c.second?.let{result.second[it].rms/secondMax}?:0f)
    }}
    val peakCache=remember(result,range){ComparisonPeakCache(values,range)}
    val cursorColumns=remember(result){comparisonCursorColumns(result.columns,result.first.size,result.second.size)}
    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(if(focus)88.dp else 76.dp)
        .testTag(if(focus){
            if(laneFirst)"comparison-focused-waveform" else "comparison-focused-waveform-version"
        }else{
            if(laneFirst)"comparison-aligned-waveform" else "comparison-aligned-waveform-version"
        })
        .pointerInput(result,range){
            detectTapGestures { point->
                val column=(range.first+(point.x/size.width.coerceAtLeast(1)*(range.last-range.first+1)).toInt())
                    .coerceIn(range.first,range.last)
                latestTap(result.zones.indexOfFirst{column>=it.start&&column<it.endExclusive},column)
            }
        }){
        val count=range.last-range.first+1
        val unit=size.width/count
        val middle=size.height/2
        fun x(column:Int)=(column-range.first)*unit
        for((index,zone)in result.zones.withIndex()){
            val lo=maxOf(range.first,zone.start);val hi=minOf(range.last+1,zone.endExclusive)
            if(hi>lo)drawRect(accent.copy(alpha=if(index==selected).32f else .14f),
                topLeft=androidx.compose.ui.geometry.Offset(x(lo),0f),
                size=androidx.compose.ui.geometry.Size(x(hi)-x(lo),size.height))
        }
        drawLine(other.copy(alpha=.28f),androidx.compose.ui.geometry.Offset(0f,middle),
            androidx.compose.ui.geometry.Offset(size.width,middle),strokeWidth=1.dp.toPx())
        // Preserve peaks when compressing to screen resolution, rather than drawing overlapping frames.
        val bins=(size.width/2.dp.toPx()).toInt().coerceIn(1,count)
        val (firstPeaks,secondPeaks)=peakCache.peaks(bins)
        for(bin in 0 until bins){
            val level=if(laneFirst)firstPeaks[bin] else secondPeaks[bin]
            val px=(bin+.5f)*size.width/bins
            val height=level.coerceIn(0f,1f)*middle*.90f
            if(height>0f)drawLine(other.copy(alpha=.90f),
                androidx.compose.ui.geometry.Offset(px,middle-height),
                androidx.compose.ui.geometry.Offset(px,middle+height),
                strokeWidth=1.6.dp.toPx(),cap=androidx.compose.ui.graphics.StrokeCap.Round)
        }
        val frame=(position/result.stepMs).toInt()
        val cursor=if(laneFirst && playingFirst)cursorColumns.first.getOrElse(frame){-1}
            else if(!laneFirst && playingSecond)cursorColumns.second.getOrElse(frame){-1}
            else -1
        if(cursor in range)drawLine(accent,androidx.compose.ui.geometry.Offset(x(cursor),0f),
            androidx.compose.ui.geometry.Offset(x(cursor),size.height),strokeWidth=1.5.dp.toPx())
    }
}

/** Display-only cursor. Unknown durations must never divide by zero or
 * move a different track's player position. */
internal fun comparisonWaveformProgress(positionMs:Long,durationMs:Long?):Float =
    if(durationMs==null || durationMs<=0L) 0f
    else (positionMs.coerceIn(0L,durationMs).toDouble()/durationMs).toFloat()

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
