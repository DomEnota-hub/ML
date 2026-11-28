package com.domenota.medialoader.ui.screens

import com.domenota.medialoader.ui.model.displayAudioTitle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import com.domenota.medialoader.ui.components.NeutralTextButton as TextButton
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
import com.domenota.medialoader.data.playback.ListeningHistoryStore
import com.domenota.medialoader.data.playback.ListeningWarmRankKey
import com.domenota.medialoader.data.playback.ListeningPeriod
import com.domenota.medialoader.data.playback.ListeningClockPolicy
import com.domenota.medialoader.data.playback.ListeningSelection
import com.domenota.medialoader.data.playback.ListeningProjectionDomain
import com.domenota.medialoader.data.playback.ListeningProjectionCodec
import com.domenota.medialoader.data.playback.SmartMusicSnapshot
import com.domenota.medialoader.data.playback.SmartTrackCandidate
import com.domenota.medialoader.data.playback.SmartStatsPeriod
import com.domenota.medialoader.data.playback.SmartMusicPolicy
import com.domenota.medialoader.data.playback.ResumeQueueStore
import com.domenota.medialoader.data.playback.ResumeQueuePolicy
import com.domenota.medialoader.core.logging.MusicRefreshBlock
import com.domenota.medialoader.core.logging.MusicRefreshTrace
import com.domenota.medialoader.data.playback.ResolvedResumeQueue
import com.domenota.medialoader.ui.components.*
import com.domenota.medialoader.ui.model.toUi
import com.domenota.medialoader.ui.screens.music.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

@Composable
internal fun MusicModeScreen(
    items: List<DownloadEntity>, allAudio: List<DownloadEntity>, playlists: List<MediaPlaylist>, header: @Composable () -> Unit,
    loading: Boolean, message: String?, onOpen: (String, List<String>) -> Unit, onOpenPrivate: () -> Unit,
    onCreate: suspend (String) -> String, onRename: suspend (String, String) -> Unit, onDeletePlaylist: suspend (String) -> Unit,
    onAddTracks: suspend (String, List<String>) -> Unit, onRemoveTrack: suspend (String, String) -> Unit,
    onMoveTrack: suspend (String, String, Int) -> Unit, onShare: (String) -> Unit, onDeleteFile: (String) -> Unit,
    searchVisible: Boolean? = null, tabOverride: Int? = null, scrollToTopRequest: Int = 0, onTabChange: (Int) -> Unit = {},
    onPreview: ((String, List<String>) -> Unit)? = null, onAddPlaylistTracks: ((String) -> Unit)? = null,
    onEnqueue: ((String, Boolean) -> Unit)? = null,
    onDetailChange: (Boolean) -> Unit = {}, currentPlayingId: String? = null, playing: Boolean = false,
    deviceMusicFolders: Map<String, String> = emptyMap(),
    hiddenTracksOpen: Boolean = false, onDismissHiddenTracks: () -> Unit = {},
    favoriteIds: Set<String> = emptySet(),
    duplicateLinkedIds: Set<String> = emptySet(),
    onTrashDuplicates: ((com.domenota.medialoader.data.library.DuplicateTrashSelection) -> Unit)? = null,
    duplicateRequest: Int = 0, onDuplicateRequestHandled: () -> Unit = {},
    onPreviewFromPlaylist: ((String,List<String>,String) -> Unit)? = null,
    onOpenFromPlaylist: ((String,List<String>,String) -> Unit)? = null,
    onOpenDuplicates: (() -> Unit)? = null,
    listeningKeys: Map<String,Set<String>> = emptyMap(),
    validateFrequent: suspend (List<String>,Boolean) -> Set<String> = { ids,_ -> ids.toSet() },
    onPlayFrequent: ((List<String>,Boolean)->Unit)? = null,
    onFolderPlay: ((List<String>,Boolean)->Unit)? = onPlayFrequent,
    onSaveFrequent: (suspend (String,List<String>)->Unit)? = null,
    onFavorite: (suspend (String,Boolean)->Unit)? = null,
    onResumeSelection: ((ResolvedResumeQueue)->Unit)? = null,
    onOpenCollections: (() -> Unit)? = null,
    onAddCollection: ((List<String>) -> Unit)? = null,
    onSimpleArchive: ((List<String>) -> Unit)? = null,
    onPassport: ((String) -> Unit)? = null,
    onRecognize:((String)->Unit)?=null,
    recentItems: List<DownloadEntity> = items, recentLoading: Boolean = loading,
    onRecentPlay: ((List<String>,Boolean)->Unit)? = null,
    onRecentPreview: ((String,List<String>)->Unit)? = null,
    onRecentOpen: ((String,List<String>)->Unit)? = null,
    browseAccess: Any? = Unit,
    preparedBrowse: BackgroundLibraryValue<MusicBrowseCatalog>? = null,
    analyticsItems: List<DownloadEntity> = allAudio,
    analyticsAccess: Any? = Unit,
    analyticsReady: Boolean = !loading,
    resumeCatalogReady: Boolean = analyticsReady,
    onAnalyticsPreview: (String,List<String>)->Unit = onPreview ?: onOpen,
    onAnalyticsOpen: (String,List<String>)->Unit = onOpen,
    onSaveFolderPlaylist: (suspend (String?,String,List<String>)->Unit)? = null,
    onFolderPlayExact: (suspend (List<String>)->Boolean)? = null,
    onCloseSearch: (() -> Unit)? = null,
) {
    var localTab by rememberSaveable { mutableIntStateOf(0) }
    var favoritesOnly by rememberSaveable { mutableStateOf(false) }
    val tab = tabOverride ?: localTab
    fun selectTab(value: Int) { localTab = value; onTabChange(value) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    // A small, transient selection. Never serialize 3000+ IDs in SavedState.
    // The public library is revalidated by the root collection action.
    var selectedTrackIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    var frequentOpen by rememberSaveable { mutableStateOf(false) }
    var smartPage by rememberSaveable { mutableStateOf<SmartMusicPage?>(null) }
    var resumeOpen by rememberSaveable { mutableStateOf(false) }
    var recentOpen by rememberSaveable { mutableStateOf(false) }
    val recentStateHolder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    var listeningPeriod by rememberSaveable { mutableStateOf(ListeningPeriod.MONTH) }
    var search by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(MusicLibrarySort.TITLE) }
    var sortMenu by remember { mutableStateOf(false) }
    var duplicatesOpen by remember { mutableStateOf(false) }
    fun openDuplicates() { if (onOpenDuplicates != null) onOpenDuplicates() else duplicatesOpen = true }
    LaunchedEffect(duplicateRequest, loading) {
        if (duplicateRequest != 0 && !loading) { onDuplicateRequestHandled(); openDuplicates() }
    }
    if (duplicatesOpen && onTrashDuplicates != null) {
        MusicDuplicatesDialog(allAudio, favoriteIds + duplicateLinkedIds + playlists.flatMap { it.trackIds }, currentPlayingId, playing,
            { duplicatesOpen = false }, onTrashDuplicates)
        return
    }
    val tracksListState = rememberLazyListState()
    val overviewListState = rememberLazyListState()
    val playlistListState = key(selectedId) { rememberLazyListState() }
    val listState = if (tab == 0) tracksListState else if (selectedId != null) playlistListState else overviewListState
    var handledTopRequest by rememberSaveable { mutableIntStateOf(0) }
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
    val context = androidx.compose.ui.platform.LocalContext.current
    val folderPrefs = remember(context) { context.getSharedPreferences("music_folder_visibility", 0) }
    var foldersGrid by remember { mutableStateOf(folderPrefs.getBoolean("folders_grid", false)) }
    var folderSort by remember(folderPrefs) {
        mutableStateOf(MusicFolderSort.fromPreference(folderPrefs.getString("folder_sort", null)))
    }
    var folderSortMenu by remember { mutableStateOf(false) }
    fun setFolderSort(value: MusicFolderSort) {
        folderSort = value
        folderPrefs.edit().putString("folder_sort", value.name).apply()
    }
    fun setFoldersGrid(grid: Boolean) {
        foldersGrid = grid
        folderPrefs.edit().putBoolean("folders_grid", grid).apply()
    }
    var excludedFolders by remember { mutableStateOf(folderPrefs.getStringSet("excluded", emptySet()).orEmpty().toSet()) }
    var hiddenTrackKeys by remember { mutableStateOf(folderPrefs.getStringSet("hidden_tracks", emptySet()).orEmpty().toSet()) }
    fun toggleHidden(track: DownloadEntity) {
        hiddenTrackKeys = MusicTrackVisibility.update(hiddenTrackKeys, track, !MusicTrackVisibility.hidden(track, hiddenTrackKeys))
        folderPrefs.edit().putStringSet("hidden_tracks", hiddenTrackKeys).apply()
    }
    var folderOpen by rememberSaveable { mutableStateOf<String?>(null) }
    // Session-local ordering only; changing the library access guard discards old identities.
    val folderShuffleOrders=remember(browseAccess){mutableStateMapOf<String,List<String>>()}
    var showExcluded by rememberSaveable { mutableStateOf(false) }
    val largeBrowse = items.size > 128 || allAudio.size > 128
    val browse = preparedBrowse ?: if(largeBrowse) addressedLibraryProjection(MusicBrowseCatalog.EMPTY,browseAccess,items,allAudio,deviceMusicFolders,serial=true) {
        withContext(Dispatchers.Default){ MusicBrowseCatalog.build(items,allAudio,deviceMusicFolders) }
    } else BackgroundLibraryValue(remember(items,allAudio,deviceMusicFolders,browseAccess){
        if(browseAccess==null)MusicBrowseCatalog.EMPTY else MusicBrowseCatalog.build(items,allAudio,deviceMusicFolders)
    },browseAccess!=null)
    val available = browse.value.available
    val listeningEnabled = onPlayFrequent != null && onSaveFrequent != null
    val listeningStore = remember(context) { ListeningHistoryStore.get(context) }
    val listeningRevision by listeningStore.revision.collectAsState()
    val currentHidden = hiddenTrackKeys
    val currentExcluded = excludedFolders
    val analyticsGuard = analyticsAccess?.let { Triple(it, currentHidden, currentExcluded) }
    val eligibleProjection = addressedLibraryProjection(MusicAnalyticsRows.EMPTY, analyticsGuard,
        analyticsItems, listeningKeys, deviceMusicFolders, favoriteIds, publishWhen = analyticsReady, serial=true,
        retain={previous,next->
            if(previous.keys==next.keys && previous.rows==next.rows && previous.candidates==next.candidates) previous
            else next.withEpoch(if(previous.keys.all{(id,keys)->next.keys[id]==keys})previous.displayEpoch else Any())
        }) {
        val rows = analyticsItems.filter { row -> availableAudio(row) && row.id in listeningKeys &&
            !MusicTrackVisibility.hidden(row, currentHidden) && MusicFolderRules.visible(row, currentExcluded, deviceMusicFolders) }.associateBy { it.id }
        val keys = listeningKeys.filterKeys { it in rows }
        MusicAnalyticsRows(rows, keys, rows.values.mapNotNull { row -> keys[row.id]?.let { SmartTrackCandidate(row.id,it,audioArtist(row),row.id in favoriteIds) } })
    }
    val frequentRows = eligibleProjection.value.rows
    val frequentKeys = eligibleProjection.value.keys
    val smartCandidates = eligibleProjection.value.candidates
    val analyticsUsable = analyticsReady && eligibleProjection.usable
    val resumeReady = resumeCatalogReady && analyticsUsable && eligibleProjection.ready
    val historyGeneration by listeningStore.visibilityRevision.collectAsState()
    val displayEpoch = eligibleProjection.value.displayEpoch
    val rankDigest = eligibleProjection.value.rankDigest
    val candidateDigest = eligibleProjection.value.candidateDigest
    var statsIntervals by rememberSaveable(stateSaver=androidx.compose.runtime.saveable.listSaver<List<com.domenota.medialoader.data.playback.SmartStatsInterval>,String>(
        save={list->list.flatMap{interval->listOf(interval.first.toString(),interval.end.toString(),interval.granularity.name,interval.title)}},
        restore={values->values.chunked(4).mapNotNull{parts->if(parts.size!=4)null else runCatching{
            com.domenota.medialoader.data.playback.SmartStatsInterval(java.time.LocalDate.parse(parts[0]),java.time.LocalDate.parse(parts[1]),
                com.domenota.medialoader.data.playback.StatsGranularity.valueOf(parts[2]),parts[3])
        }.getOrNull()}})){mutableStateOf(emptyList())}
    var smartStatsPeriod by rememberSaveable { mutableStateOf(SmartStatsPeriod.MONTH) }
    val smartPrefs = remember(context) { context.getSharedPreferences("smart_music",0) }
    var mixDiversity by remember { mutableIntStateOf(smartPrefs.getInt("diversity",50).coerceIn(0,100)/5*5) }
    var forgottenDays by remember { mutableLongStateOf(smartPrefs.getLong("forgotten_days",30L).takeIf { it==30L||it==60L } ?: 30L) }
    var mixNonce by rememberSaveable { mutableLongStateOf(smartPrefs.getLong("mix_nonce",0L)) }
    fun localDay()=java.time.LocalDate.now().toEpochDay()
    var smartDay by remember{mutableLongStateOf(localDay())}
    var smartZone by remember{mutableStateOf(java.time.ZoneId.systemDefault())}
    // System calendar changes and one exact rolling boundary, rather than a
    // permanent Compose polling loop. No timer when analytics is disabled or
    // there is no finite boundary; disposing the screen cancels its wait.
    // Returning to this screen must not flash a loader while the durable SQL
    // projection is revalidated. A warm result is keyed by the exact current
    // public/privacy guard, membership digest, period, exclusion revision and zone.
    val warmRankKey = remember(analyticsGuard, rankDigest, listeningPeriod,historyGeneration,smartZone,listeningEnabled,analyticsUsable) {
        analyticsGuard?.takeIf { listeningEnabled && analyticsUsable }?.let { guard ->
            ListeningWarmRankKey(guard,rankDigest,listeningPeriod,historyGeneration,smartZone.id)
        }
    }
    val warmRank = remember(warmRankKey) {
        listeningStore.warmRanks.get(warmRankKey,System.currentTimeMillis())
    }
    var rankingBoundary by remember(warmRankKey) {
        mutableLongStateOf(warmRank?.validUntil ?: Long.MAX_VALUE)
    }
    var rankTimeRevision by remember { mutableLongStateOf(0L) }
    DisposableEffect(context,listeningEnabled) {
        if(!listeningEnabled) onDispose { }
        else {
            var observed=System.currentTimeMillis()
            fun observe(clockChanged:Boolean=false) {
                val now=System.currentTimeMillis()
                smartDay=localDay();smartZone=java.time.ZoneId.systemDefault()
                if(ListeningClockPolicy.refreshRanking(observed,now,rankingBoundary,clockChanged)) {
                    if(now>=rankingBoundary)rankingBoundary=Long.MAX_VALUE
                    rankTimeRevision++
                }
                observed=now
            }
            val receiver=object:android.content.BroadcastReceiver(){
                override fun onReceive(context:android.content.Context?,intent:android.content.Intent?) {
                    observe(intent?.action==android.content.Intent.ACTION_TIME_CHANGED)
                }
            }
            val filter=android.content.IntentFilter().apply {
                addAction(android.content.Intent.ACTION_TIME_TICK)
                addAction(android.content.Intent.ACTION_TIME_CHANGED)
                addAction(android.content.Intent.ACTION_DATE_CHANGED)
                addAction(android.content.Intent.ACTION_TIMEZONE_CHANGED)
            }
            androidx.core.content.ContextCompat.registerReceiver(context,receiver,filter,androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
            observe()
            onDispose { context.unregisterReceiver(receiver) }
        }
    }
    LaunchedEffect(listeningEnabled,rankingBoundary,rankTimeRevision){
        val boundary=rankingBoundary
        if(!listeningEnabled||boundary==Long.MAX_VALUE)return@LaunchedEffect
        val scheduledAt=System.currentTimeMillis()
        val wait=ListeningClockPolicy.delayUntil(scheduledAt,boundary)
        if(wait>0)withContext(Dispatchers.Default){kotlinx.coroutines.delay(wait)}
        val now=System.currentTimeMillis()
        if(rankingBoundary==boundary&&ListeningClockPolicy.refreshRanking(scheduledAt,now,boundary)) {
            if(now>=boundary)rankingBoundary=Long.MAX_VALUE
            rankTimeRevision++
        }
    }
    val smartRevision by listeningStore.smartRevision.collectAsState()
    val resumeStore=remember(context){ResumeQueueStore.get(context)}
    val resumeSessions by resumeStore.sessions.collectAsState()
    val resumeMembership = remember(resumeSessions) { ResumeQueuePolicy.membership(resumeSessions) }
    val resumeFailed by resumeStore.failed.collectAsState()
    // Reordering or editing one saved queue must not empty all other queues.
    // Identity changes (including new alias ambiguity), exclusion and privacy
    // still invalidate every mapping immediately. Favorites are not identity.
    var resolvedMappings by remember(analyticsGuard,historyGeneration,rankDigest,displayEpoch){mutableStateOf<List<ResolvedResumeQueue>>(emptyList())}
    var resumeResolved by remember(analyticsGuard,historyGeneration,rankDigest,displayEpoch){mutableStateOf(false)}
    var resumeLoading by remember{mutableStateOf(false)}
    var resumeRecheckNonce by remember{mutableIntStateOf(0)}
    var completedResumeRecheck by remember{mutableIntStateOf(0)}
    var resumeError by remember(analyticsGuard,historyGeneration,resumeMembership,rankDigest,displayEpoch){mutableStateOf(false)}
    val resolvedSessions=remember(resolvedMappings,resumeSessions){
        ResumeQueuePolicy.rebindResolved(resolvedMappings,resumeSessions)
    }
    val resumeHasData=resumeResolved&&resolvedSessions.size==resumeSessions.size
    LaunchedEffect(resumeOpen,resumeMembership,rankDigest,displayEpoch,analyticsGuard,resumeReady,historyGeneration,resumeRecheckNonce) {
        if(!resumeOpen||onResumeSelection==null||!resumeReady)return@LaunchedEffect
        val requestedRecheck=resumeRecheckNonce
        val forceResolve=requestedRecheck!=completedResumeRecheck
        resumeLoading=!resumeHasData||forceResolve;resumeError=false
        val trace=MusicRefreshTrace.begin(MusicRefreshBlock.RESUME,resumeHasData,historyGeneration,resumeSessions.size)
        try {
            val sessions=resumeSessions
            val key=withContext(Dispatchers.Default){ListeningProjectionCodec.resumeKey(sessions,rankDigest)}
            val cached=listeningStore.readProjection(key,ListeningProjectionDomain.RESUME){ListeningProjectionCodec.resume(it,sessions)}
            val revision=cached?.currentRevision ?: listeningStore.persistedProjectionRevision(ListeningProjectionDomain.RESUME)
            val reusable=!forceResolve&&cached!=null&&cached.revision==revision
            val result=if(reusable)requireNotNull(cached).value else listeningStore.resolveResume(sessions,smartCandidates)
            if(listeningStore.visibilityRevision.value!=historyGeneration || listeningStore.persistedProjectionRevision(ListeningProjectionDomain.RESUME)!=revision)return@LaunchedEffect
            resolvedMappings=result;resumeResolved=true;completedResumeRecheck=requestedRecheck;resumeLoading=false
            trace?.outcome=if(reusable)MusicRefreshTrace.Outcome.CACHE else MusicRefreshTrace.Outcome.PUBLISHED
            if(!reusable)try{listeningStore.writeProjection(key,ListeningProjectionDomain.RESUME,revision,System.currentTimeMillis(),withContext(Dispatchers.Default){ListeningProjectionCodec.encodeResume(result)})}
            catch(e:CancellationException){throw e}catch(_:Exception){/* derived cache */}
        } catch(e:CancellationException){trace?.outcome=MusicRefreshTrace.Outcome.CANCELLED;throw e}
        catch(_:Exception){trace?.outcome=MusicRefreshTrace.Outcome.ERROR;if(!resumeHasData||forceResolve)resumeError=true}
        finally{resumeLoading=false;trace?.close()}
    }
    var smartSnapshot by remember(analyticsGuard,historyGeneration,displayEpoch,smartStatsPeriod,statsIntervals,mixDiversity,forgottenDays,mixNonce,smartDay,smartZone) { mutableStateOf(SmartMusicSnapshot()) }
    var smartHasData by remember(analyticsGuard,historyGeneration,displayEpoch,smartStatsPeriod,statsIntervals,mixDiversity,forgottenDays,mixNonce,smartDay,smartZone) { mutableStateOf(false) }
    var smartLoading by remember { mutableStateOf(false) }
    var smartError by remember(analyticsGuard,historyGeneration,displayEpoch,smartStatsPeriod,statsIntervals,mixDiversity,forgottenDays,mixNonce,smartDay,smartZone) { mutableStateOf<String?>(null) }
    // Mix and forgotten stay stable between relevant history changes, a new day
    // or an explicit regeneration. Only the stats page watches elapsed audio.
    val smartRefresh = if(smartPage==SmartMusicPage.STATS) smartRevision else listeningRevision
    LaunchedEffect(smartPage,candidateDigest,smartRefresh,smartStatsPeriod,statsIntervals,mixDiversity,forgottenDays,mixNonce,smartDay,smartZone,analyticsGuard,analyticsUsable,historyGeneration) {
        if(smartPage==null||!listeningEnabled||!analyticsUsable) return@LaunchedEffect
        smartLoading=!smartHasData;smartError=null
        val trace=MusicRefreshTrace.begin(MusicRefreshBlock.SMART,smartHasData,historyGeneration,smartCandidates.size)
        try {
            val now=System.currentTimeMillis()
            val seed=smartDay+mixNonce
            val zone=smartZone
            val cacheKey=withContext(Dispatchers.Default) { ListeningProjectionCodec.smartKey(candidateDigest,smartStatsPeriod,smartDay,seed,mixDiversity,forgottenDays,statsIntervals.lastOrNull(),zone) }
            val cached=listeningStore.readProjection(cacheKey,ListeningProjectionDomain.STATS,ListeningProjectionCodec::smart)
            val revision=cached?.currentRevision ?: listeningStore.persistedProjectionRevision(ListeningProjectionDomain.STATS)
            var candidates=smartCandidates
            val reusable=cached!=null && cached.reusable(now,if(smartPage==SmartMusicPage.STATS)60_000L else 60*60*1000L)
            // A persisted result is safe only for the exact complete public
            // catalog and current history revision. A stale disk result must not
            // resurrect a deleted/excluded row on a cold start.
            if(reusable&&!smartHasData && listeningStore.visibilityRevision.value==historyGeneration){smartSnapshot=requireNotNull(cached).value;smartHasData=true;smartLoading=false}
            var result=if(reusable) requireNotNull(cached).value else listeningStore.smartSnapshot(candidates,smartStatsPeriod,now,seed,mixDiversity,forgottenDays,zone,statsIntervals.lastOrNull())
            while(true) {
                val ids=when(smartPage){SmartMusicPage.FORGOTTEN->result.forgotten;SmartMusicPage.MIX->result.mix;SmartMusicPage.STATS->result.playedTracks;null->emptyList()}.map {it.rowId}
                if(ids.isEmpty()) break
                val valid=validateFrequent(ids,false)
                val missing=ids.filterNot(valid::contains).toSet()
                if(missing.isEmpty()) break
                candidates=candidates.filterNot {it.rowId in missing}
                result=listeningStore.smartSnapshot(candidates,smartStatsPeriod,now,seed,mixDiversity,forgottenDays,zone,statsIntervals.lastOrNull())
            }
            if(listeningStore.visibilityRevision.value!=historyGeneration || (listeningStore.persistedProjectionRevision(ListeningProjectionDomain.STATS)!=revision && smartPage==SmartMusicPage.STATS))return@LaunchedEffect
            smartSnapshot=result;smartHasData=true;smartLoading=false
            trace?.outcome=if(reusable)MusicRefreshTrace.Outcome.CACHE else MusicRefreshTrace.Outcome.PUBLISHED
            if(!reusable && candidates==smartCandidates) try {
                listeningStore.writeProjection(cacheKey,ListeningProjectionDomain.STATS,revision,now,withContext(Dispatchers.Default){ListeningProjectionCodec.encode(result)})
            } catch(e:CancellationException){throw e} catch(_:Exception){/* derived cache can be rebuilt */}
        } catch(e:CancellationException){trace?.outcome=MusicRefreshTrace.Outcome.CANCELLED;throw e}
        catch(_:Exception){trace?.outcome=MusicRefreshTrace.Outcome.ERROR;if(!smartHasData)smartError="Не удалось загрузить подборку"}
        finally{smartLoading=false;trace?.close()}
    }
    // Store already has a persistent SQLite rank cache; seed the first Compose
    // frame from the last *validated* in-process result before background IO.
    var listeningSelection by remember(warmRankKey) { mutableStateOf(warmRank ?: ListeningSelection()) }
    var listeningHasData by remember(warmRankKey) { mutableStateOf(warmRank != null) }
    var listeningLoading by remember { mutableStateOf(false) }
    var listeningError by remember(warmRankKey) { mutableStateOf<String?>(null) }
    LaunchedEffect(listeningEnabled,listeningRevision,listeningPeriod,rankDigest,analyticsGuard,analyticsUsable,historyGeneration,rankTimeRevision,smartZone) {
        if (!listeningEnabled || !analyticsUsable) return@LaunchedEffect
        listeningLoading = !listeningHasData; listeningError = null
        val trace=MusicRefreshTrace.begin(MusicRefreshBlock.RANK,listeningHasData,historyGeneration,frequentKeys.size)
        try {
            var eligible = frequentKeys
            val now = System.currentTimeMillis()
            val cacheKey=withContext(Dispatchers.Default){ListeningProjectionCodec.selectionKey(rankDigest,listeningPeriod)}
            val cached=listeningStore.readProjection(cacheKey,ListeningProjectionDomain.RANK,ListeningProjectionCodec::selection)
            val revision=cached?.currentRevision ?: listeningStore.persistedProjectionRevision(ListeningProjectionDomain.RANK)
            val reusable=cached!=null && cached.reusable(now,Long.MAX_VALUE) && cached.value.currentAt(now)
            if(reusable&&!listeningHasData && listeningStore.visibilityRevision.value==historyGeneration){val stored=requireNotNull(cached).value;listeningSelection=stored;rankingBoundary=stored.validUntil;listeningHasData=true;listeningLoading=false}
            var result = if(reusable) requireNotNull(cached).value else listeningStore.selection(eligible,listeningPeriod,now)
            while (result.ranked.isNotEmpty()) {
                val ids = result.ranked.map { it.rowId }
                val valid = validateFrequent(ids,false)
                val missing = ids.filterNot(valid::contains)
                if (missing.isEmpty()) break
                eligible = eligible - missing.toSet()
                result = listeningStore.selection(eligible,listeningPeriod,now)
            }
            if(listeningStore.visibilityRevision.value!=historyGeneration || listeningStore.persistedProjectionRevision(ListeningProjectionDomain.RANK)!=revision)return@LaunchedEffect
            listeningSelection = result;rankingBoundary=result.validUntil;listeningHasData=true;listeningLoading=false
            trace?.outcome=if(reusable)MusicRefreshTrace.Outcome.CACHE else MusicRefreshTrace.Outcome.PUBLISHED
            warmRankKey?.let { key -> listeningStore.warmRanks.put(key,result,now) }
            if(!reusable && eligible==frequentKeys) try {
                listeningStore.writeProjection(cacheKey,ListeningProjectionDomain.RANK,revision,now,withContext(Dispatchers.Default){ListeningProjectionCodec.encode(result)})
            } catch(e:CancellationException){throw e} catch(_:Exception){/* derived cache can be rebuilt */}
        } catch (e: CancellationException) { trace?.outcome=MusicRefreshTrace.Outcome.CANCELLED;throw e }
        catch (_: Exception) { trace?.outcome=MusicRefreshTrace.Outcome.ERROR;if(!listeningHasData)listeningError = "Не удалось загрузить статистику" }
        finally { listeningLoading = false;trace?.close() }
    }
    if(resumeOpen) MusicReadModelTrace(MusicRefreshBlock.RESUME,resumeResolved&&resolvedSessions.size==resumeSessions.size,
        resumeHasData,!resumeReady||!resumeHasData&&(resumeLoading||!resumeError),resumeFailed||resumeError,
        resolvedSessions.size,analyticsGuard!=null,privacyEpoch=historyGeneration,
        reasonMask=(if(!resumeReady)1 else 0) or (if(!resumeResolved)2 else 0) or
            (if(resolvedSessions.size!=resumeSessions.size)4 else 0))
    if(smartPage!=null) MusicReadModelTrace(MusicRefreshBlock.SMART,smartHasData,smartHasData&&analyticsGuard!=null,
        !smartHasData&&(smartLoading||!analyticsUsable||smartError==null),smartError!=null,
        smartSnapshot.playedTracks.size+smartSnapshot.forgotten.size+smartSnapshot.mix.size,
        analyticsGuard!=null,privacyEpoch=historyGeneration,reasonMask=if(!analyticsUsable)1 else 0)
    if(listeningEnabled) MusicReadModelTrace(MusicRefreshBlock.RANK,listeningHasData,listeningHasData&&analyticsGuard!=null,
        !listeningHasData&&(listeningLoading||!analyticsUsable||listeningError==null),listeningError!=null,
        listeningSelection.ranked.size,analyticsGuard!=null,privacyEpoch=historyGeneration,
        reasonMask=if(!analyticsUsable)1 else 0)
    // Card and detail consume exactly the same currently eligible rows.
    val visibleSelection=remember(listeningSelection,frequentRows){listeningSelection.copy(ranked=listeningSelection.ranked.filter{it.rowId in frequentRows})}
    val tracks = browse.value.tracks
    val recentGuard = browseAccess?.let { Triple(it,currentHidden,currentExcluded) }
    val recentView = if(recentItems.size>128) addressedLibraryProjection(emptyList<DownloadEntity>(),recentGuard,
        recentItems,deviceMusicFolders,publishWhen=recentOpen,serial=true) {
        withContext(Dispatchers.Default){ musicRecentRows(recentItems,currentHidden,currentExcluded,deviceMusicFolders) }
    } else BackgroundLibraryValue(remember(recentItems,currentHidden,currentExcluded,deviceMusicFolders,recentGuard){
        if(recentGuard==null)emptyList() else musicRecentRows(recentItems,currentHidden,currentExcluded,deviceMusicFolders)
    },recentGuard!=null)
    val recentRows = recentView.value
    // Hidden tracks are only projected while their explicit dialog is open.
    val hiddenTracks = remember(browse.value, hiddenTrackKeys,hiddenTracksOpen) {
        if(!hiddenTracksOpen)emptyList() else available.values.filter { MusicTrackVisibility.hidden(it, hiddenTrackKeys) }
    }
    if (hiddenTracksOpen) AlertDialog(onDismissRequest = onDismissHiddenTracks, title = { Text("Скрытые треки") },
        text = { androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 360.dp)) {
            if (hiddenTracks.isEmpty()) item { Text("Нет скрытых треков") }
            items(hiddenTracks, key = { it.id }) { track ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(displayAudioTitle(track),
                        Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val singleTrackQueue = HiddenMusicPreview.queue(track, hiddenTrackKeys)
                    IconButton(
                        enabled = singleTrackQueue.isNotEmpty(),
                        onClick = { (onPreview ?: onOpen)(track.id, singleTrackQueue) },
                    ) { Icon(Icons.Rounded.PlayArrow, "Прослушать ${track.originalName}") }
                    IconButton(onClick = { toggleHidden(track) }) {
                        Icon(Icons.Rounded.Visibility, "Отобразить ${track.originalName}")
                    }
                }
            }
        } }, confirmButton = { TextButton(onClick = onDismissHiddenTracks) { Text("Готово") } })
    val folders = browse.value.folders
    val folderCounts = remember(folders) { folders.entries.mapNotNull { (key, rows) -> key?.let { it to rows.size } }.toMap() }
    val sortedFolderKeys = remember(folderCounts, folderSort) {
        MusicFolderRules.sortedKeys(folderCounts.keys, folderCounts, folderSort)
    }
    val playlist = playlists.firstOrNull { it.id == selectedId }.takeIf { tab == 1 }
    com.domenota.medialoader.ui.components.ReportDrawerScreen(
        playlist==null && addingPlaylist==null && !frequentOpen && smartPage==null &&
            !resumeOpen && folderOpen==null && !recentOpen && selectedTrackIds.isEmpty() &&
            !duplicatesOpen && !creating && renameTarget==null && deleteTarget==null &&
            addTrack==null && !busy && !sortMenu && !folderSortMenu && !hiddenTracksOpen)
    LaunchedEffect(searchVisible) { if (searchVisible == false) search = "" }
    LaunchedEffect(playlist?.id, addingPlaylist, frequentOpen,smartPage,resumeOpen,folderOpen,recentOpen) { onDetailChange(playlist != null || addingPlaylist != null || frequentOpen || smartPage != null || resumeOpen || folderOpen != null || recentOpen) }
    DisposableEffect(Unit) { onDispose { onDetailChange(false) } }
    BackHandler(folderOpen != null || resumeOpen || smartPage != null || frequentOpen || playlist != null || recentOpen || tab == 1) { if(recentOpen)recentOpen=false else if(folderOpen!=null)folderOpen=null else if(resumeOpen)resumeOpen=false else if(smartPage==SmartMusicPage.STATS&&statsIntervals.isNotEmpty()) statsIntervals=statsIntervals.dropLast(1) else if(smartPage!=null) smartPage=null else if (frequentOpen) frequentOpen = false else if (playlist != null) selectedId = null else selectTab(0) }
    val searchFocus = androidx.compose.ui.platform.LocalFocusManager.current
    BackHandler(searchVisible == true && onCloseSearch != null && playlist == null && addingPlaylist == null &&
        folderOpen == null && !resumeOpen && smartPage == null && !frequentOpen && !recentOpen &&
        !duplicatesOpen && selectedTrackIds.isEmpty() && !hiddenTracksOpen) {
        search = ""
        searchFocus.clearFocus()
        onCloseSearch?.invoke()
    }
    val toAdd = playlists.firstOrNull { it.id == addingPlaylist }
    if (toAdd != null) {
        PlaylistTracksScreen(toAdd, fallbackSource, { fallbackSource = it }, tracks, browse.value.allRows, emptyMap(),
            { addingPlaylist = null }, onPreview ?: onOpen, { onAddTracks(toAdd.id, it) })
        return
    }
    val browseFilter = browseAccess?.let { MusicBrowseFilter(it,tab,playlist?.id,search,sort,favoritesOnly,currentHidden,currentExcluded) }
    val needsRows=tab==0 || playlist!=null
    val browseRows=if(largeBrowse) addressedLibraryProjection(MusicBrowseRows.EMPTY,browseFilter,browse.value,playlist,
        favoriteIds,deviceMusicFolders,publishWhen=needsRows&&browse.usable,serial=true,
        revalidateRetained={ previous -> previous.retainAllowed(sort) { row -> browse.value.available[row.id]?.savedUri == row.savedUri && row.id in browse.value.available } }) {
        withContext(Dispatchers.Default) { MusicBrowseRows.build(browse.value,playlist,tab,search,sort,favoritesOnly,favoriteIds,currentHidden,currentExcluded,deviceMusicFolders) }
    } else BackgroundLibraryValue(remember(browse.value,playlist,browseFilter,favoriteIds,deviceMusicFolders){
        if(browseFilter==null||!needsRows)MusicBrowseRows.EMPTY else MusicBrowseRows.build(browse.value,playlist,tab,search,sort,favoritesOnly,favoriteIds,currentHidden,currentExcluded,deviceMusicFolders)
    },browseFilter!=null)
    val displayed=browseRows.value.displayed
    val sections=browseRows.value.sections
    LaunchedEffect(scrollToTopRequest) {
        if (scrollToTopRequest != handledTopRequest) {
            handledTopRequest = scrollToTopRequest
            if (tab == 1) {
                selectedId=null; folderOpen=null; recentOpen=false; frequentOpen=false; resumeOpen=false; smartPage=null
                overviewListState.scrollToItem(0)
            } else tracksListState.scrollToItem(0)
        }
    }
    val displayedIds=browseRows.value.displayedIds
    // A restored scroll address must never be measured against the temporary
    // controls-only list while the guarded projection rebuilds after navigation.
    // Keep the real saved state idle; pending/revoked content gets its own state.
    val pendingTrackRows = needsRows && (!browse.usable || !browseRows.usable ||
        loading && displayedIds.isEmpty())
    val contentListState = if (pendingTrackRows) rememberLazyListState() else listState
    val selectableIds=remember(browseRows.value,tab) {
        if(tab==0) displayed.mapTo(mutableSetOf()) { it.id }
        else displayedIds.filterTo(mutableSetOf()) { it in available }
    }
    val selectedPublicIds=selectedTrackIds.intersect(selectableIds)
    LaunchedEffect(tab,selectedId) { selectedTrackIds=emptySet() }
    // A background scan or privacy revocation can remove a track mid-selection.
    // Revalidate synchronously at the actual action as well as in the UI.
    LaunchedEffect(selectableIds) {
        val retained=selectedTrackIds.intersect(selectableIds)
        if(retained!=selectedTrackIds)selectedTrackIds=retained
    }
    fun toggleTrackSelection(id:String) {
        if(id in selectableIds) selectedTrackIds=if(id in selectedTrackIds) selectedTrackIds-id
            else selectedTrackIds+id
    }
    val queue=browseRows.value.queue
    fun preview(id: String) {
        if (playlist != null && onPreviewFromPlaylist != null) onPreviewFromPlaylist(id,queue,playlist.id)
        else (onPreview ?: onOpen)(id, queue)
    }
    fun openTrack(id: String) {
        if (playlist != null && onOpenFromPlaylist != null) onOpenFromPlaylist(id,queue,playlist.id)
        else onOpen(id,queue)
    }
    if(recentOpen) {
        recentStateHolder.SaveableStateProvider("recent-music") {
            RecentMusicScreen(recentRows, recentLoading || !recentView.usable, currentPlayingId, playing, { recentOpen=false },
                onPlay = { ids, shuffle -> if(onRecentPlay!=null)onRecentPlay(ids,shuffle) else if(onPlayFrequent!=null)onPlayFrequent(ids,shuffle) else if(ids.isNotEmpty()) {
                    val order=if(shuffle)ids.shuffled() else ids; (onPreview?:onOpen)(order.first(),order)
                } }, onPreview = onRecentPreview?:onPreview?:onOpen,
                actions = { track, ids -> val id=track.id; MusicTrackActions(track,onEnqueue?.let{action->{next:Boolean->action(id,next)}},
                    {(onRecentOpen?:onOpen)(id,ids)},{addTrack=id},{onShare(id)},{onDeleteFile(id)},null,null,null,
                    onToggleHidden={toggleHidden(track)},favorite=id in favoriteIds,
                    onFavorite=onFavorite?.let{action->{mutate{action(id,id !in favoriteIds)}}},
                    onCollection=onAddCollection?.let{action->{action(listOf(id))}},onPassport=onPassport?.let{action->{action(id)}},
                    onRecognize=onRecognize?.let{action->{action(id)}}) })
        }
    } else if(folderOpen!=null) {
        val folder=requireNotNull(folderOpen)
        val folderGuard=browseAccess?.let { Triple(it,folder,sort) to currentHidden }
        val folderView=if(largeBrowse) addressedLibraryProjection(emptyList<DownloadEntity>(),folderGuard,browse.value,serial=true) {
            withContext(Dispatchers.Default){sortMusicRows(folders[folder].orEmpty().filterNot{MusicTrackVisibility.hidden(it,currentHidden)},sort)}
        } else BackgroundLibraryValue(remember(folders,folder,hiddenTrackKeys,sort){sortMusicRows(folders[folder].orEmpty().filterNot{MusicTrackVisibility.hidden(it,hiddenTrackKeys)},sort)},true)
        val originalFolderTracks=folderView.value
        val shuffledFolderOrder=folderShuffleOrders[folder]
        val folderTracks=remember(originalFolderTracks,shuffledFolderOrder){
            val byId=originalFolderTracks.associateBy{it.id}
            MusicFolderRules.visibleOrder(originalFolderTracks.map{it.id},shuffledFolderOrder).mapNotNull(byId::get)
        }
        val folderIds=remember(folderTracks){folderTracks.map{it.id}}
        val folderPlayScope=rememberCoroutineScope()
        var folderPlayBusy by remember(folder){mutableStateOf(false)}
        fun playFolder(shuffle:Boolean){
            if(folderPlayBusy || folderIds.isEmpty())return
            val order=if(shuffle)MusicFolderRules.shuffledOrder(folderIds) else folderIds.toList()
            val exact=onFolderPlayExact
            if(exact==null){
                if(onFolderPlay!=null)onFolderPlay(order,false) else (onPreview?:onOpen)(order.first(),order)
                if(shuffle)folderShuffleOrders[folder]=order
            }else{
                folderPlayBusy=true
                folderPlayScope.launch {
                    try {
                        if(exact(order)&&shuffle)folderShuffleOrders[folder]=order
                    }finally{folderPlayBusy=false}
                }
            }
        }
        var folderPlaylistIds by remember(folder){mutableStateOf<List<String>?>(null)}
        var newFolderPlaylist by remember(folder){mutableStateOf(false)}
        var folderPlaylistError by remember(folder){mutableStateOf<String?>(null)}
        var folderPlaylistBusy by remember(folder){mutableStateOf(false)}
        val liveFolderIds by rememberUpdatedState(folderIds)
        suspend fun saveFolderPlaylist(target:String?,name:String,ids:List<String>) {
            check(ids.isNotEmpty() && ids.toSet()==liveFolderIds.toSet()) { "Состав папки изменился. Повтори действие" }
            requireNotNull(onSaveFolderPlaylist)(target,name,ids)
            folderPlaylistIds=null;newFolderPlaylist=false;folderPlaylistError=null
        }
        MusicFolderScreen(MusicFolderRules.title(folder),folderTracks,folder in excludedFolders,currentPlayingId,playing,{folderOpen=null},
            onToggleVisibility={val exclude=folder !in excludedFolders;excludedFolders=if(exclude)excludedFolders+folder else excludedFolders-folder;folderPrefs.edit().putStringSet("excluded",excludedFolders).apply()},
            onPlay=::playFolder,
            onRestoreOrder=if(shuffledFolderOrder!=null){{folderShuffleOrders.remove(folder);Unit}}else null,
            playBusy=folderPlayBusy,
            onPreview={id->(onPreview?:onOpen)(id,folderIds)},
            onAddCollection=onAddCollection,
            onSimpleArchive=onSimpleArchive,
            onAddAllToPlaylist=onSaveFolderPlaylist?.let{{ids:List<String>->folderPlaylistError=null;folderPlaylistIds=ids}},
            actions={track->val id=track.id;MusicTrackActions(track,onEnqueue?.let{action->{next:Boolean->action(id,next)}},{onOpen(id,folderIds)},{addTrack=id},{onShare(id)},{onDeleteFile(id)},null,null,null,MusicTrackVisibility.hidden(track,hiddenTrackKeys),{toggleHidden(track)},
                favorite=id in favoriteIds,onFavorite=onFavorite?.let{action->{mutate{action(id,id !in favoriteIds)}}},onCollection=onAddCollection?.let{action->{action(listOf(id))}},onPassport=onPassport?.let{action->{action(id)}},onRecognize=onRecognize?.let{action->{action(id)}})})
        folderPlaylistIds?.let { ids ->
            if(newFolderPlaylist) LibraryNameDialog("Новый плейлист", MusicFolderRules.title(folder),
                {newFolderPlaylist=false}) { name -> saveFolderPlaylist(null,name,ids) }
            else AlertDialog(onDismissRequest={if(!folderPlaylistBusy)folderPlaylistIds=null},
                title={Text("Добавить в плейлист")},
                text={Column(Modifier.heightIn(max=320.dp).verticalScroll(rememberScrollState())) {
                    playlists.forEach { entry -> TextButton(enabled=!folderPlaylistBusy,onClick={
                        if(!folderPlaylistBusy){folderPlaylistBusy=true;folderPlaylistError=null;scope.launch {
                            try {saveFolderPlaylist(entry.id,"",ids)}
                            catch(e:CancellationException){throw e}
                            catch(e:Exception){folderPlaylistError=e.message?:"Не удалось сохранить плейлист"}
                            finally {folderPlaylistBusy=false}
                        }}
                    }) {Text(entry.name)} }
                    TextButton(enabled=!folderPlaylistBusy,onClick={newFolderPlaylist=true}) {Icon(Icons.Rounded.Add,null);Text("Новый плейлист")}
                    folderPlaylistError?.let{Text(it,color=MaterialTheme.colorScheme.error)}
                }},confirmButton={TextButton({folderPlaylistIds=null},enabled=!folderPlaylistBusy){Text("Отмена")}})
        }
    } else if(resumeOpen&&onResumeSelection!=null) {
        ResumeMusicScreen(resolvedSessions,frequentRows,playlists.associate{it.id to it.name},(!resumeReady||resumeLoading||!resumeHasData && !resumeError),
            resumeFailed||resumeError,{resumeOpen=false},onResumeSelection,store=resumeStore,
            onRecheck=if(resumeReady){{resumeLoading=true;resumeRecheckNonce++}}else null)
    } else if(smartPage!=null && onPlayFrequent!=null && onSaveFrequent!=null) {
        SmartMusicScreen(smartPage!!,smartSnapshot,frequentRows,smartStatsPeriod,{smartStatsPeriod=it;statsIntervals=emptyList()},
            mixDiversity,{mixDiversity=it;smartPrefs.edit().putInt("diversity",it).apply()},
            forgottenDays,{forgottenDays=it;smartPrefs.edit().putLong("forgotten_days",it).apply()},
            (!smartHasData && (smartLoading||!analyticsUsable||smartError==null)),smartError,currentPlayingId,playing,favoriteIds,{smartPage=null},{mixNonce++;smartPrefs.edit().putLong("mix_nonce",mixNonce).apply()},
            onPlayFrequent,onAnalyticsPreview,onAnalyticsOpen,onSaveFrequent,{addTrack=it},onShare,onDeleteFile,onEnqueue,onFavorite,onAddCollection,onPassport,
            onRecognize=onRecognize,statsInterval=statsIntervals.lastOrNull(),onStatsDrill={point->
                val interval=statsIntervals.lastOrNull() ?: smartSnapshot.statsInterval ?: com.domenota.medialoader.data.playback.SmartStatsInterval.overview(smartStatsPeriod,System.currentTimeMillis(),java.time.ZoneId.systemDefault())
                point.date?.let { interval.drill(it) }?.let { statsIntervals=statsIntervals+it }
            },onStatsBack={statsIntervals=statsIntervals.dropLast(1)},onStatsDay={date->
                if(statsIntervals.lastOrNull()?.isSingleDay==true && !date.isAfter(java.time.LocalDate.now()))
                    statsIntervals=statsIntervals.dropLast(1)+com.domenota.medialoader.data.playback.SmartStatsInterval.day(date)
            })
    } else if (frequentOpen && onPlayFrequent != null && onSaveFrequent != null) {
        FrequentListeningScreen(visibleSelection,frequentRows,listeningPeriod,{listeningPeriod=it},(!listeningHasData && (listeningLoading||!analyticsUsable||listeningError==null)),listeningError,
            currentPlayingId,playing,favoriteIds,listeningStore,{frequentOpen=false},onPlayFrequent,
            onAnalyticsPreview,onAnalyticsOpen,onSaveFrequent,{addTrack=it},onShare,onDeleteFile,onEnqueue,onFavorite,onAddCollection,onPassport,onRecognize=onRecognize)
    } else Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
        val fastLabels=browseRows.value.fastLabels
        LazyColumn(Modifier.fillMaxSize().padding(end = if (tab == 0 && displayed.size > 5 || playlist != null && displayedIds.size > 5) musicFastScrollLaneWidth else 0.dp).testTag("music-library"), state = contentListState, contentPadding = PaddingValues(bottom = 12.dp)) {
            item(key = "music-controls") {
                Column {
                    if (playlist == null) header() else Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton({ selectedId = null }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Все плейлисты") }
                        Text(playlist.name, Modifier.weight(1f), fontSize = 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        PlaylistActions(playlist, { renameTarget = playlist }, { deleteTarget = playlist })
                    }
                    if (searchVisible != false && playlist == null) OutlinedTextField(search, { search = it },
                        Modifier.fillMaxWidth().padding(horizontal = 17.dp).padding(bottom = 12.dp), singleLine = true,
                        label = { Text("Поиск музыки") }, leadingIcon = { Icon(Icons.Rounded.Search, null) }, shape = RoundedCornerShape(12.dp),
                        trailingIcon = if (search.isNotEmpty()) ({ IconButton({ search = "" }) { Icon(Icons.Rounded.Close, "Очистить поиск") } }) else null)
                    if (tab == 0 && searchVisible == true && playlist == null && onOpenDuplicates != null && onTrashDuplicates != null)
                        DuplicateSearchShortcut(::openDuplicates, Modifier.fillMaxWidth().padding(horizontal = 17.dp, vertical = 4.dp))
                    if (tab == 0) BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 17.dp)) {
                        val wide = !listeningEnabled || maxWidth >= 480.dp
                        Row(Modifier.fillMaxWidth(),verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                        FilterChip(
                            selected = favoritesOnly,
                            onClick = { favoritesOnly = !favoritesOnly },
                            label = { Text("Избранное") },
                            leadingIcon = { Icon(
                                if (favoritesOnly) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = if (favoritesOnly) androidx.compose.ui.graphics.Color(0xFFF04452)
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                            ) },
                        )
                        if (listeningEnabled) FilterChip(selected=false,onClick={frequentOpen=true},label={Text("Часто слушаю")},
                            leadingIcon={Icon(Icons.Rounded.GraphicEq,null,Modifier.size(18.dp),tint=com.domenota.medialoader.ui.theme.musicPlaybackAccent)},
                            modifier=Modifier.testTag("frequent-listening-chip"))
                        if(onResumeSelection!=null) FilterChip(selected=false,onClick={resumeOpen=true},label={Text("Продолжить слушать")},leadingIcon={Icon(Icons.Rounded.PlayCircle,null,Modifier.size(18.dp))})
                        if(listeningEnabled) SmartMusicPage.entries.forEach { page ->
                            FilterChip(selected=false,onClick={smartPage=page},label={Text(page.label)},leadingIcon={Icon(page.icon,null,Modifier.size(18.dp))})
                        }
                        }
                        if (wide) Text(musicSortCaption(sort), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Box {
                            IconButton(onClick = { sortMenu = true }) {
                                Icon(Icons.Rounded.Sort, contentDescription = "Сортировка треков")
                            }
                            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                MusicLibrarySort.entries.forEach { option ->
                                    DropdownMenuItem(text = { Text(musicSortLabel(option), color = if (sort == option) com.domenota.medialoader.ui.theme.musicPlaybackAccent else MaterialTheme.colorScheme.onSurface) },
                                        onClick = { sort = option; sortMenu = false })
                                }
                            }
                        }
                    }
                    }
                    Box(Modifier.padding(horizontal = 17.dp)) { LibraryStatus(loading || !browse.usable || (needsRows && !browseRows.usable), message, error,
                        tab == 0 && displayed.isEmpty(), if (favoritesOnly) "В избранном пока нет треков" else "Нет аудиофайлов") }
                }
            }

            if (tab == 1 && playlist == null) {
                if (listeningEnabled) {
                    if(onResumeSelection!=null) item(key="resume-music-card"){ResumeMusicCard(resumeSessions.size){resumeOpen=true}}
                    item(key="recent-music-card") { RecentMusicCard { recentOpen=true } }

                    item(key="frequent-playlist") { FrequentListeningCard(visibleSelection.ranked.size.takeIf{listeningHasData},listeningPeriod) { frequentOpen=true } }
                    listOf(SmartMusicPage.FORGOTTEN,SmartMusicPage.MIX).forEach { page -> item(key="smart-${page.name}") { SmartMusicCard(page){smartPage=page} } }
                    if(onOpenCollections!=null)item(key="music-collections"){
                        MusicOverviewCard("Коллекции","",Icons.Rounded.CollectionsBookmark,"music-collections",onOpenCollections)
                    }
                    item(key="smart-${SmartMusicPage.STATS.name}"){SmartMusicCard(SmartMusicPage.STATS){smartPage=SmartMusicPage.STATS}}
                }
                if(!listeningEnabled) item(key="recent-music-card") { RecentMusicCard { recentOpen=true } }
                item(key = "playlists-title") { Text("Мои плейлисты", Modifier.padding(horizontal = 17.dp, vertical = 15.dp), fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
                items(playlists.chunked(2), key = { it.first().id }) { pair ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 17.dp).padding(bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        pair.forEach { entry ->
                            Column(Modifier.weight(1f).clickable { selectedId = entry.id }) {
                                PlaylistCover(entry, available, Modifier.fillMaxWidth().aspectRatio(1.25f))
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(entry.name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(RussianCounts.tracks(entry.trackIds.size), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    PlaylistActions(entry, { renameTarget = entry }, { deleteTarget = entry })
                                }
                            }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                item(key = "playlist-create") {
                    OutlinedButton({ creating = true }, Modifier.fillMaxWidth().padding(horizontal = 17.dp),
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) {
                        Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(8.dp)); Text("Создать плейлист")
                    }
                    if (playlists.isEmpty()) Text("Пока нет плейлистов", Modifier.padding(17.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                item(key = "music-folders-title") {
                    Row(Modifier.fillMaxWidth().padding(start = 17.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("Папки с музыкой", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        Box {
                            IconButton(onClick = { folderSortMenu = true }) {
                                Icon(Icons.Rounded.Sort, "Сортировка папок",
                                    tint = com.domenota.medialoader.ui.theme.musicPlaybackAccent)
                            }
                            DropdownMenu(expanded = folderSortMenu, onDismissRequest = { folderSortMenu = false }) {
                                MusicFolderSort.entries.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option.label) },
                                        leadingIcon = { if (folderSort == option) Icon(Icons.Rounded.Check, null,
                                            tint = com.domenota.medialoader.ui.theme.musicPlaybackAccent) },
                                        onClick = { setFolderSort(option); folderSortMenu = false })
                                }
                            }
                        }
                        MusicFolderViewToggle(foldersGrid, ::setFoldersGrid)
                    }
                }
                val visibleFolders = sortedFolderKeys.filter { it !in excludedFolders }
                if (foldersGrid) {
                    items(visibleFolders.chunked(2), key = { "folder-grid:${it.first()}" }) { pair ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 17.dp, vertical = 5.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            pair.forEach { folder ->
                                Surface(Modifier.weight(1f).clickable { folderOpen = folder },
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(16.dp)) {
                                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Icon(Icons.Rounded.Folder, null, Modifier.size(32.dp),
                                            tint = MaterialTheme.colorScheme.primary)
                                        Text(MusicFolderRules.title(folder), fontWeight = FontWeight.SemiBold,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(RussianCounts.tracks(folders[folder].orEmpty().size), fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                    }
                                }
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                } else items(visibleFolders, key = { "folder:$it" }) { folder ->
                    MusicFolderListRow(MusicFolderRules.title(folder), RussianCounts.tracks(folders[folder].orEmpty().size)) { folderOpen = folder }
                }
                if (folders.keys.any { it in excludedFolders }) {
                    item(key = "excluded-folders") {
                        TextButton(onClick = { showExcluded = !showExcluded }) { Text("Исключённые папки") }
                    }
                    if (showExcluded) items(sortedFolderKeys.filter { it in excludedFolders },
                        key = { "excluded:$it" }) { folder ->
                        TextButton(onClick = { folderOpen = folder }) { Text(MusicFolderRules.title(folder), color = MaterialTheme.colorScheme.onSurface) }
                    }
                }

            } else {
                if (playlist != null) item(key = "playlist-summary") {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 19.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(17.dp)) {
                        PlaylistCover(playlist, available, Modifier.size(100.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            val minutes = browseRows.value.playlistMinutes
                            Text(RussianCounts.tracks(playlist.trackIds.size) + if (minutes > 0) ", $minutes минут" else "", fontSize = 13.sp)
                            Text("Музыка для тебя", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Button(enabled = displayed.isNotEmpty(), onClick = { displayed.firstOrNull()?.let { preview(it.id) } }, shape = RoundedCornerShape(18.dp)) {
                                Icon(Icons.Rounded.PlayArrow, null); Text("Слушать", fontSize = 13.sp)
                            }
                        }
                    }
                    OutlinedButton({ if (onAddPlaylistTracks != null) onAddPlaylistTracks(playlist.id) else addingPlaylist = playlist.id },
                        Modifier.fillMaxWidth().padding(horizontal = 17.dp, vertical = 12.dp),
                        shape = RoundedCornerShape(15.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) {
                        Icon(Icons.Rounded.Add, "Добавить треки"); Spacer(Modifier.width(8.dp)); Text("Добавить треки")
                    }
                    val missing = browseRows.value.playlistMissing.takeIf { browse.ready && browseRows.ready } ?: 0
                    if (missing > 0) Text("Недоступно треков: $missing. Записи сохранены в плейлисте.", Modifier.padding(horizontal = 17.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (tab == 0) {
                    sections.forEach { (letter, records) ->
                        item(key = "music-letter:$letter") {
                            Text(letter, Modifier.fillMaxWidth().padding(start = 17.dp, top = 15.dp, bottom = 7.dp),
                                color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                        }
                        items(records, key = { it.id }) { track ->
                            val id = track.id
                            MusicLibraryTrackRow(track, { if(selectedPublicIds.isNotEmpty()) toggleTrackSelection(id) else preview(id) },
                                id == currentPlayingId, playing,
                                selectionMode=selectedPublicIds.isNotEmpty(),
                                selected=id in selectedPublicIds,
                                onArtworkLongPress={ toggleTrackSelection(id) },
                                onToggleSelection={ toggleTrackSelection(id) }) {
                                MusicTrackActions(track, onEnqueue?.let { action -> { next -> action(id, next) } },
                                    { openTrack(id) }, { addTrack = id }, { onShare(id) }, { onDeleteFile(id) },
                                    null, null, null, MusicTrackVisibility.hidden(track, hiddenTrackKeys), { toggleHidden(track) },
                                    onCollection=onAddCollection?.let{action->{action(listOf(id))}},onPassport=onPassport?.let{action->{action(id)}},onRecognize=onRecognize?.let{action->{action(id)}})
                            }
                        }
                    }
                } else items(displayedIds, key = { it }) { id ->
                    val track = available[id] ?: browse.value.byId[id]
                    if (track == null && playlist != null) UnavailablePlaylistTrackRow((browseRows.value.ordinals[id] ?: 0) + 1) {
                        mutate { onRemoveTrack(playlist.id, id) }
                    } else if (track != null) MusicLibraryTrackRow(track,
                        { if(selectedPublicIds.isNotEmpty()) toggleTrackSelection(id) else preview(id) },
                        id == currentPlayingId, playing,
                        selectionMode=selectedPublicIds.isNotEmpty(), selected=id in selectedPublicIds,
                        onArtworkLongPress={toggleTrackSelection(id)}, onToggleSelection={toggleTrackSelection(id)}) {
                        MusicTrackActions(track, onEnqueue?.let { action -> { next -> action(id, next) } }, { openTrack(id) }, { addTrack = id }, { onShare(id) }, { onDeleteFile(id) },
                            playlist?.let { entry -> { mutate { onRemoveTrack(entry.id, id) } } },
                            playlist?.takeIf { (browseRows.value.ordinals[id] ?: 0) > 0 }?.let { entry -> { mutate { onMoveTrack(entry.id, id, -1) } } },
                            playlist?.takeIf { (browseRows.value.ordinals[id] ?: 0) < it.trackIds.lastIndex }?.let { entry -> { mutate { onMoveTrack(entry.id, id, 1) } } }, MusicTrackVisibility.hidden(track, hiddenTrackKeys), { toggleHidden(track) },
                                    onCollection=onAddCollection?.let{action->{action(listOf(id))}},onPassport=onPassport?.let{action->{action(id)}},onRecognize=onRecognize?.let{action->{action(id)}})
                    }
                }
            }
        }
        if (tab == 0 || playlist != null) MusicFastScroll(contentListState, fastLabels, Modifier.align(Alignment.CenterEnd))
        }
        if (selectedPublicIds.isNotEmpty()) {
            Surface(Modifier.fillMaxWidth().testTag("music-selection-bottom"),
                tonalElevation = 5.dp, color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.fillMaxWidth().padding(horizontal=12.dp, vertical=4.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text("Выбрано: ${selectedPublicIds.size}", Modifier.weight(1f),fontWeight=FontWeight.SemiBold)
                        IconButton({selectedTrackIds=emptySet()}) { Icon(Icons.Rounded.Close,"Отменить выбор") }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically,
                        horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick={
                        // No stale ID or hidden row can reach the collection picker.
                        val confirmed=selectedTrackIds.intersect(selectableIds).toList()
                        if(confirmed.isNotEmpty() && onAddCollection!=null) {
                            onAddCollection(confirmed)
                            selectedTrackIds=emptySet()
                        }
                    },enabled=onAddCollection!=null,
                        modifier=Modifier.testTag("music-selected-collection")) {
                        Icon(Icons.Rounded.CollectionsBookmark,null,Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Коллекция")
                    }
                    if (onSimpleArchive != null) TextButton({ onSimpleArchive(selectedPublicIds.toList()) },
                        modifier=Modifier.testTag("music-selected-archive")) {
                        com.domenota.medialoader.ui.components.ArchiveActionIcon(null)
                        Spacer(Modifier.width(6.dp)); Text("В архив")
                    }
                    }
                }
            }
        }
        if (tabOverride == null && playlist == null && selectedPublicIds.isEmpty())
            LibraryBottomSwitcher(AppMode.MUSIC, tab, ::selectTab)
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
            if (playlists.isEmpty()) Text("Сначала создай плейлист")
            playlists.forEach { entry -> TextButton(enabled = !busy && id !in entry.trackIds, onClick = { mutate { onAddTracks(entry.id, listOf(id)); addTrack = null } }) { Text(entry.name) } }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = { TextButton({ addTrack = null }) { Text("Закрыть") } }) }
}

/** Large library work is derived once on a worker. These objects share immutable
 * row references with the primary catalog and are not another database. */
internal class MusicBrowseCatalog(val tracks:List<DownloadEntity>,val available:Map<String,DownloadEntity>,
    val folders:Map<String?,List<DownloadEntity>>) {
    val allRows=available.values.toList()
    val byId=tracks.associateBy{it.id}
    fun retainAllowed(allowed: (DownloadEntity) -> Boolean): MusicBrowseCatalog {
        if (available.values.all(allowed) && tracks.all(allowed)) return this
        return MusicBrowseCatalog(tracks.filter(allowed),available.filterValues(allowed),
            folders.mapValues { (_, rows) -> rows.filter(allowed) }.filterValues { it.isNotEmpty() })
    }
    companion object {
        val EMPTY=MusicBrowseCatalog(emptyList(),emptyMap(),emptyMap())
        fun build(items:List<DownloadEntity>,all:List<DownloadEntity>,deviceFolders:Map<String,String>):MusicBrowseCatalog {
            val tracks=items.filter(::availableAudio).distinctBy{it.id}
            return MusicBrowseCatalog(tracks,all.filter(::availableAudio).associateBy{it.id},
                tracks.groupBy{MusicFolderRules.key(it,deviceFolders)}.filterKeys{it!=null})
        }
    }
}
private data class MusicBrowseFilter(val access:Any,val tab:Int,val playlist:String?,val search:String,val sort:MusicLibrarySort,
    val favorites:Boolean,val hidden:Set<String>,val excluded:Set<String>)
internal class MusicBrowseRows(val displayed:List<DownloadEntity>,val sections:Map<String,List<DownloadEntity>>,
    val displayedIds:List<String>,val queue:List<String>,val fastLabels:List<String>,val playlistMinutes:Long,
    val playlistMissing:Int,val ordinals:Map<String,Int>) {
    fun retainAllowed(sort: MusicLibrarySort, allowed: (DownloadEntity) -> Boolean): MusicBrowseRows {
        val kept = displayed.filter(allowed)
        if (kept.size == displayed.size) return this
        val ids = kept.map { it.id }; val idSet = ids.toHashSet()
        val groups = sections.mapValues { (_, rows) -> rows.filter(allowed) }.filterValues { it.isNotEmpty() }
        val labels = if (groups.isEmpty()) listOf("") + kept.map { musicGroupLetter(musicDisplayTitle(it)) }
            else listOf("") + groups.flatMap { (label, rows) ->
                val caption = if (sort == MusicLibrarySort.DATE || sort == MusicLibrarySort.DATE_OLDEST) label.take(7) else label
                listOf(caption) + rows.map { caption }
            }
        return MusicBrowseRows(kept,groups,displayedIds.filter { it in idSet },queue.filter { it in idSet },
            labels,kept.sumOf { (it.durationMs ?: 0) / 60_000 },playlistMissing,ordinals.filterKeys { it in idSet })
    }
    companion object {
        val EMPTY=MusicBrowseRows(emptyList(),emptyMap(),emptyList(),emptyList(),listOf(""),0,0,emptyMap())
        fun build(catalog:MusicBrowseCatalog,playlist:MediaPlaylist?,tab:Int,search:String,sort:MusicLibrarySort,
            favoritesOnly:Boolean,favoriteIds:Set<String>,hidden:Set<String>,excluded:Set<String>,deviceFolders:Map<String,String>):MusicBrowseRows {
            val available=catalog.available
            val source=when {
                tab!=0->playlist?.trackIds?.mapNotNull(available::get).orEmpty()
                favoritesOnly->MusicFavoritesPolicy.select(catalog.allRows,favoriteIds)
                else->catalog.tracks.filter{MusicFolderRules.visible(it,excluded,deviceFolders)&&!MusicTrackVisibility.hidden(it,hidden)}
            }
            val filtered=source.filter{matchesMusicSearch(it,search)}
            val displayed=if(tab==0)sortMusicRows(filtered,sort) else filtered
            val sections=if(tab==0)displayed.groupBy{musicGroupHeading(it,sort)} else emptyMap()
            val queue=displayed.map{it.id}
            val ids=if(playlist==null)queue else playlist.trackIds.filter{id->available[id]?.let{matchesMusicSearch(it,search)}?:search.isBlank()}
            val labels=if(tab==0)listOf("")+sections.flatMap{(label,records)->val caption=if(sort==MusicLibrarySort.DATE||sort==MusicLibrarySort.DATE_OLDEST)label.take(7) else label;listOf(caption)+records.map{caption}}
                else listOf("")+if(playlist!=null)listOf("")+ids.map{id->available[id]?.let{musicGroupLetter(musicDisplayTitle(it))}.orEmpty()} else emptyList()
            val minutes=playlist?.trackIds?.mapNotNull(available::get)?.sumOf{(it.durationMs?:0)/60_000}?:0L
            val missing=playlist?.trackIds?.count{it !in available}?:0
            val ordinals=buildMap{playlist?.trackIds?.forEachIndexed{index,id->if(id !in this)put(id,index)}}
            return MusicBrowseRows(displayed,sections,ids,queue,labels,minutes,missing,ordinals)
        }
    }
}
private fun musicRecentRows(items:List<DownloadEntity>,hidden:Set<String>,excluded:Set<String>,folders:Map<String,String>)=items.filter{
    availableAudio(it)&&!it.savedUri.isNullOrBlank()&&!MusicTrackVisibility.hidden(it,hidden)&&MusicFolderRules.visible(it,excluded,folders)
}.distinctBy{it.id}

private fun availableAudio(item: DownloadEntity) = item.state == DownloadState.COMPLETED && !item.hidden && !item.isPrivate && item.mediaType == MediaType.AUDIO
private fun matchesMusicSearch(track: DownloadEntity, search: String) = listOfNotNull(track.originalName, track.recognizedArtist,
    track.recognizedTitle, track.sourceAudioArtist, track.sourceAudioTitle).any { it.contains(search, true) }

@Composable
private fun PlaylistCover(playlist: MediaPlaylist, available: Map<String, DownloadEntity>, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer), contentAlignment = Alignment.Center) {
        val cover = playlist.trackIds.firstNotNullOfOrNull(available::get)
        if (cover != null) DownloadThumbnail(cover.savedUri, cover.recognitionArtworkUrl ?: cover.sourceAudioArtworkUrl ?: cover.previewUrl,
            cover.toUi().kind, Modifier.fillMaxSize())
        else Icon(Icons.Rounded.LibraryMusic, null, Modifier.size(32.dp), MaterialTheme.colorScheme.onSurface)
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
    com.domenota.medialoader.ui.components.ReportDrawerOverlay(menu)
    Box {
        IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, "Действия с плейлистом ${playlist.name}") }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem({ Text("Переименовать плейлист") }, { menu = false; onRename() }, leadingIcon = { Icon(com.domenota.medialoader.ui.components.MediaEditOutline, null) })
            DropdownMenuItem({ Text("Удалить плейлист", color = MaterialTheme.colorScheme.error) }, { menu = false; onDelete() },
                leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) })
        }
    }
}

@Composable
internal fun MusicTrackActions(track: DownloadEntity, onEnqueue: ((Boolean) -> Unit)?, onOpen: () -> Unit, onAdd: () -> Unit, onShare: () -> Unit, onDelete: () -> Unit,
                              onRemove: (() -> Unit)?, onUp: (() -> Unit)?, onDown: (() -> Unit)?, hidden: Boolean = false, onToggleHidden: (() -> Unit)? = null,
                              onExclude: (() -> Unit)? = null, favorite: Boolean = false, onFavorite: (() -> Unit)? = null,
                              onCollection: (() -> Unit)? = null,onPassport: (() -> Unit)? = null,onRecognize:(()->Unit)?=null) {
    var menu by remember { mutableStateOf(false) }
    var properties by remember { mutableStateOf(false) }
    com.domenota.medialoader.ui.components.ReportDrawerOverlay(menu||properties)
    if (properties) MusicFilePropertiesDialog(track) { properties = false }
    Box {
        IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, "Действия: ${track.originalName}") }
        DropdownMenu(menu, { menu = false }) {
            if (onEnqueue != null) {
                DropdownMenuItem({ Text("Играть следующим") }, { menu = false; onEnqueue(true) }, leadingIcon = { Icon(Icons.Rounded.SkipNext, null) })
                DropdownMenuItem({ Text("Добавить в конец очереди") }, { menu = false; onEnqueue(false) }, leadingIcon = { Icon(Icons.Rounded.QueueMusic, null) })
            }
            DropdownMenuItem({ Text("Добавить в плейлист") }, { menu = false; onAdd() }, leadingIcon = { Icon(Icons.Rounded.PlaylistAdd, null) })
            if (onFavorite != null) DropdownMenuItem({Text(if(favorite) "Убрать из избранного" else "В избранное")},{menu=false;onFavorite()},leadingIcon={Icon(if(favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,null)})
            if (onExclude != null) DropdownMenuItem({Text("Исключить из подборки")},{menu=false;onExclude()},leadingIcon={Icon(Icons.Rounded.PlaylistRemove,null)})
            if (onRemove != null) DropdownMenuItem({ Text("Убрать из плейлиста") }, { menu = false; onRemove() }, leadingIcon = { Icon(Icons.Rounded.PlaylistRemove, null) })
            if (onUp != null) DropdownMenuItem({ Text("Выше") }, { menu = false; onUp() }, leadingIcon = { Icon(Icons.Rounded.ArrowUpward, null) })
            if (onDown != null) DropdownMenuItem({ Text("Ниже") }, { menu = false; onDown() }, leadingIcon = { Icon(Icons.Rounded.ArrowDownward, null) })
            if (onToggleHidden != null) DropdownMenuItem({ Text(if (hidden) "Отобразить в общем списке" else "Скрыть") },
                { menu = false; onToggleHidden() }, leadingIcon = { Icon(if (hidden) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff, null) })
            if(onCollection!=null)DropdownMenuItem({Text("В коллекцию")},{menu=false;onCollection()},leadingIcon={Icon(Icons.Rounded.CollectionsBookmark,null)})
            if(onRecognize!=null&&!track.isPrivate&&!track.hidden)DropdownMenuItem({Text("Распознать композицию")},{menu=false;onRecognize()},leadingIcon={TrackRecognitionIcon(null)})
            if(onPassport!=null)DropdownMenuItem({Text("Паспорт файла")},{menu=false;onPassport()},leadingIcon={Icon(Icons.Rounded.Info,null)})
            DropdownMenuItem({ Text("Свойства") }, { menu = false; properties = true }, leadingIcon = { Icon(Icons.Rounded.Info, null) })
            DropdownMenuItem({ Text("Поделиться") }, { menu = false; onShare() }, leadingIcon = { Icon(Icons.Rounded.Share, null) })
            DropdownMenuItem({ Text("Удалить файл", color = MaterialTheme.colorScheme.error) }, { menu = false; onDelete() },
                leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) })
        }
    }
}

@Composable
private fun musicSortCaption(sort: MusicLibrarySort): androidx.compose.ui.text.AnnotatedString =
    androidx.compose.ui.text.AnnotatedString(musicSortLabel(sort))

private fun musicSortLabel(sort: MusicLibrarySort): String = when (sort) {
    MusicLibrarySort.DATE -> "Сначала новые"
    MusicLibrarySort.DATE_OLDEST -> "Сначала старые"
    else -> sort.label
}

private class MusicAnalyticsRows(val rows: Map<String,DownloadEntity>, val keys: Map<String,Set<String>>, val candidates: List<SmartTrackCandidate>,
    val displayEpoch: Any=Any(), val rankDigest: String=ListeningProjectionCodec.rankDigest(keys),
    val candidateDigest: String=ListeningProjectionCodec.candidateDigest(candidates)) {
    fun withEpoch(epoch:Any)=MusicAnalyticsRows(rows,keys,candidates,epoch,rankDigest,candidateDigest)
    companion object { val EMPTY = MusicAnalyticsRows(emptyMap(),emptyMap(),emptyList()) }
}

@Composable
internal fun MusicFolderViewToggle(grid:Boolean, onChange:(Boolean)->Unit) {
    IconButton(onClick={onChange(!grid)}) {
        Icon(if(grid) Icons.AutoMirrored.Rounded.ViewList else Icons.Rounded.GridView,
            if(grid) "Показать списком" else "Показать сеткой", tint=MaterialTheme.colorScheme.primary)
    }
}

@Composable
internal fun MusicFolderListRow(title:String,count:String,onOpen:()->Unit) {
    Surface(Modifier.fillMaxWidth().padding(horizontal=17.dp,vertical=4.dp).clickable(onClick=onOpen),
        color=MaterialTheme.colorScheme.surfaceVariant,shape=RoundedCornerShape(14.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min=56.dp).padding(horizontal=12.dp,vertical=8.dp),
            verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Rounded.Folder,null,Modifier.size(28.dp),tint=MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                Text(title,fontWeight=FontWeight.SemiBold,color=MaterialTheme.colorScheme.primary,maxLines=2,overflow=TextOverflow.Ellipsis)
                Text(count,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Rounded.ChevronRight,null,tint=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
