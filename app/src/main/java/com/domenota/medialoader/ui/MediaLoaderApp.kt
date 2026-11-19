package com.domenota.medialoader.ui

import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.ScaffoldDefaults

import com.domenota.medialoader.data.library.*
import com.domenota.medialoader.data.catalog.visibility.*
import com.domenota.medialoader.ui.components.AppModeTitle
import com.domenota.medialoader.ui.components.LibraryRefreshShield
import com.domenota.medialoader.ui.components.addressedLibraryProjection
import com.domenota.medialoader.core.logging.LibraryProjectionKind
import com.domenota.medialoader.ui.screens.MusicBrowseCatalog
import com.domenota.medialoader.ui.components.LibraryModeHeader
import com.domenota.medialoader.ui.components.LibraryTheme
import com.domenota.medialoader.ui.components.LibraryBottomSwitcher
import com.domenota.medialoader.ui.screens.gallery.DeviceAlbum
import com.domenota.medialoader.ui.screens.gallery.GalleryBrowseInventory
import com.domenota.medialoader.ui.screens.gallery.GalleryMediaViewer
import com.domenota.medialoader.ui.screens.gallery.GalleryViewerState
import com.domenota.medialoader.ui.screens.gallery.GalleryLibraryState
import com.domenota.medialoader.ui.screens.gallery.GalleryAlbumRequest
import com.domenota.medialoader.ui.screens.music.PlaylistTracksScreen
import com.domenota.medialoader.ui.components.LibraryMediaDeleteHost
import com.domenota.medialoader.ui.components.MediaDeletionTarget
import com.domenota.medialoader.ui.components.MediaManagementAccessPrompt
import com.domenota.medialoader.ui.components.label
import com.domenota.medialoader.ui.screens.GalleryModeScreen
import com.domenota.medialoader.ui.screens.MusicModeScreen
import com.domenota.medialoader.ui.screens.organization.*
import com.domenota.medialoader.data.MediaRepository
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.MusicNote
import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.domenota.medialoader.ui.components.AppNotice
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import com.domenota.medialoader.ui.components.NeutralTextButton as TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.collectAsState
import com.domenota.medialoader.ui.screens.PrivateScreen
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import com.domenota.medialoader.ui.navigation.StableLibraryNavHost
import com.domenota.medialoader.ui.navigation.LibraryRouteContent
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.domenota.medialoader.BuildConfig
import com.domenota.medialoader.core.provider.InstagramLinkParser
import com.domenota.medialoader.core.provider.RutubeLinkParser
import com.domenota.medialoader.core.provider.VkLinkParser
import com.domenota.medialoader.core.provider.YouTubeLinkParser
import com.domenota.medialoader.core.recognition.MusicRecognitionMode
import com.domenota.medialoader.core.recognition.MusicLookupPolicy
import com.domenota.medialoader.data.search.FullSongSource
import com.domenota.medialoader.core.storage.StorageNaming
import com.domenota.medialoader.data.storage.SavedFileStatus
import com.domenota.medialoader.core.logging.AppLog
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import androidx.compose.runtime.rememberCoroutineScope
import com.domenota.medialoader.data.storage.SavedFileStatusCache
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import com.domenota.medialoader.ui.model.AnalysisUiState
import com.domenota.medialoader.ui.model.BatchFileNames
import com.domenota.medialoader.ui.model.VkDownloadGroup
import com.domenota.medialoader.ui.model.toPreviewUi
import com.domenota.medialoader.ui.model.toUi
import com.domenota.medialoader.ui.navigation.ViewerQueueCache
import com.domenota.medialoader.ui.navigation.AppDestination
import com.domenota.medialoader.ui.navigation.BottomTabPolicy
import com.domenota.medialoader.ui.navigation.BROWSER_ROUTE
import com.domenota.medialoader.ui.navigation.PREVIEW_ROUTE
import com.domenota.medialoader.ui.navigation.BULK_ROUTE
import com.domenota.medialoader.ui.navigation.MEDIA_VIEWER_ROUTE
import com.domenota.medialoader.ui.navigation.VIDEO_EDITOR_ROUTE
import com.domenota.medialoader.ui.navigation.AUDIO_EDITOR_ROUTE
import com.domenota.medialoader.ui.navigation.PHOTO_EDITOR_ROUTE
import com.domenota.medialoader.ui.screens.DownloadsScreen
import com.domenota.medialoader.ui.screens.HomeScreen
import com.domenota.medialoader.ui.screens.PreviewScreen
import com.domenota.medialoader.ui.screens.SettingsScreen
import com.domenota.medialoader.ui.screens.DiagnosticsScreen
import com.domenota.medialoader.ui.screens.BulkDownloadScreen
import com.domenota.medialoader.ui.screens.BrowserSource
import com.domenota.medialoader.ui.screens.SourceBrowserScreen
import com.domenota.medialoader.ui.screens.MediaViewerScreen
import com.domenota.medialoader.ui.screens.VideoEditorScreen
import com.domenota.medialoader.ui.screens.AudioEditorScreen
import com.domenota.medialoader.ui.screens.PhotoEditorScreen
import com.domenota.medialoader.ui.screens.PhoneTrashController
import com.domenota.medialoader.ui.screens.VkCommunityScreen
import com.domenota.medialoader.ui.components.AudioMiniPlayer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Restore

private const val PRIVATE_ROUTE = "private"
private const val PLAYLIST_ADD_ROUTE = "playlist-add/{playlistId}"

/** The compact transfer panel belongs to Downloader chrome only. Gallery,
 * Music and media viewers stay usable while the dataSync service runs. */
internal fun visibilityMiniPanelHostAllowed(mode: AppMode, route: String?): Boolean =
    mode == AppMode.DOWNLOADER && route in setOf(
        AppDestination.Home.route, AppDestination.Downloads.route, AppDestination.Settings.route)


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun fileNameFieldColors() = if (MaterialTheme.colorScheme.background.luminance() > 0.5f) {
    OutlinedTextFieldDefaults.colors(
        focusedTextColor = MaterialTheme.colorScheme.onSurface,
        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
        focusedBorderColor = MaterialTheme.colorScheme.primary,
        unfocusedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
        focusedLabelColor = MaterialTheme.colorScheme.primary,
        unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )
} else {
    OutlinedTextFieldDefaults.colors()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaLoaderApp(
    shortcutMode: AppMode? = null,
    shortcutGeneration: Int = 0,
    shortcutDuplicates: Boolean = false,
    onShortcutConsumed: () -> Unit = {},
    sharedText: String? = null,
    sharedTextGeneration: Int = 0,
    onSharedTextConsumed: () -> Unit = {},
    currentPlayerRequest: Int = 0,
    onCurrentPlayerRequestConsumed: (Int) -> Unit = {},
    viewModel: MediaLoaderViewModel = viewModel(),
) {
    val context = LocalContext.current
    val navController = rememberNavController()
    val clipboardManager = LocalClipboardManager.current
    val lifecycleOwner = LocalLifecycleOwner.current
    com.domenota.medialoader.ui.components.NoticeForeground()
    val fileActionScope = rememberCoroutineScope()
    val availabilityCache = remember(viewModel) { SavedFileStatusCache(viewModel::savedFileStatus) }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    var pendingUrl by rememberSaveable { mutableStateOf("") }
    var downloadsScrollToTopRequest by rememberSaveable { mutableIntStateOf(0) }
    var lastImportedClipboard by rememberSaveable { mutableStateOf("") }
    var dismissedClipboard by rememberSaveable { mutableStateOf("") }
    var checkedClipboardAtLaunch by rememberSaveable { mutableStateOf(false) }
    var showPicker by rememberSaveable { mutableStateOf(false) }
    val uiPrefs = remember(context) { context.getSharedPreferences("ui", 0) }
    val libraryModel: LibraryViewModel = viewModel()
    val mediaRepository=remember(context){MediaRepository.get(context)}
    val organization=remember(mediaRepository){mediaRepository.organization}
    // Reversible home-only presentation switch. Stored library data and routes remain intact.
    val showHomeOrganization = remember(uiPrefs) {
        uiPrefs.getBoolean(HomeOrganizationPolicy.PREFERENCE, HomeOrganizationPolicy.DEFAULT_VISIBLE)
    }
    val reviewObserved = HomeOrganizationPolicy.observeReview(currentRoute, showHomeOrganization)
    val reviewFlow = remember(organization, reviewObserved) {
        if (reviewObserved) organization.review else kotlinx.coroutines.flow.flowOf(emptyList())
    }
    val reviewEntries by reviewFlow.collectAsStateWithLifecycle(initialValue=emptyList())
    var collectionInitial by rememberSaveable{mutableStateOf<String?>(null)}
    var collectionDetailId by rememberSaveable{mutableStateOf<String?>(null)}
    var collectionPickerRequest by remember { mutableStateOf(CollectionCatalogRequest.NONE) }
    var collectionLegacyRequest by remember { mutableStateOf(CollectionCatalogRequest.NONE) }
    val collectionCatalogRequest = maxOf(collectionPickerRequest, collectionLegacyRequest)
    val collectionViewerActive = currentRoute == MEDIA_VIEWER_ROUTE && backStackEntry?.savedStateHandle?.get<Boolean>("collection_viewer") == true
    val collectionRouteActive = currentRoute == "collections" || collectionViewerActive
    var collectionCreate by rememberSaveable{mutableStateOf(false)}
    var collectionAudioOnly by rememberSaveable{mutableStateOf(false)}
    var collectionAddIds by remember{mutableStateOf<List<String>>(emptyList())}
    var collectionLink by rememberSaveable{mutableStateOf<String?>(null)}
    DisposableEffect(libraryModel, currentRoute) {
        libraryModel.holdAutomaticMediaIndexing(currentRoute == VIDEO_EDITOR_ROUTE ||
            currentRoute == AUDIO_EDITOR_ROUTE || currentRoute == PHOTO_EDITOR_ROUTE)
        onDispose { libraryModel.holdAutomaticMediaIndexing(false) }
    }
    val libraryPreferences = remember(context) { LibraryPreferences(context) }
    var appMode by rememberSaveable { mutableStateOf(shortcutMode ?: libraryPreferences.mode) }
    val collectionsObserved = collectionViewerActive || HomeOrganizationPolicy.observeCollections(currentRoute, appMode, showHomeOrganization)
    val collectionsFlow = remember(organization, collectionsObserved) {
        if (collectionsObserved) organization.collections else kotlinx.coroutines.flow.flowOf(emptyList())
    }
    val collectionsState by collectionsFlow.collectAsStateWithLifecycle(initialValue=null as List<MediaCollection>?)
    val collections = collectionsState.orEmpty()
    val collectionsLoaded = collectionsObserved && collectionsState != null
    var privateNavigation by remember { mutableStateOf(false) }
    var duplicateRequest by rememberSaveable { mutableIntStateOf(0) }
    var galleryDateRequest by rememberSaveable { mutableIntStateOf(0) }
    var galleryTab by rememberSaveable { mutableIntStateOf(0) }
    var galleryTopRequest by rememberSaveable { mutableIntStateOf(0) }
    var galleryDetailOpen by remember { mutableStateOf(false) }
    var batchPhotoIds by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var galleryAlbumRequest by remember { mutableStateOf<GalleryAlbumRequest?>(null) }
    var openingAlbumKey by remember { mutableStateOf<String?>(null) }
    var openingPlaylistId by remember { mutableStateOf<String?>(null) }
    var musicTab by rememberSaveable { mutableIntStateOf(0) }
    var musicTopRequest by rememberSaveable { mutableIntStateOf(0) }
    var musicDetailOpen by remember { mutableStateOf(false) }
    var libraryPreviewJob by remember { mutableStateOf<Job?>(null) }
    var gallerySource by rememberSaveable { mutableStateOf(libraryPreferences.source(AppMode.GALLERY)) }
    var musicLibrarySource by rememberSaveable { mutableStateOf(libraryPreferences.source(AppMode.MUSIC)) }
    val librarySource = if (appMode == AppMode.GALLERY) gallerySource else musicLibrarySource
    var librarySearchVisible by rememberSaveable(appMode, librarySource) { mutableStateOf(false) }
    val phoneGallery by libraryModel.gallery.collectAsStateWithLifecycle()
    val phoneMusic by libraryModel.music.collectAsStateWithLifecycle()
    com.domenota.medialoader.ui.components.ScreenPerformanceProbe(
        "${currentRoute ?: "initial"}:$appMode:$librarySource",
        if (appMode == AppMode.GALLERY && librarySource == LibrarySource.PHONE)
            phoneGallery.loaded && !phoneGallery.requiresValidation
        else if (appMode == AppMode.MUSIC && librarySource == LibrarySource.PHONE)
            phoneMusic.loaded && !phoneMusic.requiresValidation
        else true)

    val libraryIndex by libraryModel.index.collectAsStateWithLifecycle()
    val visibilityGuard by libraryModel.visibilityGuard.collectAsStateWithLifecycle()
    val nomediaRegistry = remember(mediaRepository) { mediaRepository.nomediaFolders }
    val nomediaSnapshot by nomediaRegistry.snapshot.collectAsStateWithLifecycle()
    val nomediaFolderStatuses = remember(nomediaSnapshot) {
        nomediaSnapshot.folders.keys.associateWith { nomediaRegistry.statusText(it).orEmpty() }
    }
    val visibilityAccess=com.domenota.medialoader.ui.components.rememberFolderVisibilityAccessHost()
    val visibilityWork=remember(mediaRepository) { mediaRepository.folderVisibilityWork }
    val visibilityWorkState by visibilityWork.state.collectAsStateWithLifecycle()
    val postTransferCatalogStatus by libraryModel.postTransferCatalogStatus.collectAsStateWithLifecycle()
    val androidApprovalTicket by visibilityWork.pendingAndroidApproval.collectAsStateWithLifecycle()
    var visibilityWorkExpanded by rememberSaveable { mutableStateOf(true) }
    // The user-dismissed final result must not reappear on Activity rotation.
    var visibilityWorkDismissed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(visibilityWorkState.phase) {
        if (visibilityWorkState.phase == VisibilityExecutionPhase.PREPARING) {
            visibilityWorkExpanded=true
            visibilityWorkDismissed=false
        }
    }
    // Service requests never launch system consent from background. The Activity
    // becomes RESUMED first, then its own ActivityResultLauncher makes the request.
    LaunchedEffect(androidApprovalTicket?.id,lifecycleOwner) {
        val ticket=androidApprovalTicket ?: return@LaunchedEffect
        lifecycleOwner.lifecycle.currentStateFlow.first {
            it.isAtLeast(Lifecycle.State.RESUMED)
        }
        if(!visibilityWork.claimAndroidApproval(ticket.id)) return@LaunchedEffect
        try {
            val permitted=visibilityAccess.approve(ticket.payload)
            visibilityWork.resolveAndroidApproval(ticket.id,permitted)
        } catch(cancelled:CancellationException) {
            visibilityWork.rejectAndroidApprovalOnActivityLoss(ticket.id)
            throw cancelled
        } catch(error:Exception) {
            visibilityWork.rejectAndroidApprovalOnActivityLoss(ticket.id)
            AppLog.w("FolderVisibility","ANDROID_APPROVAL_ACTIVITY error=${error.javaClass.simpleName}")
        }
    }
    var visibilityBusy by remember { mutableStateOf(false) }
    var visibilityPreflight by remember { mutableStateOf<VisibilityPreflightSummary?>(null) }
    // The legacy blocking dialog is only for membership moves that still
    // execute from the screen. An ordinary folder hide/show transitions to
    // the new service dialog rather than flashing the legacy placeholder.
    var visibilityMembershipBusy by remember { mutableStateOf(false) }
    var visibilityResumeDismissed by remember { mutableStateOf(false) }
    suspend fun visibilityOperation(action:suspend ()->Unit) {
        check(!visibilityBusy && !visibilityWork.state.value.active) { "Перенос уже выполняется" }
        visibilityBusy=true
        try { visibilityAccess.ensure(); action() }
        finally { visibilityBusy=false }
    }
    // One explicit Android tree grant per physical directory. The virtual ML
    // folder may contain files elsewhere: .nomedia affects ALL physical children
    // of the chosen tree; never imply that only this virtual album is affected.
    var nomediaRequest by remember { mutableStateOf<Pair<String,Boolean>?>(null) }
    var nomediaPickerRequest by remember { mutableStateOf<Pair<String,Boolean>?>(null) }
    val nomediaTreePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val request=nomediaPickerRequest
        nomediaPickerRequest=null
        if (uri==null || request==null) {
            AppNotice.post(context,"Выбор папки отменён. Состояние не изменено")
        } else try {
            val (folder,hide)=request
            if (!hide) check(nomediaRegistry.snapshot.value.folders[folder]?.treeUri==uri.toString()) {
                "Выбран другой каталог. Разрешение не применено"
            }
            context.contentResolver.takePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            fileActionScope.launch {
                try {
                    val result=if(hide) nomediaRegistry.hide(folder,uri) else nomediaRegistry.show(folder)
                    AppNotice.post(context,if(result.excludedFromMl)
                        "Скрыто в ML. Статус индекса Android не подтверждён"
                    else if(result.markerState==NomediaMarkerState.FOREIGN_MARKER)
                        "Показано в ML. Чужой .nomedia сохранён"
                    else "Показано в ML. Медиаиндекс Android обновляется отдельно")
                } catch(cancelled:CancellationException) { throw cancelled }
                catch(error:Exception) { AppNotice.post(context,error.message ?: "Операция .nomedia не завершена") }
            }
        } catch(error:Exception) {
            AppNotice.post(context,error.message ?: "Доступ SAF не предоставлен")
        }
    }
    val toggleFolderVisibility:suspend (String,Boolean)->Unit={ folder,hidden ->
        check(!nomediaSnapshot.unknown) { "Реестр скрытия требует проверки" }
        check(folder !in (visibilityGuard?.policy?.snapshot?.hiddenFolders.orEmpty()) &&
            (visibilityGuard?.policy?.pendingFolder != folder)) { "Для старого физического архива сохранена только диагностика" }
        val current=nomediaSnapshot.folders[folder]
        check(if(hidden) current?.excludedFromMl != true else current?.excludedFromMl == true) {
            "Состояние папки изменилось. Повторите проверку"
        }
        nomediaRequest = folder to hidden
    }
    nomediaRequest?.let { (folder,hide) ->
        AlertDialog(onDismissRequest={nomediaRequest=null},
            title={Text(if(hide) "Скрытие папки без переноса" else "Показать папку")},
            text={Column {
                Text(if(hide)
                    "Скрытие внутри ML и .nomedia для выбранного каталога Android. Все исходные файлы остаются на месте."
                else "Папка снова появится в ML. Удаляется только созданный ML служебный .nomedia; чужой файл сохраняется.")
                Text(if(hide)
                    "Выбранный каталог и вложенные папки могут содержать и другие медиа. Xiaomi Gallery может обновиться не сразу."
                else "Прежние даты, имена и история загрузок не изменяются. Доступность старого MediaStore URI проверяется отдельно.")
            }},
            confirmButton={TextButton(onClick={
                nomediaRequest=null
                if(hide) {
                    nomediaPickerRequest=folder to true
                    try { nomediaTreePicker.launch(null) }
                    catch(error:Exception) {
                        nomediaPickerRequest=null
                        AppNotice.post(context,error.message ?: "Не удалось выбрать каталог")
                    }
                } else fileActionScope.launch {
                    try {
                        val result=nomediaRegistry.show(folder)
                        AppNotice.post(context,if(result.markerState == NomediaMarkerState.FOREIGN_MARKER)
                            "Показано в ML. Чужой .nomedia сохранён; Android может скрывать папку"
                        else "Показано в ML. Переиндексация Android может занять время")
                    } catch(cancelled:CancellationException) { throw cancelled }
                    catch(error:Exception) {
                        AppNotice.post(context,error.message ?: "Показ папки не завершён")
                        if(error.message?.contains("Доступ к папке отозван")==true) {
                            nomediaPickerRequest=folder to false
                            try { nomediaTreePicker.launch(null) }
                            catch(_:Exception) { nomediaPickerRequest=null }
                        }
                    }
                }
            }){Text(if(hide) "Выбрать каталог" else "Показать")}},
            dismissButton={TextButton(onClick={nomediaRequest=null}){Text("Отмена")}})
    }

    val visibilityProgress by mediaRepository.folderVisibility.status.collectAsStateWithLifecycle()
    if(visibilityBusy && visibilityMembershipBusy) AlertDialog(onDismissRequest={},confirmButton={},title={Text("Перенос файлов")},text={
        Text(when {
            visibilityProgress.preparing && visibilityProgress.total>0 -> "Подготовка: ${visibilityProgress.prepared} из ${visibilityProgress.total}"
            visibilityProgress.total>0 -> "Перенос: ${visibilityProgress.completed} из ${visibilityProgress.total}"
            else -> "Чтение и проверка плана переноса…"
        })
    })
    // Legacy journal remains physically intact. No old resume action is available.
    if(visibilityProgress.pending && !visibilityBusy && !visibilityResumeDismissed &&
        visibilityWorkState.phase == VisibilityExecutionPhase.IDLE) AlertDialog(
        onDismissRequest={visibilityResumeDismissed=true},
        title={Text("Сохранённый журнал переноса")},
        text={Text("Прежний перенос отключён. Данные и контрольные точки сохранены для диагностики. Автоматический возврат не запускается.")},
        confirmButton={TextButton(onClick={visibilityResumeDismissed=true}){Text("Закрыть")}},
    )

    val visibilityReady = visibilityGuard != null && visibilityGuard?.policy?.unknownPending == false
    val visibilityFolders = visibilityGuard?.policy?.excludedFolders.orEmpty()
    val folderRefs = if (visibilityReady && libraryIndex.visibility == visibilityGuard && FolderVisibilityStorageAccess.granted())
        libraryIndex.hiddenRefs else emptyList()
    val folderRefsById = remember(folderRefs) { folderRefs.associateBy { it.key } }
    val liveFolderRefs by rememberUpdatedState(folderRefs)
    fun canOpenFolderMedia(row: com.domenota.medialoader.core.database.DownloadEntity, folder: String? = null): Boolean =
        row.isPrivate || libraryModel.visibilityGuard.value?.canOpen(row,folder,liveFolderRefs) == true

    val libraryAudioState by libraryModel.audioPreview.state.collectAsStateWithLifecycle()
    val libraryPrivateKeys by libraryModel.privateKeys.collectAsStateWithLifecycle()
    val visibilityBlocked=remember(visibilityGuard){visibilityGuard?.blockedKeys().orEmpty()}
    val publicProjectionGuard=remember(libraryPrivateKeys,visibilityGuard,visibilityBlocked) {
        libraryPrivateKeys?.takeIf { visibilityReady }?.plus(visibilityBlocked)
    }
    // The IO-built index may briefly lag a *visibility-only* receipt update.
    // Re-check previously admitted refs against the current fail-closed guard
    // instead of blanking the other thousands of valid photos and songs.
    // Permission/private changes still return null immediately.
    val guardedPublicRefs = remember(libraryIndex,visibilityGuard,libraryPrivateKeys,phoneGallery,phoneMusic) {
        guardedRetainedIndexRefs(libraryIndex,visibilityGuard,libraryPrivateKeys,phoneGallery,phoneMusic,allowUnaffectedKinds=true)
    }
    val publicIndexAllowed = guardedPublicRefs != null
    val publicLibraryRefs = guardedPublicRefs.orEmpty()
    val displayInventory = remember(libraryIndex,visibilityGuard,guardedPublicRefs) {
        when {
            guardedPublicRefs == null -> LibraryDisplayInventory.EMPTY
            libraryIndex.visibility == visibilityGuard && guardedPublicRefs === libraryIndex.refs -> libraryIndex.display
            else -> LibraryDisplayInventory.build(guardedPublicRefs,visibilityGuard?.external.orEmpty())
        }
    }
    // Always use the live guard for follow-on projections and addressed actions.
    val publicProjectionAccess = visibilityGuard.takeIf { publicIndexAllowed }
    val historySnapshot by libraryModel.collectionHistory.collectAsStateWithLifecycle()
    val galleryAccessEpoch by libraryModel.galleryAccessEpoch.collectAsStateWithLifecycle()
    val musicAccessEpoch by libraryModel.musicAccessEpoch.collectAsStateWithLifecycle()
    // Real access/private epochs are independent of the visibility receipt revision.
    // A bridge can only remove rows from an already authenticated generation.
    val publicRetentionAccess = libraryPrivateKeys?.takeIf { publicIndexAllowed }?.let {
        Triple(it, galleryAccessEpoch, musicAccessEpoch)
    }
    val currentPublicRows = remember(publicLibraryRefs) { publicLibraryRefs.associate { it.item.id to it.item.savedUri } }
    fun retainedPublicRow(row: com.domenota.medialoader.core.database.DownloadEntity): Boolean =
        currentPublicRows.containsKey(row.id) && currentPublicRows[row.id] == row.savedUri
    val observeAudioCatalog = phoneMusic.loaded ||
        (appMode == AppMode.MUSIC && currentRoute in setOf("home", MEDIA_VIEWER_ROUTE)) ||
        (currentRoute == "collections" && collectionCatalogRequest == CollectionCatalogRequest.AUDIO)
    val audioCatalogFlow = remember(observeAudioCatalog) {
        if (observeAudioCatalog) libraryModel.audioCatalog else kotlinx.coroutines.flow.flowOf(null)
    }
    val audioCatalog by audioCatalogFlow.collectAsStateWithLifecycle(initialValue = null)
    val libraryItems = displayInventory.items
    val libraryAudioItems = displayInventory.audio
    val libraryAliasProjection = com.domenota.medialoader.ui.components.backgroundLibraryProjection(
        (emptyMap<String, com.domenota.medialoader.data.library.LibraryMediaRef>() to emptyMap<String,String>()), publicProjectionGuard, publicLibraryRefs,
        accessGuard = publicProjectionAccess, retentionGuard = publicRetentionAccess,
        revalidateRetained = { previous ->
            if (previous.first.values.all { retainedPublicRow(it.item) }) previous else {
                val refs = previous.first.filterValues { retainedPublicRow(it.item) }
                refs to previous.second.filterKeys(refs::containsKey)
            }
        }, kind = LibraryProjectionKind.ALIASES) {
        val refs=buildMap<String,com.domenota.medialoader.data.library.LibraryMediaRef> {
            publicLibraryRefs.forEach { ref -> ref.aliases.forEach { put(it,ref) }; put(ref.key,ref) }
        }
        refs to refs.mapValues { it.value.key }
    }
    val libraryRefsByAlias = libraryAliasProjection.value.first
    val liveLibraryRefsByAlias by rememberUpdatedState(libraryRefsByAlias)
    val libraryAliases = libraryAliasProjection.value.second
    val organizationReady=visibilityReady&&libraryIndex.visibility==visibilityGuard&&libraryIndex.ready&&libraryPrivateKeys!=null&&libraryAliasProjection.ready
    val organizationUsable=visibilityReady&&libraryIndex.visibility==visibilityGuard&&libraryIndex.ready&&libraryPrivateKeys!=null&&libraryAliasProjection.usable
    var trackRecognitionTarget by remember{mutableStateOf<Pair<String,Long?>?>(null)}
    fun openTrackRecognition(id:String,position:Long?=null,expectedUri:String?=null){
        val ref=libraryRefsByAlias[id] ?: expectedUri?.let{uri->publicLibraryRefs.singleOrNull{it.item.savedUri==uri}}
        if(!organizationUsable||ref==null||ref.item.isPrivate||ref.item.hidden||ref.item.mediaType!=MediaType.AUDIO||
            (expectedUri!=null&&ref.item.savedUri!=expectedUri)){
            AppNotice.post(context,"Трек недоступен");return
        }
        trackRecognitionTarget=ref.key to position
    }
    trackRecognitionTarget?.let{(id,position)->
        val row=publicLibraryRefs.firstOrNull{it.key==id}?.item
        if(libraryPrivateKeys!=null&&row!=null&&!row.isPrivate&&!row.hidden&&row.mediaType==MediaType.AUDIO)
            com.domenota.medialoader.ui.screens.music.TrackRecognitionDialog(row,position,
                onDismiss={trackRecognitionTarget=null},onUpdated={libraryModel.refresh(AppMode.MUSIC,force=true)})
        else LaunchedEffect(id,libraryPrivateKeys){trackRecognitionTarget=null}
    }

    fun openCollections(id:String?=null,audioOnly:Boolean=false,ids:List<String> = emptyList(),link:String?=null,create:Boolean=false){
        collectionInitial=id;collectionDetailId=id;collectionAudioOnly=audioOnly;collectionAddIds=ids;collectionLink=link;collectionCreate=create
        collectionLegacyRequest=CollectionCatalogRequest.NONE
        collectionPickerRequest=if(create || ids.isNotEmpty()) CollectionCatalogRequest.ALL else CollectionCatalogRequest.NONE
        navController.navigate("collections"){launchSingleTop=true}
    }
    fun addToCollection(ids:List<String>){
        if(!organizationReady){AppNotice.post(context,"Медиатека ещё загружается");return}
        val stable=ids.distinct().mapNotNull{libraryRefsByAlias[it]?.key}
        if(stable.size!=ids.distinct().size||stable.isEmpty()){AppNotice.post(context,"Часть файлов недоступна");return}
        openCollections(ids=stable)
    }
    fun openPassport(id:String){
        val ref=libraryRefsByAlias[id]
        if(!organizationReady||ref==null||ref.item.isPrivate||ref.item.hidden){AppNotice.post(context,"Файл недоступен");return}
        navController.navigate("media-passport/"+android.net.Uri.encode(ref.key))
    }
    fun openOrganizationLink(raw:String){
        try{val link=MediaOrganizationPolicy.link(raw);context.startActivity(Intent(Intent.ACTION_VIEW,android.net.Uri.parse(link)))}
        catch(_:Exception){AppNotice.post(context,"Не удалось открыть ссылку")}
    }
    val currentLibraryAudioId = libraryAudioState.currentId?.takeUnless { id -> folderRefsById.containsKey(id) || id in visibilityBlocked }?.let { id -> libraryRefsByAlias[id]?.key
        ?: libraryAudioState.identities.firstNotNullOfOrNull { libraryRefsByAlias[it]?.key } ?: id }
    val externalCatalog by libraryModel.externalCatalog.entries.collectAsStateWithLifecycle()
    val externalByAlias = remember(externalCatalog) { externalCatalog.flatMap { entry ->
        (entry.ref.aliases + entry.ref.key).map { it to entry }
    }.toMap() }
    val appLibraryItems = displayInventory.appItems
    val appLibraryAudioItems = displayInventory.appAudio
    val galleryPublicItems = if (gallerySource == LibrarySource.APP) appLibraryItems
        else if (!phoneGallery.loaded) emptyList() else libraryItems
    val playlists by libraryModel.playlistRepository.playlists.collectAsStateWithLifecycle()
    var playlistsReady by remember { mutableStateOf(false) }
    LaunchedEffect(libraryModel) { libraryModel.playlistRepository.awaitLoaded(); playlistsReady = true }
    LaunchedEffect(libraryModel) {
        try { libraryModel.audioPreview.observe() }
        catch (e: CancellationException) { throw e } catch (_: Exception) { /* A later play request can reconnect. */ }
    }
    var editorLibraryMode by remember { mutableStateOf<AppMode?>(null) }
    val phoneRows = remember(phoneGallery.items, phoneMusic.items) { phoneGallery.items + phoneMusic.items }
    var permissionMode by rememberSaveable { mutableStateOf(AppMode.GALLERY) }
    val libraryPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        libraryModel.refresh(permissionMode)
    }
    fun requestLibraryAccess(mode: AppMode) {
        permissionMode = mode
        libraryPermissions.launch(PhoneMediaLibrary.permissions(mode))
    }
    fun changeAppMode(next: AppMode) {
        val dispatchedAt = com.domenota.medialoader.core.logging.PerformanceLog.begin("mode_switch:$next")
        // Check access synchronously before a cached phone-backed screen can be rendered.
        if (next == AppMode.MUSIC || (next == AppMode.GALLERY &&
                (gallerySource == LibrarySource.PHONE || phoneGallery.loaded))) libraryModel.refresh(next)
        if (next == AppMode.MUSIC && appMode != AppMode.MUSIC) { musicTab = 0; musicTopRequest++ }
        libraryPreferences.mode = next
        appMode = next
        com.domenota.medialoader.core.logging.PerformanceLog.end("mode_switch_dispatch:$next", dispatchedAt)
    }
    fun changeLibrarySource(next: LibrarySource) {
        val dispatchedAt = com.domenota.medialoader.core.logging.PerformanceLog.begin("source_switch:$next")
        if (next == LibrarySource.PHONE) libraryModel.refresh(appMode)
        libraryPreferences.setSource(appMode, next)
        if (appMode == AppMode.GALLERY) gallerySource = next else musicLibrarySource = next
        if (next == LibrarySource.PHONE && !hasPhoneLibraryAccess(context, appMode)) requestLibraryAccess(appMode)
        com.domenota.medialoader.core.logging.PerformanceLog.end("source_switch_dispatch:$next", dispatchedAt)
    }
    val phoneViewingId = backStackEntry?.arguments?.getString("downloadId")
    fun refreshPhoneLibraries(force: Boolean = false) {
        val needed = mutableSetOf<AppMode>()
        editorLibraryMode?.let { needed += it }
        if (collectionRouteActive) {
            if (!collectionViewerActive) needed += collectionScanModes(collectionCatalogRequest)
        } else {
            if (currentRoute=="media-review"||currentRoute=="media-passport/{mediaId}") {
                needed+=AppMode.GALLERY;needed+=AppMode.MUSIC
            }
            if (appMode == AppMode.GALLERY && (librarySource == LibrarySource.PHONE ||
                    externalCatalog.any { it.ref.item.mediaType != MediaType.AUDIO })) needed += AppMode.GALLERY
            if (appMode == AppMode.MUSIC) needed += AppMode.MUSIC
            if (phoneViewingId?.startsWith("phone:") == true) needed +=
                if (phoneViewingId.startsWith("phone:audio:")) AppMode.MUSIC else AppMode.GALLERY
        }
        needed.forEach { mode ->
            val state = if (mode == AppMode.GALLERY) libraryModel.gallery.value else libraryModel.music.value
            if (force || (!state.loaded && !state.loading)) libraryModel.refresh(mode)
        }
    }
    LaunchedEffect(appMode, librarySource, currentRoute, collectionCatalogRequest) {
        // MediaStore's ContentObserver tracks real photo/video changes. Do not
        // force a full 11k-row scan each time we return from a photo viewer.
        // LibraryViewModel.refresh throttles routine rechecks and still refreshes
        // immediately when permissions or private-media access change.
        refreshPhoneLibraries()
    }
    LaunchedEffect(phoneViewingId) { refreshPhoneLibraries() }
    LaunchedEffect(externalCatalog.map { it.ref.key }) { refreshPhoneLibraries() }
    val refreshVisibleLibraries by rememberUpdatedState<(Boolean) -> Unit>({ force -> refreshPhoneLibraries(force) })
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refreshVisibleLibraries(true) }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var hiddenMusicTracksOpen by remember { mutableStateOf(false) }
    val libraryHeader: @Composable () -> Unit = {
        LibraryModeHeader(appMode, librarySource, ::changeAppMode, ::changeLibrarySource,
            onTrash = { navController.navigate("trash") },
            onSettings = { navController.navigate(AppDestination.Settings.route) },
            onSearch = { librarySearchVisible = !librarySearchVisible },
            onCalendar = if (appMode == AppMode.GALLERY && galleryTab == 0) ({ galleryDateRequest++ }) else null,
            onHiddenTracks = if (appMode == AppMode.MUSIC) ({ hiddenMusicTracksOpen = true }) else null,
            onPrivate = { viewModel.privateAccess.lock(); privateNavigation = true; navController.navigate(PRIVATE_ROUTE) })
    }
    // A single observable music-source state feeds Settings and BOTH preview hosts.
    // An experimental source may not silently fall back to HitMoz.
    var musicSource by remember {
        mutableStateOf(FullSongSource.fromStored(uiPrefs.getString(FullSongSource.PREF_KEY, null)))
    }
    fun changeMusicSource(next: FullSongSource) {
        if (next == musicSource) return
        // Commit synchronously before any new preview can launch in this process.
        val persisted = uiPrefs.edit().putString(FullSongSource.PREF_KEY, next.storedValue).commit()
        if (persisted) {
            val previous = musicSource
            musicSource = next
            AppLog.i("MusicSource", "Saved source mode=" + next.storedValue)
            viewModel.onMusicSourceChanged(previous, next)
        } else {
            AppLog.w("MusicSource", "Could not persist manual music-source selection")
            AppNotice.post(context, "Не удалось сохранить музыкальный источник")
        }
    }
    var closePickerAfterDownload by rememberSaveable {
        mutableStateOf(uiPrefs.getBoolean("close_picker_after_download", false))
    }
    var internalViewerEnabled by rememberSaveable {
        mutableStateOf(uiPrefs.getBoolean("internal_viewer_enabled", true))
    }
    var lastHandledLink by rememberSaveable { mutableStateOf("") }
    var showNameDialog by remember { mutableStateOf(false) }
    var pendingNames by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var batchName by remember { mutableStateOf("") }
    val pickerState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val preview by viewModel.preview.collectAsStateWithLifecycle()
    val deferredEntries by viewModel.deferredEntries.collectAsStateWithLifecycle()
    val deferredActive by viewModel.deferredActive.collectAsStateWithLifecycle()
    val deferredMessage by viewModel.deferredMessage.collectAsStateWithLifecycle()
    val bulk by viewModel.bulkState.collectAsStateWithLifecycle()
    val bulkHistory by viewModel.bulkHistory.collectAsStateWithLifecycle()
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val privateDownloads by viewModel.privateDownloads.collectAsStateWithLifecycle()
    val privateState by viewModel.privateAccess.state.collectAsState()
    // Keep old phone IDs resolvable for restored routes/playlists without showing duplicate cards.
    val publicDownloads = remember(downloads,visibilityGuard) { downloads.filter { row -> visibilityGuard?.canOpen(row,null,emptyList()) == true } }
    val indexedLibraryProjection = com.domenota.medialoader.ui.components.backgroundLibraryProjection(
        emptyList<com.domenota.medialoader.core.database.DownloadEntity>(), publicProjectionGuard, publicDownloads, publicLibraryRefs,
        accessGuard = publicProjectionAccess, retentionGuard = publicRetentionAccess,
        revalidateRetained = { rows -> rows.filter(::retainedPublicRow) }, kind = LibraryProjectionKind.PUBLIC_ROWS) {
        PublicLibraryRows.indexed(publicDownloads, publicLibraryRefs) { row ->
            publicProjectionAccess?.canOpen(row,null,emptyList()) == true
        }
    }
    val indexedLibraryRows = indexedLibraryProjection.value
    fun awaitingPhone(id: String?): Boolean = id?.startsWith("phone:") == true &&
        (!indexedLibraryProjection.ready || !libraryAliasProjection.ready ||
            if (id.startsWith("phone:audio:")) !phoneMusic.loaded else !phoneGallery.loaded)
    val publicSessionProjection = com.domenota.medialoader.ui.components.backgroundLibraryProjection(
        emptyList<com.domenota.medialoader.core.database.DownloadEntity>(),publicProjectionGuard,indexedLibraryRows, libraryAudioState.queue,
        accessGuard = publicProjectionAccess, enabled = libraryAudioState.queue.isNotEmpty() && indexedLibraryProjection.usable,
        retentionGuard = publicRetentionAccess, revalidateRetained = { rows -> rows.filter(::retainedPublicRow) },
        kind = LibraryProjectionKind.AUDIO_SESSION) {
        val session = libraryPrivateKeys?.let { keys ->
            com.domenota.medialoader.data.playback.AudioPlaybackMetadata.publicSessionRows(libraryAudioState.queue, indexedLibraryRows, keys)
        }.orEmpty()
        PublicLibraryRows.mergeSession(indexedLibraryRows, session) { row ->
            publicProjectionAccess?.canOpen(row,null,emptyList()) == true
        }
    }
    val publicLibraryRows = if (libraryAudioState.queue.isEmpty() || !publicSessionProjection.usable)
        indexedLibraryRows else publicSessionProjection.value
    val accessibleDownloads = remember(privateState.unlocked, publicLibraryRows, privateDownloads) {
        if (privateState.unlocked) publicLibraryRows + privateDownloads else publicLibraryRows
    }
    var pendingPrivateMoves by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var returnAfterPrivateSetup by rememberSaveable { mutableStateOf(false) }
    val privateMoveStatus by viewModel.privateMoveState.collectAsStateWithLifecycle()
    val privateMoveBusy = privateMoveStatus.busy
    val privateActivity = remember(context) {
        generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }
            .filterIsInstance<Activity>().firstOrNull()
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) viewModel.privateAccess.lock()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); viewModel.privateAccess.lock() }
    }
    DisposableEffect(privateNavigation, currentRoute == PRIVATE_ROUTE, privateActivity) {
        val window = privateActivity?.window
        val wasSecure = window?.attributes?.flags?.and(android.view.WindowManager.LayoutParams.FLAG_SECURE) != 0
        if (privateNavigation || currentRoute == PRIVATE_ROUTE) window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (!wasSecure) window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) }
    }
    LaunchedEffect(currentRoute) {
        if (currentRoute == PRIVATE_ROUTE) privateNavigation = true
        else if (privateNavigation && currentRoute !in setOf(MEDIA_VIEWER_ROUTE, VIDEO_EDITOR_ROUTE, AUDIO_EDITOR_ROUTE, PHOTO_EDITOR_ROUTE)) {
            viewModel.privateAccess.lock()
            privateNavigation = false
            pendingPrivateMoves = emptyList()
            returnAfterPrivateSetup = false
        }
    }
    var visibilityRestoreConsent by remember { mutableStateOf<kotlinx.coroutines.CompletableDeferred<Boolean>?>(null) }
    if(visibilityRestoreConsent!=null) AlertDialog(onDismissRequest={visibilityRestoreConsent?.complete(false)},
        title={Text("Вернуть видимость файлов?")},
        text={Text("Для этого действия выбранные скрытые файлы будут возвращены в исходное расположение и исключены из скрытой папки. Они снова станут видны в общей медиатеке и системной галерее.")},
        confirmButton={TextButton(onClick={visibilityRestoreConsent?.complete(true)}){Text("Вернуть и продолжить")}},
        dismissButton={TextButton(onClick={visibilityRestoreConsent?.complete(false)}){Text("Отмена")}})
    suspend fun restoreBeforeAction(ids:List<String>):Map<String,com.domenota.medialoader.core.database.DownloadEntity> {
        val selected=ids.distinct().mapNotNull { folderRefsById[it] }
        if(selected.isEmpty()) return emptyMap()
        check(visibilityRestoreConsent==null)
        val consent=kotlinx.coroutines.CompletableDeferred<Boolean>(); visibilityRestoreConsent=consent
        val approved=try{consent.await()}finally{visibilityRestoreConsent=null}
        if(!approved) throw java.util.concurrent.CancellationException("Отменено")
        check(android.os.Build.VERSION.SDK_INT>=30)
        val restored=mutableMapOf<String,com.domenota.medialoader.core.database.DownloadEntity>()
        visibilityOperation {
            for(ref in selected) {
                if(android.os.Build.VERSION.SDK_INT >= 30) {
                    check(mediaRepository.changeFolderMembership(ref,null,visibilityAccess.approve))
                } else error("Старый перенос недоступен на этой версии Android")
                val current=if(ref.appDownloadId!=null) mediaRepository.downloadById(ref.appDownloadId)
                    else libraryModel.externalCatalog.visibilityEntries().singleOrNull{it.ref.key==ref.key}?.ref?.item
                restored[ref.key]=checkNotNull(current).also { check(it.savedUri!=ref.item.savedUri && !it.hidden && !it.isPrivate) }
            }
        }
        return restored
    }
    fun runRestoredAction(ids:List<String>,action:suspend (Map<String,com.domenota.medialoader.core.database.DownloadEntity>)->Unit) {
        fileActionScope.launch {
            try { action(restoreBeforeAction(ids)) }
            catch(e:CancellationException){throw e}
            catch(e:Exception){AppNotice.post(context,e.message?:"Не удалось выполнить действие")}
        }
    }
    fun openVisibilityEditor(row:com.domenota.medialoader.core.database.DownloadEntity) {
        runRestoredAction(listOf(row.id)) { restored ->
            val current=restored[row.id]?:row
            if(restored.isNotEmpty() && current.id.startsWith("phone:")) {
                check(withTimeoutOrNull(8000L){libraryModel.index.first { index -> index.refs.any { it.key==current.id && it.item.savedUri==current.savedUri } }}!=null) { "Обновление медиатеки ещё не завершено" }
            }
            val route=when(current.mediaType){MediaType.PHOTO->"photo-editor/";MediaType.VIDEO->"video-editor/";MediaType.AUDIO->"audio-editor/"}
            navController.navigate(route+android.net.Uri.encode(current.id))
        }
    }
    val renameVisibilityFile:(String,String)->Unit={id,name->runRestoredAction(listOf(id)){viewModel.renameDownload(id,name)}}
    val recognizeVisibilityFile:(String)->Unit={id->runRestoredAction(listOf(id)){viewModel.recognizeDownload(id)}}
    suspend fun hideVisibilityGroupFile(id:String) { restoreBeforeAction(listOf(id)); viewModel.hideGroupFile(id) }
    fun continuePrivateMove(ids: List<String>) {
        // Navigation can retain this callback across recompositions. Read live owner
        // state: a captured busy=true would silently block every later batch.
        if (viewModel.privateMoveState.value.busy || ids.isEmpty()) return
        if (viewModel.privateAccess.state.value.mode == com.domenota.medialoader.data.privatevault.PrivateAccessMode.UNCONFIGURED) {
            pendingPrivateMoves = ids
            returnAfterPrivateSetup = true
            privateNavigation = true
            navController.navigate(PRIVATE_ROUTE)
        } else viewModel.startPrivateMove(ids)
    }
    fun requestPrivateMove(ids:List<String>) {
        runRestoredAction(ids) { continuePrivateMove(ids) }
    }
    LaunchedEffect(privateState.mode, pendingPrivateMoves) {
        if (pendingPrivateMoves.isNotEmpty() && privateState.mode != com.domenota.medialoader.data.privatevault.PrivateAccessMode.UNCONFIGURED) {
            val ids = pendingPrivateMoves
            if (viewModel.startPrivateMove(ids)) pendingPrivateMoves = emptyList()
        }
    }
    LaunchedEffect(privateMoveStatus.result, privateMoveStatus.busy) {
        val result = privateMoveStatus.result
        if (!privateMoveStatus.busy && result != null) {
            AppNotice.postBatch(context, "Перенос в личное", result, "Перемещено")
            viewModel.consumePrivateMoveResult(privateMoveStatus.revision)
            if (phoneGallery.loaded || phoneGallery.loading) libraryModel.refresh(AppMode.GALLERY, force = true)
            if (phoneMusic.loaded || phoneMusic.loading) libraryModel.refresh(AppMode.MUSIC, force = true)
            if (returnAfterPrivateSetup) {
                returnAfterPrivateSetup = false
                navController.popBackStack()
                viewModel.privateAccess.lock()
                privateNavigation = false
            }
        }
    }
    // A killed process cannot retain the in-memory batch; leave setup safely rather than showing a permanent busy screen.
    LaunchedEffect(returnAfterPrivateSetup, pendingPrivateMoves, privateMoveStatus) {
        // Lifecycle-aware collection may be paused while a credential activity is returning.
        val activeMove = viewModel.privateMoveState.value
        if (returnAfterPrivateSetup && pendingPrivateMoves.isEmpty() && !activeMove.busy && activeMove.result == null) {
            returnAfterPrivateSetup = false
            AppNotice.post(context, "Перенос был прерван. Проверьте файлы в загрузках и личном.")
            navController.popBackStack()
            viewModel.privateAccess.lock()
            privateNavigation = false
        }
    }
    if (privateMoveBusy && currentRoute != PRIVATE_ROUTE) AlertDialog(onDismissRequest = {}, confirmButton = {},
        text = { Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.CircularProgressIndicator(Modifier.size(28.dp))
            Text("Перемещаем в личное…", Modifier.padding(start = 16.dp))
        } })
    val catalogFolders by viewModel.catalogFolders.collectAsStateWithLifecycle()
    val catalogAssignments by viewModel.catalogAssignments.collectAsStateWithLifecycle()
    val catalogAssignmentProjection = com.domenota.medialoader.ui.components.backgroundLibraryProjection(
        emptyList<com.domenota.medialoader.core.database.CatalogAssignmentEntity>(), publicProjectionGuard,
        publicLibraryRefs, folderRefs, externalCatalog, catalogAssignments, catalogFolders,
        accessGuard = publicProjectionAccess?.let { it to FolderVisibilityStorageAccess.granted() },
        retentionGuard = libraryPrivateKeys?.takeIf { publicIndexAllowed }?.let { it to FolderVisibilityStorageAccess.granted() },
        revalidateRetained = { previous -> previous.filter { assignment ->
            currentPublicRows.containsKey(assignment.downloadId) || folderRefsById.containsKey(assignment.downloadId)
        } }, kind = LibraryProjectionKind.ASSIGNMENTS) {
        val validFolders = catalogFolders.mapTo(mutableSetOf()) { it.id }
        val externalAssignments = (publicLibraryRefs + folderRefs).mapNotNull { ref ->
            (visibilityGuard?.externalEntry(ref) ?: ref.aliases.firstNotNullOfOrNull { externalByAlias[it] }?.takeIf { it.ref.item.savedUri == ref.item.savedUri })?.let { entry ->
                com.domenota.medialoader.core.database.CatalogAssignmentEntity(ref.key,
                    entry.folderId?.takeIf { it in validFolders }, entry.isFavorite)
            }
        }
        (catalogAssignments + externalAssignments).distinctBy { it.downloadId }
    }
    val libraryCatalogAssignments = catalogAssignmentProjection.value
    val galleryBrowseAccess = publicProjectionAccess?.takeUnless {
        gallerySource != LibrarySource.APP && visibleLibraryValidationPending(AppMode.GALLERY, libraryIndex, phoneGallery, phoneMusic)
    }?.let { Triple(it, gallerySource, FolderVisibilityStorageAccess.granted()) to galleryAccessEpoch }
    val galleryRetentionAccess = libraryPrivateKeys?.takeIf { galleryBrowseAccess != null }
        ?.let { Triple(it, if (gallerySource == LibrarySource.APP) 0L else galleryAccessEpoch,
            gallerySource to FolderVisibilityStorageAccess.granted()) }
    val currentGalleryRows = remember(galleryPublicItems, folderRefs) {
        (galleryPublicItems + folderRefs.map { it.item }).associate { it.id to it.savedUri }
    }
    val galleryBrowse = com.domenota.medialoader.ui.components.backgroundLibraryProjection(
        GalleryBrowseInventory.EMPTY, publicProjectionGuard, galleryPublicItems, folderRefs,
        catalogFolders, libraryCatalogAssignments, displayInventory.deviceAlbums, visibilityFolders,
        accessGuard = galleryBrowseAccess, enabled = catalogAssignmentProjection.usable,
        retentionGuard = galleryRetentionAccess,
        revalidateRetained = { previous -> previous.retainAllowed { row ->
            currentGalleryRows.containsKey(row.id) && currentGalleryRows[row.id] == row.savedUri
        } },
        equivalentContent = { old, next -> old.equivalentContent(next) },
        kind = LibraryProjectionKind.GALLERY_BROWSE) {
        val items = GalleryBrowseInventory.withScopedItems(galleryPublicItems, folderRefs,
            libraryCatalogAssignments, visibilityFolders) { publicProjectionAccess?.explicitFolder(it) }
        GalleryBrowseInventory.build(items, catalogFolders, libraryCatalogAssignments,
            gallerySource, displayInventory.deviceAlbums, visibilityFolders)
    }
    // Folder metadata is shared between Gallery and Downloads, but external
    // phone media must not appear as missing download-history rows.
    val galleryOnlyFolderIds = remember(libraryCatalogAssignments, catalogAssignments) {
        val historyIds = catalogAssignments.mapTo(mutableSetOf()) { it.downloadId }
        libraryCatalogAssignments.asSequence()
            .filter { it.downloadId !in historyIds }
            .mapNotNull { it.folderId }
            .toSet()
    }
    val setLibraryFavorite: suspend (String, Boolean) -> Unit = { id, favorite ->
        val ref = folderRefsById[id] ?: libraryRefsByAlias[id] ?: currentLibraryAudioId?.takeIf { id == libraryAudioState.currentId }?.let(libraryRefsByAlias::get)
        requireNotNull(ref) { "Файл недоступен" }
        if (ref.appDownloadId != null) {
            libraryModel.externalCatalog.setExistingFavorite(ref, favorite)
            viewModel.setCatalogFavorite(ref.appDownloadId, favorite)
        }
        else libraryModel.externalCatalog.setFavorite(ref, favorite)
    }
    val setLibraryFolder: suspend (String, String?) -> Unit = { id, folder ->
        check(!visibilityWork.state.value.active &&
            !FolderVisibilityManager.hasPendingJournal(context)) {
            "Сначала завершите или проверьте незаконченный перенос папки"
        }
        val ref = folderRefsById[id] ?: publicLibraryRefs.firstOrNull { it.key == id || id in it.aliases }
            ?: mediaRepository.downloadById(id)?.takeIf { canOpenFolderMedia(it) && !it.isPrivate && !it.hidden && it.state==DownloadState.COMPLETED }
                ?.let { LibraryMediaRef(it.id,emptySet(),it,it.id) }
        requireNotNull(ref) { "Файл недоступен" }
        // Room has committed a newly created folder before Compose necessarily
        // observes it. Check the live flow, not the stale composition snapshot.
        if (folder != null && viewModel.catalogFolders.value.none { it.id == folder }) {
            val found = withTimeoutOrNull(1500L) {
                viewModel.catalogFolders.first { rows -> rows.any { it.id == folder } }
            }
            require(found != null) { "Папка не найдена" }
        }
        val currentFolder = if(ref.appDownloadId != null)
            catalogAssignments.firstOrNull { it.downloadId == ref.appDownloadId }?.folderId
        else libraryModel.externalCatalog.entries.value.singleOrNull { it.ref.key == ref.key }?.folderId
        check(currentFolder !in nomediaSnapshot.hiddenFolderIds && folder !in nomediaSnapshot.hiddenFolderIds) {
            "Сначала восстановите видимость папки и проверьте доступ к файлам"
        }
        val physical=mediaRepository.folderVisibility.requiresMembershipMove(ref,folder)
        if(physical) {
            RetiredFolderVisibilityTransfer.requireActive()
            check(android.os.Build.VERSION.SDK_INT>=30)
            visibilityMembershipBusy=true
            try {
                visibilityOperation {
                    check(mediaRepository.changeFolderMembership(ref,folder,visibilityAccess.approve))
                }
            } finally {
                visibilityMembershipBusy=false
            }
        } else if (ref.appDownloadId != null) {
            viewModel.setCatalogFolder(ref.appDownloadId, folder)
            libraryModel.externalCatalog.setExistingFolder(ref, folder)
        } else libraryModel.externalCatalog.setFolder(ref, folder)
    }
    val deleteVisibilityFolder:suspend (String)->Unit={ folder ->
        check(!visibilityWork.state.value.active &&
            !FolderVisibilityManager.hasPendingJournal(context)) {
            "Сначала завершите или проверьте незаконченный перенос папки"
        }
        if(mediaRepository.folderVisibility.isHidden(folder) || nomediaRegistry.snapshot.value.folders[folder]?.excludedFromMl == true) {
            error("Сначала восстановите видимость папки и проверьте доступ к файлам")
        } else {
            nomediaRegistry.releaseShownFolder(folder)
            viewModel.deleteCatalogFolder(folder)
            libraryModel.externalCatalog.removeFolder(folder)
        }
    }
    val signedIn by viewModel.signedIn.collectAsStateWithLifecycle()
    val instagramSessionRevision by viewModel.instagramSessionRevision.collectAsStateWithLifecycle()
    val youtubeSignedIn by viewModel.youTubeSignedIn.collectAsStateWithLifecycle()
    val vkSignedIn by viewModel.vkSignedIn.collectAsStateWithLifecycle()
    val rutubeSignedIn by viewModel.rutubeSignedIn.collectAsStateWithLifecycle()
    val youtubeCookies by viewModel.youTubeCookies.collectAsStateWithLifecycle()
    val ytDlpUpdate by viewModel.ytDlpUpdate.collectAsStateWithLifecycle()
    val ytDlpUpdating by viewModel.ytDlpUpdating.collectAsStateWithLifecycle()
    val hiddenCount by viewModel.hiddenCount.collectAsStateWithLifecycle()
    val hiddenItems by viewModel.hiddenItems.collectAsStateWithLifecycle()
    val downloadFolder by viewModel.downloadFolder.collectAsStateWithLifecycle()
    val storageMigration by viewModel.storageMigration.collectAsStateWithLifecycle()
    val dateRecoveryStatus by viewModel.dateRecoveryStatus.collectAsStateWithLifecycle()
    val galleryDateAudit by viewModel.galleryDateAudit.collectAsStateWithLifecycle()
    val galleryDateAuditBusy by viewModel.galleryDateAuditBusy.collectAsStateWithLifecycle()
    val migrationInventoryStatus by viewModel.migrationInventoryStatus.collectAsStateWithLifecycle()
    val migrationInventoryBusy by viewModel.migrationInventoryBusy.collectAsStateWithLifecycle()
    val migrationInventoryJson by viewModel.migrationInventoryJson.collectAsStateWithLifecycle()
    val republishTrialBusy by viewModel.republishTrialBusy.collectAsStateWithLifecycle()
    val republishTrialStatus by viewModel.republishTrialStatus.collectAsStateWithLifecycle()
    val exifTrialStatus by viewModel.exifTrialStatus.collectAsStateWithLifecycle()
    val exifTrialBusy by viewModel.exifTrialBusy.collectAsStateWithLifecycle()
    val videoTrialStatus by viewModel.videoTrialStatus.collectAsStateWithLifecycle()
    val videoTrialBusy by viewModel.videoTrialBusy.collectAsStateWithLifecycle()
    val sameUriBusy by viewModel.sameUriBusy.collectAsStateWithLifecycle()
    val sameUriJpegStatus by viewModel.sameUriJpegStatus.collectAsStateWithLifecycle()
    val sameUriVideoStatus by viewModel.sameUriVideoStatus.collectAsStateWithLifecycle()
    val dateRecoveryBusy by viewModel.dateRecoveryBusy.collectAsStateWithLifecycle()
    val dateRecoveryReady by viewModel.dateRecoveryReady.collectAsStateWithLifecycle()
    val dateRecoveryTrialDone by viewModel.dateRecoveryTrialDone.collectAsStateWithLifecycle()
    // MediaStore publishes new items under Pictures/Music after a verified
    // migration; refresh only when the explicit operation finishes.
    LaunchedEffect(storageMigration.running, storageMigration.processed) {
        if (!storageMigration.running && storageMigration.processed > 0) {
            libraryModel.refresh(AppMode.GALLERY, force = true)
            libraryModel.refresh(AppMode.MUSIC, force = true)
        }
    }
    val musicRecognitionMode by viewModel.musicRecognitionMode.collectAsStateWithLifecycle()
    val renameRecognizedTracks by viewModel.renameRecognizedTracks.collectAsStateWithLifecycle()
    val editorExport by viewModel.editorExport.collectAsStateWithLifecycle()
    DisposableEffect(currentRoute, editorExport.exporting) {
        com.domenota.medialoader.data.playback.BackgroundMediaWork.setBusy("viewer-or-editor",
            currentRoute == MEDIA_VIEWER_ROUTE || currentRoute == VIDEO_EDITOR_ROUTE ||
                currentRoute == AUDIO_EDITOR_ROUTE || currentRoute == PHOTO_EDITOR_ROUTE ||
                currentRoute == BROWSER_ROUTE || editorExport.exporting)
        onDispose {
            com.domenota.medialoader.data.playback.BackgroundMediaWork.setBusy("viewer-or-editor", false)
        }
    }
    val recognitionProposal by viewModel.recognitionProposal.collectAsStateWithLifecycle()
    recognitionProposal?.takeIf { proposal -> downloads.any { it.id == proposal.downloadId } }?.let { proposed ->
        AlertDialog(
            onDismissRequest = viewModel::dismissRecognitionProposal,
            title = { Text("Другой вариант композиции") },
            text = {
                Text(
                    "Повторное распознавание нашло: ${proposed.result.artist} — ${proposed.result.title}. " +
                        "Заменить сохранённые название и обложку? Исходный MP3 останется на месте."
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmRecognitionProposal) { Text("Применить") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissRecognitionProposal) { Text("Оставить прежнее") }
            },
        )
    }
    LaunchedEffect(editorExport.completedItemId, editorExport.sourceId, currentRoute, backStackEntry) {
        val completedId = editorExport.completedItemId ?: return@LaunchedEffect
        if (editorExport.operation == "Оригинал обновлён")
            libraryModel.refresh(AppMode.MUSIC, force = true, invalidate = true)
        val editorEntry = backStackEntry
        val editorSourceId = editorEntry?.arguments?.getString("downloadId")
        val isEditorRoute =
            currentRoute == VIDEO_EDITOR_ROUTE ||
                currentRoute == AUDIO_EDITOR_ROUTE ||
                currentRoute == PHOTO_EDITOR_ROUTE
        val entryReady = editorEntry?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true
        if (isEditorRoute && editorSourceId == editorExport.sourceId && entryReady) {
            navController.navigate("viewer/$completedId") {
                popUpTo(editorEntry.destination.id) { inclusive = true }
                launchSingleTop = true
            }
        }
        viewModel.consumeEditorCompletion()
    }
    // History is immutable metadata, but a saved file can disappear outside this app.
    // Scan in IO only when saved files change or the app returns to the foreground.
    var fileScanEpoch by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) fileScanEpoch++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val completedUris = historySnapshot?.completedUris.orEmpty()
    var collectionRefreshEpoch by remember { mutableIntStateOf(0) }
    // Fresh addressed checks supersede a history scan already in flight. Keep
    // only overrides made during that scan, not a second unbounded history map.
    val statusOverrides = remember { mutableMapOf<String, Pair<String?, SavedFileStatus>>() }
    var statusScanActive by remember { mutableStateOf(false) }
    var fileStatusUris by remember { mutableStateOf(emptyMap<String, String?>()) }
    var fileStatuses by remember {
        mutableStateOf(emptyMap<String, SavedFileStatus>())
    }
    LaunchedEffect(completedUris, fileScanEpoch) {
        delay(1_200L)
        statusOverrides.clear()
        statusScanActive = true
        try {
        val (uris, statuses) = withContext(Dispatchers.IO) {
            completedUris.toMap() to completedUris.associate { (id, uri) -> id to availabilityCache.get(uri) }
        }
        val overrides = statusOverrides.filter { (id, result) -> uris.containsKey(id) && uris[id] == result.first }
        fileStatusUris = uris
        fileStatuses = if (overrides.isEmpty()) statuses else statuses + overrides.mapValues { it.value.second }
        } finally { statusOverrides.clear(); statusScanActive = false }
    }
    val musicVisibilityPrefs=remember(context){context.getSharedPreferences("music_folder_visibility",0)}
    var organizationHidden by remember{mutableStateOf(musicVisibilityPrefs.getStringSet("hidden_tracks",emptySet()).orEmpty().toSet())}
    DisposableEffect(musicVisibilityPrefs){
        val listener=android.content.SharedPreferences.OnSharedPreferenceChangeListener{_,key->
            if(key=="hidden_tracks"||key==null)organizationHidden=musicVisibilityPrefs.getStringSet("hidden_tracks",emptySet()).orEmpty().toSet()
        }
        musicVisibilityPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose{musicVisibilityPrefs.unregisterOnSharedPreferenceChangeListener(listener)}
    }
    val recoveryRunning by com.domenota.medialoader.data.storage.HistoricalRecoverySignals.isRunning.collectAsStateWithLifecycle()
    val collectionHistory = historySnapshot?.rows.orEmpty()
    // The only synchronous gate contains small identity sets, not one newly allocated
    // set per media row. Real privacy/hidden changes invalidate retained projections.
    val collectionPrivacyGeneration by DuplicatePrivacyGuard.changes.collectAsStateWithLifecycle()
    val inFlightPrivacyKeys = remember(collectionPrivacyGeneration, libraryPrivateKeys, historySnapshot?.blocked) { DuplicatePrivacyGuard.currentBlockedKeys() }
    val collectionBlocked = remember(libraryPrivateKeys, historySnapshot?.blocked, organizationHidden, inFlightPrivacyKeys, visibilityGuard) {
        buildSet {
            addAll(inFlightPrivacyKeys)
            addAll(visibilityGuard?.blockedKeys().orEmpty())
            addAll(libraryPrivateKeys.orEmpty())
            addAll(historySnapshot?.blocked.orEmpty())
            addAll(com.domenota.medialoader.ui.screens.music.MusicTrackVisibility.blockedIdentities(organizationHidden))
        }
    }
    val collectionPermissionTag = remember(fileScanEpoch, galleryAccessEpoch, musicAccessEpoch, currentRoute) {
        val permissions = (PhoneMediaLibrary.permissions(AppMode.GALLERY) + PhoneMediaLibrary.permissions(AppMode.MUSIC)).distinct()
        val granted = permissions.filter { ContextCompat.checkSelfPermission(context,it) == PackageManager.PERMISSION_GRANTED }
        val partial = Build.VERSION.SDK_INT >= 34 && android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED in granted &&
            (android.Manifest.permission.READ_MEDIA_IMAGES !in granted || android.Manifest.permission.READ_MEDIA_VIDEO !in granted)
        granted.sorted().joinToString("|") + if (partial) ":selected:$fileScanEpoch" else ""
    }
    val collectionAccess = libraryPrivateKeys?.takeIf { !recoveryRunning && historySnapshot != null && visibilityReady }?.let {
        CollectionAccessGuard(it, collectionBlocked, galleryAccessEpoch, musicAccessEpoch, collectionPermissionTag, collectionPrivacyGeneration)
    }
    val collectionReadAccess = libraryPrivateKeys?.takeIf {
        historySnapshot != null && visibilityReady && collectionReadAllowed(publicIndexAllowed, com.domenota.medialoader.data.storage.HistoricalRecoverySignals.isRunning.value,
            visibilityGuard?.policy?.pendingFolder, visibilityGuard?.policy?.unknownPending != false,
            com.domenota.medialoader.data.storage.HistoricalRecoverySignals.isFolderVisibilityOperation())
    }?.let { CollectionAccessGuard(it, collectionBlocked, galleryAccessEpoch, musicAccessEpoch, collectionPermissionTag, collectionPrivacyGeneration) }
    val fullGuard = collectionAccess.takeIf { collectionRouteActive && collectionCatalogRequest != CollectionCatalogRequest.NONE }
    // The full catalog is needed for picking arbitrary new files or legacy alias
    // fallback, not for displaying an already stored collection.
    val audioPickerSnapshot = audioCatalog?.takeIf { it.guard.privacy == libraryPrivateKeys && it.guard.accessEpoch == musicAccessEpoch && it.guard.visibility == visibilityGuard }
    val allCatalogComplete = libraryIndex.ready && libraryIndex.appComplete &&
        libraryIndex.privacyKeys == libraryPrivateKeys && libraryIndex.appUris == historySnapshot?.appUris &&
        phoneGallery.loaded && phoneGallery.complete && !phoneGallery.loading && phoneMusic.loaded && phoneMusic.complete && !phoneMusic.loading &&
        phoneGallery.accessEpoch == galleryAccessEpoch && phoneMusic.accessEpoch == musicAccessEpoch &&
        libraryIndex.galleryRevision == phoneGallery.revision && libraryIndex.musicRevision == phoneMusic.revision
    val completeRefs = if(fullGuard == null) null else when(collectionCatalogRequest) {
        CollectionCatalogRequest.NONE -> null
        CollectionCatalogRequest.AUDIO -> audioPickerSnapshot?.refs
        CollectionCatalogRequest.ALL -> publicLibraryRefs.takeIf { allCatalogComplete }
    }
    val collectionComplete = completeRefs != null
    val pickerGuard = fullGuard?.let { it to collectionCatalogRequest }
    val fullCatalogProjection = addressedLibraryProjection<CollectionCatalogSnapshot?>(null, pickerGuard, completeRefs,
        publishWhen = completeRefs != null) {
        completeRefs?.let { CollectionCatalogSnapshot(it, fullGuard!!.privacy, CollectionIdentityIndex.build(it)) }
    }
    val collectionCatalog = fullCatalogProjection.value
    val fullCurrent = collectionComplete && fullCatalogProjection.ready && collectionCatalog != null
    val statusSnapshot = fileStatuses
    val statusUriSnapshot = fileStatusUris
    val hiddenSnapshot = organizationHidden
    val fullProjection = addressedLibraryProjection(RouteMediaCatalog.EMPTY, pickerGuard.takeIf { collectionCatalog != null },
        collectionCatalog, collectionHistory, statusSnapshot, statusUriSnapshot) {
        RouteMediaCatalog(filterRouteCatalog(collectionCatalog?.refs.orEmpty(), collectionBlocked, collectionHistory, statusSnapshot, statusUriSnapshot))
    }
    val collectionRefs = fullProjection.value.refs
    val collectionUsable = fullProjection.usable && collectionCatalog != null
    val scopedCollection = collections.firstOrNull { it.record.id == collectionDetailId }
    val scopedAccess = collectionAccess?.copy(galleryEpoch=0, musicEpoch=0)
    val collectionCoverAccess = remember(collectionPermissionTag, historySnapshot, collectionReadAccess != null,
        collectionBlocked, libraryPrivateKeys != null, visibilityReady, fileScanEpoch) {
        CollectionCoverAccess(collectionReadAccess != null && PhoneMediaLibrary.canReadMissingMetadata(MediaType.PHOTO, Build.VERSION.SDK_INT) {
            ContextCompat.checkSelfPermission(context,it)==PackageManager.PERMISSION_GRANTED
        }, if(collectionReadAccess!=null) historySnapshot?.publicPhotoUris.orEmpty() else emptySet(),
            collectionBlocked.takeIf { libraryPrivateKeys != null && historySnapshot != null && visibilityReady },
            fileScanEpoch.toLong())
    }
    val scopeGuard = scopedAccess.takeIf { collectionRouteActive }?.let { guard -> scopedCollection?.record?.id?.let { CollectionDetailGuard(guard, it) } }
    val scopeMembers = scopedCollection?.members.orEmpty()
    val relevantHistory = remember(scopeMembers, historySnapshot) { historySnapshot?.forMembers(scopeMembers).orEmpty() }
    val scopeHistory = remember(relevantHistory) { relevantHistory }
    val mediaAddressSignal by libraryModel.collectionAddressRevision.collectAsStateWithLifecycle()
    var scopedMediaRevision by remember { mutableStateOf(mediaAddressSignal.revision) }
    LaunchedEffect(mediaAddressSignal.revision) {
        if(mediaAddressSignal.affects(scopeMembers,scopeHistory)) scopedMediaRevision=mediaAddressSignal.revision
    }
    // Do not restart the addressed pass for every incremental MediaStore batch.
    // A completed revision or actual history/access change is a meaningful trigger.
    val collectionDiskCache = remember(context) { CollectionProjectionCache(context) }
    val overviewDiskCache = remember(context) { CollectionProjectionCache(context, "collection-overviews") }
    var collectionCacheRevision by remember { mutableIntStateOf(0) }
    val scopedProjection = addressedLibraryProjection<AddressedCollection?>(null, scopeGuard,
        scopeMembers, scopeHistory, collectionCatalog, fullCurrent, fileScanEpoch, collectionRefreshEpoch, scopedMediaRevision,
        retain = { previous, next -> next?.let { retainAddressedCollection(previous,it) } }, serial = true,
        seed = { scopeGuard?.let { guard -> CollectionLookupBudget.run(2_000) {
            collectionDiskCache.read(guard.id, scopeMembers, scopeHistory, collectionBlocked)
        } } }) {
        val started = android.os.SystemClock.elapsedRealtime()
        val cacheGuard = collectionDiskCache.guard(collectionBlocked)
        val stamp = CollectionLookupBudget.run(2_000) { collectionDiskCache.stamp() } ?: PhoneStoreStamp(null,null)
        val deadline = CollectionLookupDeadline()
        val result = CollectionAddressResolver.resolve(scopeMembers, if(collectionCatalog==null) scopeHistory else collectionHistory, collectionCatalog,
            fullCurrent && collectionCatalogRequest == CollectionCatalogRequest.ALL, collectionBlocked,
            { row -> deadline.lookup { libraryModel.addressCollectionApp(row) { uri -> availabilityCache.get(uri, fresh = true) } } },
            { uri -> deadline.lookup { libraryModel.addressCollectionPhone(uri) } }) { ref ->
            ref.item.mediaType == MediaType.AUDIO && com.domenota.medialoader.ui.screens.music.MusicTrackVisibility.hidden(ref.item, hiddenSnapshot)
        }.copy(verifiedStoreStamp = stamp, verifiedCacheGuard = cacheGuard)
        result.also { AppLog.i("Library", "Collection addresses · members=${scopeMembers.size} · resolved=${it.resolved.size} · pending=${it.pending.size} · full=$fullCurrent · ms=${android.os.SystemClock.elapsedRealtime()-started}") }
    }
    // Overview has its own cheap first-member projections, not a full media catalog.
    val coverGuard = scopedAccess.takeIf { collectionsLoaded && ((currentRoute == "collections" && collectionDetailId == null) ||
        (currentRoute == "home" && appMode == AppMode.GALLERY)) }
    val overviewCollections = collections
    val overviewHistory = historySnapshot
    val overviewMembers = remember(overviewCollections) { overviewCollections.flatMap { it.members } }
    val overviewRelevantHistory = remember(overviewMembers, historySnapshot) { historySnapshot?.forMembers(overviewMembers).orEmpty() }
    val overviewRows = remember(overviewRelevantHistory) { overviewRelevantHistory }
    var overviewMediaRevision by remember { mutableStateOf(mediaAddressSignal.revision) }
    LaunchedEffect(mediaAddressSignal.revision) {
        if (coverGuard != null && mediaAddressSignal.affects(overviewMembers, overviewRows)) overviewMediaRevision = mediaAddressSignal.revision
    }
    suspend fun readOverviewCovers(): Map<String, LibraryMediaRef> {
        val stamp = collectionDiskCache.stamp()
        return buildMap {
            for (collection in overviewCollections) {
                val detail = collectionDiskCache.read(collection.record.id,collection.members,overviewRows,collectionBlocked,stamp)
                val cover = com.domenota.medialoader.data.library.collectionAutomaticCover(collection.members,detail?.resolved.orEmpty(),collectionBlocked)
                    ?: com.domenota.medialoader.data.library.collectionAutomaticCover(collection.members,
                        overviewDiskCache.read(collection.record.id,collection.members,overviewRows,collectionBlocked,stamp)?.resolved.orEmpty(),collectionBlocked)
                cover?.let { put(collection.record.id,it) }
            }
        }
    }
    val overviewProjection = addressedLibraryProjection<Map<String,LibraryMediaRef>?>(null,coverGuard,
        collections,overviewRows,collectionCacheRevision,fileScanEpoch,overviewMediaRevision,
        retain = { previous, next -> next ?: previous }, serial = true,
        seed = { CollectionLookupBudget.run(3_000) { readOverviewCovers() } }) {
        CollectionLookupBudget.run(12_000) {
            val stamp = overviewDiskCache.stamp()
            val cacheGuard = overviewDiskCache.guard(collectionBlocked)
            val cached = readOverviewCovers()
            buildMap {
                for (collection in overviewCollections) {
                    val candidates = listOfNotNull(com.domenota.medialoader.data.library.collectionAutomaticCoverMember(collection.members,collectionBlocked))
                    if (candidates.isEmpty()) continue
                    val value = CollectionAddressResolver.resolve(candidates, overviewHistory?.forMembers(candidates).orEmpty(), null, false, collectionBlocked,
                        { row -> libraryModel.addressCollectionApp(row) { uri -> availabilityCache.get(uri, fresh = true) } }, libraryModel::addressCollectionPhone)
                    val cover = com.domenota.medialoader.data.library.collectionAutomaticCover(collection.members,value.resolved,collectionBlocked)
                    if (cover != null) {
                        put(collection.record.id,cover)
                        overviewDiskCache.write(collection.record.id,candidates,value,collectionBlocked,stamp,cacheGuard)
                    } else {
                        val old = cached[collection.record.id]
                        val stillPending = candidates.any { it.id in value.pending && it.id !in value.invalidated &&
                            it.id !in value.suppressed && old != null && CollectionAddressResolver.matches(MediaOrganizationPolicy.decode(it.identityJson),old) }
                        if(stillPending && old != null) put(collection.record.id,old)
                        else overviewDiskCache.write(collection.record.id,candidates,value,collectionBlocked,stamp,cacheGuard)
                    }
                }
            }
        }
    }
    val cachedCollectionCovers = remember(overviewProjection.value, collectionHistory, collectionBlocked, statusSnapshot, statusUriSnapshot) {
        overviewProjection.value.orEmpty().filterValues { ref -> filterRouteCatalog(listOf(ref),collectionBlocked,collectionHistory,statusSnapshot,statusUriSnapshot).isNotEmpty() }
    }
    val readRefsByKey = remember(publicLibraryRefs) { publicLibraryRefs.associateBy { it.key } }
    val detailReadGuard = collectionReadAccess.takeIf { collectionRouteActive }
        ?.let { access -> scopedCollection?.record?.id?.let { CollectionDetailGuard(access, it) } }
    val materializedDetail = com.domenota.medialoader.ui.components.backgroundLibraryProjection<AddressedCollection?>(
        null, libraryPrivateKeys, scopeMembers, publicLibraryRefs, collectionHistory, collectionBlocked, statusSnapshot, statusUriSnapshot,
        accessGuard=detailReadGuard,
        retentionGuard=detailReadGuard?.copy(access=detailReadGuard.access.copy(blocked=emptySet())),
        revalidateRetained={ previous -> retainMaterializedCollectionRead(previous, scopeMembers, readRefsByKey,
            collectionHistory, collectionBlocked, statusSnapshot, statusUriSnapshot) }) {
        materializedCollectionRead(scopeMembers, publicLibraryRefs, collectionHistory, collectionBlocked, statusSnapshot, statusUriSnapshot)
    }
    val transferDetailRead = collectionReadAccess != null && (recoveryRunning || !scopedProjection.usable)
    val scopedCatalog = remember(scopedProjection.value, materializedDetail.value, transferDetailRead,
        scopeMembers, collectionHistory, collectionBlocked, statusSnapshot, statusUriSnapshot) {
        (if (transferDetailRead) materializedDetail.value else scopedProjection.value)?.let {
            filterAddressedDisplay(it, scopeMembers, collectionHistory, collectionBlocked, statusSnapshot, statusUriSnapshot)
        }
    }
    LaunchedEffect(scopedCatalog, scopeGuard, scopedProjection.ready) {
        val result = scopedCatalog ?: return@LaunchedEffect
        val guard = scopeGuard ?: return@LaunchedEffect
        val stamp = result.verifiedStoreStamp ?: return@LaunchedEffect
        val cacheGuard = result.verifiedCacheGuard ?: return@LaunchedEffect
        if (!scopedProjection.ready) return@LaunchedEffect
        try {
            val wrote = CollectionLookupBudget.run(3_000) { collectionDiskCache.write(guard.id, scopeMembers, result, collectionBlocked, stamp, cacheGuard); true }
            if(wrote==true) collectionCacheRevision++
        }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { AppLog.w("Library", "Collection projection cache write deferred") }
    }
    LaunchedEffect(scopedCatalog) {
        val changes = scopedCatalog?.statuses.orEmpty().filter { (id, result) ->
            collectionHistory[id]?.savedUri == result.first && result.second != SavedFileStatus.UNCHECKED &&
                (fileStatusUris[id] != result.first || fileStatuses[id] != result.second)
        }
        if (changes.isNotEmpty()) {
            if (statusScanActive) statusOverrides.putAll(changes)
            fileStatusUris = fileStatusUris + changes.mapValues { it.value.first }
            fileStatuses = fileStatuses + changes.mapValues { it.value.second }
        }
    }
    LaunchedEffect(scopedCatalog, scopeGuard, scopedProjection.ready) {
        if (!scopedProjection.ready) return@LaunchedEffect
        val scope = scopedCatalog ?: return@LaunchedEffect
        val guard = scopeGuard ?: return@LaunchedEffect
        if (scopeMembers.any { it.metadataJson == null && it.id in scope.resolved && it.id !in scope.pending }) {
            try { organization.rememberMemberMetadata(guard.id,scopeMembers,scope.resolved,guard.access.blocked) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { AppLog.w("Library", "Collection metadata persistence deferred") }
        }
    }
    val missingExternalRefs = remember(externalCatalog, collectionBlocked, collectionRouteActive) {
        if (!collectionRouteActive) emptyList() else externalCatalog.map { it.ref }.filter { it.appDownloadId == null && !collectionRefBlocked(it, collectionBlocked) }
    }
    val audioSnapshot = audioCatalog?.takeIf { it.guard.privacy == libraryPrivateKeys && it.guard.accessEpoch == musicAccessEpoch && it.guard.visibility == visibilityGuard && !recoveryRunning }
    val listeningHistoryRows by libraryModel.listeningHistoryRows.collectAsStateWithLifecycle()
    val analyticsGuard = audioSnapshot?.guard?.takeIf { historySnapshot != null && listeningHistoryRows != null }
        ?.let { ListeningCatalogGuard(it,collectionBlocked,collectionPrivacyGeneration) }
    val analyticsProjection = addressedLibraryProjection(ListeningCatalog.EMPTY, analyticsGuard,
        audioSnapshot, listeningHistoryRows, statusSnapshot, statusUriSnapshot, serial=true,
        retain={ previous,next -> if(previous.refs==next.refs) previous else next }) {
        ListeningCatalog(filterRouteCatalog(audioSnapshot?.refs.orEmpty(),collectionBlocked,listeningHistoryRows.orEmpty(),statusSnapshot,statusUriSnapshot))
    }
    val currentAnalytics by rememberUpdatedState(analyticsProjection.value)
    val currentAnalyticsGuard by rememberUpdatedState(analyticsGuard)
    val currentAudioFavorites = remember(libraryCatalogAssignments) { libraryCatalogAssignments.filter { it.isFavorite }.mapTo(mutableSetOf()) { it.downloadId } }
    fun currentAudioRef(id: String): LibraryMediaRef? {
        val guard=currentAnalyticsGuard ?: return null
        val blocked=libraryModel.privateKeys.value ?: return null
        if(guard.access.privacy!=blocked || guard.access.accessEpoch!=libraryModel.musicAccessEpoch.value ||
            guard.privacyGeneration!=DuplicatePrivacyGuard.generation(context) ||
            com.domenota.medialoader.data.storage.HistoricalRecoverySignals.isRunning.value) return null
        val ref=currentAnalytics.byKey[id] ?: return null
        val live=libraryModel.listeningHistoryRows.value ?: return null
        if(collectionRefBlocked(ref,guard.blocked)||collectionRefBlocked(ref,blocked)||
            !filterRouteCatalog(listOf(ref),guard.blocked,live,fileStatuses,fileStatusUris).any()) return null
        return ref
    }
    val recentGuard = audioSnapshot?.guard?.takeIf { historySnapshot != null }
        ?.let { RecentAccessGuard(it, collectionBlocked, musicLibrarySource) }
    val recentProjection = addressedLibraryProjection(RouteMediaCatalog.EMPTY, recentGuard, audioSnapshot,
        collectionHistory, statusSnapshot, statusUriSnapshot, externalByAlias) {
        val refs = filterRouteCatalog(audioSnapshot?.refs.orEmpty(), collectionBlocked, collectionHistory, statusSnapshot, statusUriSnapshot)
        RouteMediaCatalog(refs.filter { ref -> musicLibrarySource == LibrarySource.PHONE || ref.appDownloadId != null ||
            ref.aliases.any { externalByAlias[it]?.folderId != null } })
    }
    val organizationNeeded = currentRoute in setOf("media-review", "media-passport/{mediaId}", "visual-duplicates") ||
        (currentRoute == "home" && (appMode == AppMode.GALLERY || showHomeOrganization))
    val organizationReadAccess = collectionReadAccess.takeIf { organizationNeeded && publicIndexAllowed }
    val organizationProjection = com.domenota.medialoader.ui.components.backgroundLibraryProjection(RouteMediaCatalog.EMPTY,
        libraryPrivateKeys, publicLibraryRefs, collectionHistory, statusSnapshot, statusUriSnapshot,
        accessGuard=organizationReadAccess,
        retentionGuard=organizationReadAccess?.copy(blocked=emptySet()),
        revalidateRetained={ previous -> RouteMediaCatalog(filterRouteCatalog(previous.refs.mapNotNull { old -> readRefsByKey[old.key]?.takeIf { it.item.savedUri == old.item.savedUri } },
            collectionBlocked, collectionHistory, statusSnapshot, statusUriSnapshot)) }) {
        RouteMediaCatalog(filterRouteCatalog(publicLibraryRefs, collectionBlocked, collectionHistory, statusSnapshot, statusUriSnapshot))
    }
    val organizationRefs = organizationProjection.value.refs
    // Result reconciliation and new scans share the full validated visual index.
    // The selected source controls scan coverage, not the validity of old reports.
    val visualScanRoute = currentRoute == "visual-duplicates"
    LaunchedEffect(visualScanRoute) {
        if (visualScanRoute) libraryModel.refresh(AppMode.GALLERY)
    }
    val visualScanInProgress = if(visualScanRoute) libraryModel.galleryScanInProgress.collectAsStateWithLifecycle().value else false
    val visualScanHasPhoneAccess = visualScanRoute && hasPhoneLibraryAccess(context, AppMode.GALLERY)
    val visualScanComplete = visualScanRoute && organizationReady && organizationProjection.ready &&
        visualDuplicateCatalogComplete(libraryIndex, phoneGallery, galleryAccessEpoch, historySnapshot?.appUris,visualScanInProgress) &&
        (!visualScanHasPhoneAccess || phoneGallery.message==null)
    // Safe read-only reports survive routine refresh; new scans and destructive actions still
    // require the complete fresh generation. Permission/privacy changes replace this guard.
    val visualReportAccess = collectionAccess.takeIf { visualScanRoute && organizationUsable && organizationProjection.usable }
    val visualScanCatalog = addressedLibraryProjection(
        VisualDuplicateCatalog.EMPTY, visualReportAccess, organizationRefs, displayInventory.appItems, gallerySource,
        publishWhen = visualScanComplete) {
        VisualDuplicateCatalog.build(organizationRefs, displayInventory.appItems, gallerySource)
    }
    val visualScanReady = visualScanComplete && visualScanCatalog.ready && visualReportAccess != null
    val visualPhonePermission = gallerySource != LibrarySource.PHONE || visualScanHasPhoneAccess

    val currentCollectionCatalog by rememberUpdatedState(fullProjection.value)
    val currentScopedCatalog by rememberUpdatedState(scopedCatalog)
    val currentRecentCatalog by rememberUpdatedState(recentProjection.value)
    val currentBlocked by rememberUpdatedState(collectionBlocked)
    val recentDeviceFolders = remember(phoneMusic.items) { phoneMusic.items.mapNotNull { row ->
        row.savedUri?.let { uri -> row.groupId?.let { uri to it } }
    }.toMap() }
    val currentRecentFolders by rememberUpdatedState(recentDeviceFolders)
    val musicHomeItems = if (musicLibrarySource == LibrarySource.APP) appLibraryAudioItems
        else if (!phoneMusic.loaded) emptyList() else libraryAudioItems
    // Historical transfer keeps a guarded, complete music snapshot. Only
    // actual permission/visibility validation may hide it.
    val musicBrowseAccess = publicProjectionAccess?.takeUnless {
        musicLibrarySource != LibrarySource.APP && visibleLibraryValidationPending(AppMode.MUSIC, libraryIndex, phoneGallery, phoneMusic) }
        ?.let { Triple(it, musicAccessEpoch, collectionPrivacyGeneration) to musicLibrarySource }
    val musicBrowse = com.domenota.medialoader.ui.components.backgroundLibraryProjection(
        MusicBrowseCatalog.EMPTY, publicProjectionGuard, musicHomeItems, libraryAudioItems, recentDeviceFolders,
        accessGuard = musicBrowseAccess, enabled = observeAudioCatalog,
        retentionGuard = libraryPrivateKeys?.takeIf { musicBrowseAccess != null }
            ?.let { Triple(it, if (musicLibrarySource == LibrarySource.APP) 0L else musicAccessEpoch, musicLibrarySource) },
        revalidateRetained = { previous -> previous.retainAllowed(::retainedPublicRow) },
        kind = LibraryProjectionKind.MUSIC_BROWSE) {
        MusicBrowseCatalog.build(musicHomeItems, libraryAudioItems, recentDeviceFolders)
    }
    suspend fun validCollectionRefs(ids: List<String>, recent: Boolean = false, analytics: Boolean = false, selectedId: String? = null): List<LibraryMediaRef> {
        val full = currentCollectionCatalog.byKey
        val scoped = currentScopedCatalog?.byKey.orEmpty()
        val music = currentRecentCatalog.byKey
        val audioEpoch = libraryModel.musicAccessEpoch.value
        val visualEpoch = libraryModel.galleryAccessEpoch.value
        val actionPrivacyEpoch = DuplicatePrivacyGuard.generation(context)
        val candidates = ids.distinct().mapNotNull { id -> if (analytics) currentAudioRef(id) else if (recent) music[id] else scoped[id] ?: full[id] }
        val (checked, statuses) = withContext(Dispatchers.IO) {
            val statuses = mutableMapOf<String, Pair<String?, SavedFileStatus>>()
            val hiddenKeys = musicVisibilityPrefs.getStringSet("hidden_tracks", emptySet()).orEmpty()
            val blocked = libraryModel.privateKeys.value?.plus(com.domenota.medialoader.ui.screens.music.MusicTrackVisibility.blockedIdentities(hiddenKeys))
                ?: return@withContext emptyList<LibraryMediaRef>() to statuses
            val allowedTypes = MediaType.entries.filter { type -> PhoneMediaLibrary.canRead(type,Build.VERSION.SDK_INT) {
                ContextCompat.checkSelfPermission(context,it)==PackageManager.PERMISSION_GRANTED } }.toSet()
            val checked = candidates.filter { ref ->
                if (collectionRefBlocked(ref, blocked) || !DuplicatePrivacyGuard.accepts(context,actionPrivacyEpoch,ref.appDownloadId ?: ref.key,ref.aliases)) return@filter false
                if (ref.appDownloadId == null && ref.item.mediaType !in allowedTypes) return@filter false
                val current = ref.appDownloadId?.let { if(analytics) libraryModel.listeningHistoryRows.value?.get(it) else mediaRepository.downloadById(it) }
                // A large playback queue already comes from a complete current audio
                // generation. Fresh-check the requested track; reuse the short-lived
                // presence cache for the rest, with current privacy/URI checks below.
                val targeted=!analytics || candidates.size<=30 || ref.key==selectedId
                val status = availabilityCache.get(ref.item.savedUri, fresh = targeted)
                ref.appDownloadId?.let { id -> statuses[id] = ref.item.savedUri to status }
                status == SavedFileStatus.PRESENT &&
                    (ref.appDownloadId == null || current?.let { !it.isPrivate && !it.hidden && it.state == DownloadState.COMPLETED && it.savedUri == ref.item.savedUri } == true) &&
                    (ref.item.mediaType != MediaType.AUDIO || !com.domenota.medialoader.ui.screens.music.MusicTrackVisibility.hidden(ref.item, hiddenKeys)) &&
                    (if (ref.appDownloadId != null) true else {
                        val identities = if(targeted) com.domenota.medialoader.data.playback.PublicMediaIdentity.capture(context, requireNotNull(ref.item.savedUri)) else ref.aliases
                        val strong = ref.aliases.filter { it.startsWith("file-stat:") }
                        val known = if (strong.isNotEmpty()) strong else ref.aliases.filter { it.startsWith("uri:") || it.startsWith("media:") || it.startsWith("file:") }
                        known.any(identities::contains) && identities.none(blocked::contains) &&
                            (recent || analytics || !CollectionAddressResolver.isTypedPhoneAddress(requireNotNull(ref.item.savedUri)) || libraryModel.addressCollectionPhone(requireNotNull(ref.item.savedUri)).let { actual ->
                                actual.status == SavedFileStatus.PRESENT && actual.ref?.let { CollectionAddressResolver.matches(MediaOrganizationPolicy.keys(ref),it) } == true
                            })
                    })
            }
            checked to statuses
        }
        if ((checked.any { it.item.mediaType == MediaType.AUDIO } && libraryModel.musicAccessEpoch.value != audioEpoch) ||
            (checked.any { it.item.mediaType != MediaType.AUDIO } && libraryModel.galleryAccessEpoch.value != visualEpoch) ||
            com.domenota.medialoader.data.storage.HistoricalRecoverySignals.isRunning.value) return emptyList()
        val keys = libraryModel.privateKeys.value ?: return emptyList()
        val liveFull = currentCollectionCatalog.byKey
        val liveScoped = currentScopedCatalog?.byKey.orEmpty()
        val liveMusic = currentRecentCatalog.byKey
        fun live(id: String) = if (analytics) currentAudioRef(id) else if (recent) liveMusic[id] else liveScoped[id] ?: liveFull[id]
        val updates = statuses.filter { (id, result) -> live(id)?.item?.savedUri == result.first && id !in keys }
        if (updates.any { (id, result) -> fileStatusUris[id] != result.first || fileStatuses[id] != result.second }) {
            if (statusScanActive) statusOverrides.putAll(updates)
            fileStatusUris = fileStatusUris + updates.mapValues { it.value.first }
            fileStatuses = fileStatuses + updates.mapValues { it.value.second }
            collectionRefreshEpoch++
        }
        val finalHidden = com.domenota.medialoader.ui.screens.music.MusicTrackVisibility.blockedIdentities(
            musicVisibilityPrefs.getStringSet("hidden_tracks",emptySet()).orEmpty())
        val excluded = musicVisibilityPrefs.getStringSet("excluded",emptySet()).orEmpty()
        val allowedTypes = MediaType.entries.filter { type -> PhoneMediaLibrary.canRead(type,Build.VERSION.SDK_INT) {
            ContextCompat.checkSelfPermission(context,it)==PackageManager.PERMISSION_GRANTED } }.toSet()
        return checked.filter { ref -> live(ref.key)?.item?.savedUri == ref.item.savedUri && !collectionRefBlocked(ref, keys) &&
            !collectionRefBlocked(ref, currentBlocked) && !collectionRefBlocked(ref, finalHidden) &&
            DuplicatePrivacyGuard.accepts(context,actionPrivacyEpoch,ref.appDownloadId ?: ref.key,ref.aliases) &&
            (ref.appDownloadId != null || ref.item.mediaType in allowedTypes) && (!(recent || analytics) ||
                com.domenota.medialoader.ui.screens.music.MusicFolderRules.visible(ref.item,excluded,currentRecentFolders)) }
    }
    val reviewCount=remember(reviewEntries,organizationRefs){val ids=organizationRefs.mapNotNull{it.appDownloadId}.toHashSet();reviewEntries.count{it.downloadId in ids}}
    val consumePlayerRequest by rememberUpdatedState(onCurrentPlayerRequestConsumed)
    // Notification entry observes the existing controller only. Opening an ordinary audio row
    // invokes audioPreview.open and may replace its queue, so that path must never be reused here.
    LaunchedEffect(currentPlayerRequest) {
        if (currentPlayerRequest <= 0) return@LaunchedEffect
        val request = currentPlayerRequest
        try { libraryModel.audioPreview.observeCurrent() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            AppNotice.post(context, "Текущий плеер недоступен")
            consumePlayerRequest(request)
        }
    }
    LaunchedEffect(currentPlayerRequest, libraryAudioState, libraryIndex.ready,
        indexedLibraryProjection.ready, libraryAliasProjection.ready, libraryPrivateKeys,publicSessionProjection.ready,
        publicLibraryRows, organizationHidden) {
        if (currentPlayerRequest <= 0) return@LaunchedEffect
        val request = currentPlayerRequest
        val decision = com.domenota.medialoader.data.playback.CurrentPlayerNavigation.resolve(
            controllerReady = libraryAudioState.connected,
            catalogReady = libraryIndex.ready && indexedLibraryProjection.ready && libraryAliasProjection.ready && (libraryAudioState.queue.isEmpty()||publicSessionProjection.ready),
            privateReady = libraryPrivateKeys != null,
            currentItem = libraryAudioState.currentItem,
            rows = publicLibraryRows,
            blockedIdentities = libraryPrivateKeys.orEmpty() + organizationHidden)
        when (decision) {
            com.domenota.medialoader.data.playback.CurrentPlayerDestination.Wait -> Unit
            com.domenota.medialoader.data.playback.CurrentPlayerDestination.Unavailable -> {
                AppNotice.post(context, "Текущий плеер недоступен")
                consumePlayerRequest(request)
            }
            is com.domenota.medialoader.data.playback.CurrentPlayerDestination.Open -> {
                navController.navigate("viewer/${android.net.Uri.encode(decision.row.id)}") { launchSingleTop = true }
                navController.currentBackStackEntry?.savedStateHandle?.apply {
                    set("private_viewer", false)
                    set("gallery_presentation", false)
                    set("current_audio_session_only", true)
                }
                consumePlayerRequest(request)
            }
        }
    }

    // Hide from public Gallery/Music, not from the original Downloads history.
    // No stale MediaStore URI is considered a confirmed readable SAF binding.
    val nomediaHistoryIds = remember(catalogAssignments,nomediaSnapshot) {
        catalogAssignments.filter { it.folderId in nomediaSnapshot.hiddenFolderIds }
            .mapTo(mutableSetOf()) { it.downloadId }
    }
    val downloadItems = remember(downloads, fileStatuses, visibilityGuard, folderRefs,nomediaHistoryIds) {
        downloads.filter { row ->
            visibilityGuard?.canOpen(row,null,emptyList()) == true ||
            (row.id in nomediaHistoryIds && !row.hidden && !row.isPrivate &&
                visibilityGuard?.let { guard ->
                    !guard.policy.unknownPending &&
                    guard.policy.pendingFolder == null &&
                    guard.policy.snapshot.receipts.none { it.downloadId == row.id }
                } == true) ||
            folderRefsById[row.id]?.item?.savedUri == row.savedUri && row.savedUri != null
        }.map { it.toUi(fileStatuses[it.id] ?: SavedFileStatus.UNCHECKED) }
    }
    val recentDownloadItems = remember(publicDownloads,fileStatuses) { publicDownloads.map { it.toUi(fileStatuses[it.id] ?: SavedFileStatus.UNCHECKED) } }

    fun navigateTo(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    val goToDownloads: () -> Unit = {
        showPicker = false
        navigateTo(AppDestination.Downloads.route)
    }
    val afterDownload: () -> Unit = {
        val musicLookupAvailable = preview.items.any {
            MusicLookupPolicy.supports(it.providerId) &&
                (it.type == com.domenota.medialoader.core.model.MediaType.AUDIO ||
                    it.type == com.domenota.medialoader.core.model.MediaType.VIDEO)
        }
        if(preview.recoveryHistoryId!=null){
            showPicker=false
            if(currentRoute==PREVIEW_ROUTE)navController.popBackStack()
        }else if (musicLookupAvailable && !closePickerAfterDownload) {
            // Keep analyzed assets available for both audio choices without another analysis.
            AppNotice.post(context, "Добавлено в загрузки. Можно выбрать второй вариант.")
        } else if (musicLookupAvailable && showPicker) {
            showPicker = false
        } else if (currentRoute == BROWSER_ROUTE) {
            showPicker = false
        } else {
            goToDownloads()
        }
    }

    val loginLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        viewModel.onLoginFinished(result.resultCode == Activity.RESULT_OK)
    }
    val startLogin: () -> Unit = { loginLauncher.launch(Intent(context, LoginActivity::class.java)) }
    val youtubeLoginLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        viewModel.onYouTubeLoginFinished(result.resultCode == Activity.RESULT_OK)
    }
    val startYouTubeLogin: () -> Unit = {
        youtubeLoginLauncher.launch(Intent(context, YouTubeLoginActivity::class.java))
    }
    val vkLoginLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        viewModel.onVkLoginFinished(result.resultCode == Activity.RESULT_OK)
    }
    val startVkLogin: () -> Unit = {
        vkLoginLauncher.launch(Intent(context, VkLoginActivity::class.java))
    }
    val rutubeLoginLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        viewModel.onRutubeLoginFinished(result.resultCode == Activity.RESULT_OK)
    }
    val startRutubeLogin: () -> Unit = {
        rutubeLoginLauncher.launch(Intent(context, RutubeLoginActivity::class.java))
    }
    val startLoginForCurrentUrl: () -> Unit = {
        val target = preview.url.ifBlank { pendingUrl }
        when {
            YouTubeLinkParser.parse(target) != null -> startYouTubeLogin()
            VkLinkParser.parse(target) != null -> startVkLogin()
            RutubeLinkParser.parse(target) != null -> startRutubeLogin()
            else -> startLogin()
        }
    }

    // Android 8–9 need the legacy storage permission for the public Downloads folder.
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            viewModel.downloadSelected(pendingNames, afterDownload)
        } else {
            viewModel.showMessage("Для сохранения в Загрузки на Android 8–9 нужен доступ к хранилищу.")
        }
    }
    val deferredPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.retryDeferred()
    }
    val retryDeferred: () -> Unit = {
        if (Build.VERSION.SDK_INT < 29 && viewModel.needsLegacyStoragePermission() &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
            deferredPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        else viewModel.retryDeferred()
    }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.selectFolder(uri)
    }
    val confirmDownload: () -> Unit = {
        val needsPermission = Build.VERSION.SDK_INT < 29 && viewModel.needsLegacyStoragePermission() &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        if (needsPermission) permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        else viewModel.downloadSelected(pendingNames, afterDownload)
    }
    val requestDownload: () -> Unit = {
        if(preview.recoveryHistoryId!=null){
            pendingNames=emptyMap();confirmDownload()
        }else{
            pendingNames = BatchFileNames.defaults(preview.items.filter { it.id in preview.selectedIds }
                .map { it.id to it.originalName })
            batchName = ""
            if (pendingNames.isNotEmpty()) showNameDialog = true
        }
    }
    val selectedNameItems = preview.items.filter { it.id in preview.selectedIds }
    val resetNames = BatchFileNames.platformDefaults(selectedNameItems)
    val batchCandidates = runCatching {
        BatchFileNames.commonInputNames(selectedNameItems, batchName)
    }.getOrNull()
    if (showNameDialog) AlertDialog(
        onDismissRequest = { showNameDialog = false },
        title = { Text("Имена файлов") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                if (selectedNameItems.size > 1) {
                    OutlinedTextField(
                        value = batchName,
                        onValueChange = { input ->
                            batchName = input
                            runCatching { BatchFileNames.commonInputNames(selectedNameItems, input) }
                                .getOrNull()?.let { pendingNames = it }
                        },
                        label = { Text("Общее название") },
                        colors = fileNameFieldColors(),
                        singleLine = true,
                        isError = batchName.isNotBlank() && batchCandidates == null,
                    )
                    TextButton(onClick = {
                        pendingNames = resetNames
                        batchName = ""
                    }) { Text("Сбросить все") }
                }
                selectedNameItems.forEach { item ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = pendingNames[item.id] ?: StorageNaming.editableStem(item.originalName),
                            onValueChange = { pendingNames = pendingNames + (item.id to it) },
                            label = { Text("Файл ${item.position ?: 1}") },
                            colors = fileNameFieldColors(),
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = {
                            pendingNames = pendingNames + (item.id to resetNames.getValue(item.id))
                        }) {
                            Icon(Icons.Rounded.Restore,
                                contentDescription = "Стандартное имя файла ${item.position ?: 1}")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { showNameDialog = false; confirmDownload() },
                enabled = batchCandidates != null,
            ) {
                Text("Скачать")
            }
        },
        dismissButton = { TextButton(onClick = { showNameDialog = false }) { Text("Отмена") } },
    )
    var openVisualRequest by remember { mutableStateOf(0L) }
    val openDownloadWithQueue: (String, List<String>) -> Unit = { id, queueIds ->
        val request = ++openVisualRequest
        val originAlbumKey = openingAlbumKey
        val explicitFolder = originAlbumKey?.takeIf { it.startsWith("folder:") }?.removePrefix("folder:")
        val originPlaylistId = openingPlaylistId
        openingAlbumKey = null
        openingPlaylistId = null
        // Downloads renders the current Room history, while the derived public
        // index can still be preparing. Prefer that exact visual history row.
        val directVisual = downloads.firstOrNull { it.id == id && it.mediaType != MediaType.AUDIO && canOpenFolderMedia(it,explicitFolder) }
        val explicitRef = folderRefsById[id]?.takeIf { explicitFolder != null && canOpenFolderMedia(it.item,explicitFolder) }
        (explicitRef?.item ?: directVisual ?: accessibleDownloads.firstOrNull { it.id == id } ?: libraryRefsByAlias[id]?.item
            ?: currentLibraryAudioId?.takeIf { id == libraryAudioState.currentId }?.let { libraryRefsByAlias[it]?.item })?.takeIf { canOpenFolderMedia(it,explicitFolder) }?.let { item ->
          fileActionScope.launch {
            val status = withContext(Dispatchers.IO) { availabilityCache.get(item.savedUri, fresh = true) }
            if (request != openVisualRequest || !canOpenFolderMedia(item,explicitFolder)) return@launch
            if(!item.isPrivate && status==SavedFileStatus.PRESENT && !libraryModel.verifyVisibilityOpen(item,explicitFolder)) {
                AppNotice.post(context,"Идентичность файла изменилась или видимость не подтверждена")
                return@launch
            }
            val appVisual = !item.isPrivate && item.mediaType != MediaType.AUDIO &&
                (directVisual != null || explicitRef?.appDownloadId == item.id || libraryRefsByAlias[id]?.appDownloadId == item.id)
            if (appVisual && viewModel.downloads.value.none { row ->
                    row.id == item.id && row.savedUri == item.savedUri && !row.isPrivate && !row.hidden
                }) {
                AppNotice.post(context, "Выбранный файл изменился или недоступен")
                return@launch
            }
            if (statusScanActive) statusOverrides[id] = item.savedUri to status
            fileStatusUris = fileStatusUris + (id to item.savedUri)
            fileStatuses = fileStatuses + (id to status)
            if (status == SavedFileStatus.MISSING) {
                AppLog.w("History", "Open prevented · saved file missing · id=$id")
                AppNotice.post(context, "Файл удалён с устройства")
            } else if (status == SavedFileStatus.INACCESSIBLE) {
                AppNotice.post(context, "Нет доступа к файлу")
            } else if ((internalViewerEnabled || item.isPrivate || appMode != AppMode.DOWNLOADER || queueIds.isNotEmpty()) && item.state == DownloadState.COMPLETED && item.savedUri != null) {
                if (item.mediaType == MediaType.AUDIO && !item.isPrivate) {
                    val scopedHidden = explicitRef != null
                    val rows=if (scopedHidden) liveFolderRefs.filter { canOpenFolderMedia(it.item,explicitFolder) &&
                        libraryModel.visibilityGuard.value?.explicitFolder(it)==explicitFolder }.map { it.item } else publicLibraryRows
                    val refsByAlias=if(scopedHidden) folderRefsById else libraryRefsByAlias
                    val statuses=fileStatuses
                    val accessEpoch=libraryModel.musicAccessEpoch.value
                    val privacyEpoch=DuplicatePrivacyGuard.generation(context)
                    val (ref,queue)=withContext(Dispatchers.IO) {
                        val selectedRows=LibraryAudioQueue.resolve(queueIds,rows,false,statuses)
                        fun resolve(row:com.domenota.medialoader.core.database.DownloadEntity):LibraryMediaRef =
                            refsByAlias[row.id]?.takeIf { it.item.savedUri==row.savedUri } ?: LibraryMediaRef(
                                row.id,com.domenota.medialoader.data.playback.PublicMediaIdentity.uriKeys(requireNotNull(row.savedUri))+row.id,
                                row,row.id.takeUnless { it.startsWith("phone:") })
                        val selected=(listOf(item)+selectedRows).distinctBy{it.savedUri}.map(::resolve).associateBy{it.item.savedUri}
                        val base=requireNotNull(selected[item.savedUri])
                        val exact=refsByAlias[item.id]?.takeIf{it.item.savedUri==item.savedUri} ?: base.copy(aliases=base.aliases+
                            com.domenota.medialoader.data.playback.PublicMediaIdentity.capture(context,requireNotNull(item.savedUri)))
                        val ordered=selectedRows.mapNotNull{selected[it.savedUri]}
                        exact to (if(ordered.any{it.key==exact.key}) ordered.map{if(it.key==exact.key)exact else it} else listOf(exact)+ordered)
                    }
                    val livePrivacy=libraryModel.privateKeys.value ?: return@launch
                    if(request!=openVisualRequest || accessEpoch!=libraryModel.musicAccessEpoch.value || privacyEpoch!=DuplicatePrivacyGuard.generation(context) ||
                        collectionRefBlocked(ref,livePrivacy) || !canOpenFolderMedia(ref.item,explicitFolder) ||
                        (!scopedHidden && collectionRefBlocked(ref,currentBlocked))) return@launch
                    val permitted=queue.filterNot { collectionRefBlocked(it,livePrivacy) || !canOpenFolderMedia(it.item,explicitFolder) || (!scopedHidden && collectionRefBlocked(it,currentBlocked)) }
                    try { libraryModel.audioPreview.open(permitted,ref.key,originPlaylistId) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) {
                        AppNotice.post(context, "Не удалось подключиться к музыкальному плееру")
                        return@launch
                    }
                }
                if (request != openVisualRequest) return@launch
                navController.navigate("viewer/${android.net.Uri.encode(item.id)}")
                // A phone library may have 10k+ items. Never persist those IDs
                // in Navigation SavedStateHandle: it can crash onStop with
                // TransactionTooLargeException when Android saves the Activity.
                val viewerEntry = navController.currentBackStackEntry
                val token = ViewerQueueCache.store(queueIds)
                viewerEntry?.savedStateHandle?.set("grid_viewer_token", token)
                viewerEntry?.savedStateHandle?.set("gallery_origin_album", originAlbumKey)
                viewerEntry?.savedStateHandle?.set("folder_visibility_scope", explicitFolder.takeIf { explicitRef != null })
                if (!item.isPrivate && item.mediaType != MediaType.AUDIO)
                    viewerEntry?.savedStateHandle?.set("gallery_selection",
                        arrayListOf(item.id, requireNotNull(item.savedUri), if (appVisual) "app" else "index"))
                // Explicitly carry the vault context. The public gallery index
                // intentionally excludes private records and must never be used
                // to resolve a private viewer request.
                viewerEntry?.savedStateHandle?.set("private_viewer",
                    item.isPrivate && item.mediaType != MediaType.AUDIO)
                navController.currentBackStackEntry?.savedStateHandle?.set("gallery_presentation",
                    item.mediaType != MediaType.AUDIO &&
                        (item.isPrivate || appMode == AppMode.GALLERY || queueIds.isNotEmpty()))
            } else {
                openSavedFile(context, item)
            }
          }
        }
    }
    val openDownload: (String) -> Unit = { openDownloadWithQueue(it, emptyList()) }
    fun shareScopedFile(id:String,folder:String?=null) {
        val explicit=folder?.let { scope -> folderRefsById[id]?.takeIf { libraryModel.visibilityGuard.value?.explicitFolder(it)==scope } }
        val item=explicit?.item ?: publicLibraryRows.firstOrNull { it.id==id && !it.isPrivate }
            ?: downloads.firstOrNull { it.id==id && canOpenFolderMedia(it) }
        if(item!=null) fileActionScope.launch {
            if(libraryModel.verifyVisibilityOpen(item,folder)) shareSavedFile(context,item)
            else AppNotice.post(context,"Файл недоступен")
        }
    }
    val shareDownload: (String) -> Unit = { shareScopedFile(it) }
    val shareLibraryFile: (String) -> Unit = { shareScopedFile(it) }

    // Store ID/URI pairs rather than re-looking up provider rows after Android deletes them.
    // Large multi-select operations are transient; persisting every ID/URI
    // pair into Android SavedState can exhaust the Activity Bundle.
    var pendingDeleteRecords by remember { mutableStateOf(arrayListOf<String>()) }
    var pendingTrashOnly by remember { mutableStateOf(false) }
    val deleteLibraryFiles: (List<String>) -> Unit = { ids ->
        runRestoredAction(ids) { restored ->
            pendingTrashOnly = false
            val appById = accessibleDownloads.associateBy { it.id }
            pendingDeleteRecords = ArrayList(ids.distinct().mapNotNull { id ->
                val row=restored[id] ?: libraryRefsByAlias[id]?.item ?: appById[id]
                row?.takeUnless { it.isPrivate || it.hidden || (restored[id]==null && !canOpenFolderMedia(it)) }
                    ?.let { listOf(id,it.savedUri.orEmpty()) }
            }.flatten())
        }
    }
    val deleteLibraryFile: (String) -> Unit = { deleteLibraryFiles(listOf(it)) }
    var albumTrash by remember { mutableStateOf<com.domenota.medialoader.ui.components.AlbumTrashRequest?>(null) }
    fun albumMemberIds(folderId: String): Set<String> =
        viewModel.catalogAssignments.value.filter { it.folderId == folderId }.map { it.downloadId }.toSet() +
            libraryModel.externalCatalog.entries.value.filter { it.folderId == folderId }.map { it.ref.key }
    val requestAlbumTrash: (String,List<String>) -> Unit = request@{ folderId, ids ->
        if(folderId in nomediaSnapshot.hiddenFolderIds) {
            AppNotice.post(context,"Сначала покажите папку и проверьте доступ к файлам")
            return@request
        }
        if(folderId in visibilityFolders) {
            fileActionScope.launch {
                try {
                    val selected=ids.distinct().mapNotNull { folderRefsById[it] }
                    val members=viewModel.albumMemberIds(folderId)+libraryModel.externalCatalog.albumMemberIds(folderId)
                    check(selected.isNotEmpty() && selected.size==ids.distinct().size && selected.map { it.key }.toSet()==members &&
                        selected.all { libraryModel.visibilityGuard.value?.explicitFolder(it)==folderId &&
                            it.item.mediaType in setOf(MediaType.PHOTO,MediaType.VIDEO) }) { "Состав скрытого альбома не подтверждён полностью" }
                    for(ref in selected) check(libraryModel.verifyVisibilityOpen(ref.item,folderId)) { "Файл скрытого альбома изменился" }
                    check(android.os.Build.VERSION.SDK_INT>=30)
                    toggleFolderVisibility(folderId,false)
                    check(!mediaRepository.folderVisibility.isHidden(folderId) && !mediaRepository.folderVisibility.pending().pending)
                    val fresh=withTimeoutOrNull(20_000L) {
                        androidx.compose.runtime.snapshotFlow { liveLibraryRefsByAlias }.first { aliases ->
                            selected.all { old -> aliases[old.key]?.let { it.item.savedUri!=old.item.savedUri && it.key==old.key }==true }
                        }
                    } ?: error("Файлы восстановлены. Дождитесь обновления медиатеки и повторите удаление альбома.")
                    val resolved=selected.map { checkNotNull(fresh[it.key]) }
                    check(members==viewModel.albumMemberIds(folderId)+libraryModel.externalCatalog.albumMemberIds(folderId)) { "Состав альбома изменился" }
                    for(ref in resolved) check(libraryModel.verifyVisibilityOpen(ref.item,null)) { "Восстановленный файл изменился" }
                    albumTrash=com.domenota.medialoader.ui.components.AlbumTrashRequest(folderId,resolved,members)
                } catch(e:CancellationException){throw e}
                catch(e:Exception){AppNotice.post(context,e.message?:"Не удалось удалить скрытый альбом")}
            }
            return@request
        }
        val resolved = ids.distinct().mapNotNull { libraryRefsByAlias[it] }.distinctBy { it.item.savedUri }
        val expectedAliases = resolved.flatMap { it.aliases + it.key + listOfNotNull(it.appDownloadId) }.toSet()
        val members = albumMemberIds(folderId)
        if (!libraryIndex.ready || !libraryAliasProjection.ready || libraryPrivateKeys == null ||
            resolved.isEmpty() || resolved.size != ids.distinct().size || members.isEmpty() ||
            !expectedAliases.containsAll(members) || resolved.any { row ->
                row.item.savedUri.isNullOrBlank() || row.item.hidden || row.item.isPrivate ||
                row.item.mediaType !in setOf(MediaType.PHOTO, MediaType.VIDEO) ||
                (row.aliases + row.key + listOfNotNull(row.appDownloadId)).none { it in members }
            }) {
            AppNotice.post(context,"Состав альбома изменился или загружен не полностью. Файлы не перемещены.")
        } else albumTrash = com.domenota.medialoader.ui.components.AlbumTrashRequest(folderId,resolved,members)
    }
    val requestDeviceAlbumTrash: (String,List<String>) -> Unit = { bucket, ids ->
        val resolved = ids.distinct().mapNotNull(libraryRefsByAlias::get).distinctBy { it.item.savedUri }
        val members = publicLibraryRefs.filter { it.deviceAlbumId == bucket }.map { it.key }.toSet()
        if (!libraryIndex.ready || !libraryAliasProjection.ready || libraryPrivateKeys == null ||
            !com.domenota.medialoader.ui.screens.gallery.PhysicalAlbumPolicy.fullAccess(context) ||
            members.isEmpty() || resolved.map { it.key }.toSet() != members || resolved.size != ids.distinct().size ||
            resolved.any { it.item.savedUri.isNullOrBlank() || it.item.hidden || it.item.isPrivate ||
                it.item.mediaType !in setOf(MediaType.PHOTO,MediaType.VIDEO) }) {
            AppNotice.post(context,"Состав папки не подтверждён. Файлы не перемещены.")
        } else albumTrash = com.domenota.medialoader.ui.components.AlbumTrashRequest(bucket,resolved,members,physical = true)
    }
    albumTrash?.let { request ->
        com.domenota.medialoader.ui.components.AlbumTrashHost(
            request, refs = libraryRefsByAlias,
            currentMembers = {
                if (request.physical) publicLibraryRefs.filter { it.deviceAlbumId == request.folderId }.map { it.key }.toSet()
                else viewModel.albumMemberIds(request.folderId) + libraryModel.externalCatalog.albumMemberIds(request.folderId)
            },
            onAppTrash = viewModel::trashVerifiedAlbumMedia,
            onTrashed = { ref ->
                if (!request.physical) {
                    ref.appDownloadId?.let { viewModel.detachTrashedAlbumMember(it,request.folderId) }
                    libraryModel.externalCatalog.detachTrashedAlbumMember(ref,request.folderId)
                }
            },
            onRemoveAlbum = {
                if (!request.physical) libraryModel.externalCatalog.withEmptyAlbum(request.folderId) {
                    viewModel.deleteEmptyAlbum(request.folderId)
                }
            },
            onClose = {
                albumTrash = null
                libraryModel.refresh(AppMode.GALLERY, force = true)
            })
    }
    val duplicateTrash by viewModel.duplicateTrashOperation.collectAsStateWithLifecycle()
    val trashDuplicates: (com.domenota.medialoader.data.library.DuplicateTrashSelection) -> Unit = viewModel::startDuplicateTrash
    duplicateTrash?.let { operation ->
        com.domenota.medialoader.ui.components.DuplicateTrashHost(
            request = operation.request, refs = libraryRefsByAlias, currentId = currentLibraryAudioId,
            retained=operation,catalogReady=organizationUsable,
            onAppTrash = viewModel::trashVerifiedDuplicate,
            onTrashed = { uris ->
                com.domenota.medialoader.data.playback.AudioPlaybackService.removeTrashedFromQueue(uris.toList())
                MusicDuplicateSessionStore.removeTrashed(context, uris)
            },
            onClose = {
                viewModel.finishDuplicateTrash()
                libraryModel.refresh(AppMode.GALLERY, force = true)
                libraryModel.refresh(AppMode.MUSIC, force = true)
                duplicateRequest++
            },
        )
    }
    if (pendingDeleteRecords.isNotEmpty()) {
        val targets = remember(pendingDeleteRecords) { pendingDeleteRecords.chunked(2).map {
            MediaDeletionTarget(it[0], it[1].ifBlank { null })
        } }
        fun finishDeletion() {
            pendingDeleteRecords = arrayListOf()
            val duplicateOperation = pendingTrashOnly
            pendingTrashOnly = false
            if (duplicateOperation || targets.any { it.phone }) {
                libraryModel.refresh(AppMode.GALLERY, force = true)
                libraryModel.refresh(AppMode.MUSIC, force = true)
            }
        }
        LibraryMediaDeleteHost(targets, onDismiss = ::finishDeletion,
            onDeleteApp = viewModel::hideLibraryFile, onDeleteAppPermanently = viewModel::deleteLibraryFilePermanently, onDeleted = ::finishDeletion,
            trashOnly = pendingTrashOnly, onTrashed = { done -> if (pendingTrashOnly) {
                val uris = done.mapNotNull { it.uri }
                com.domenota.medialoader.data.playback.AudioPlaybackService.removeTrashedFromQueue(uris)
                fileActionScope.launch { runCatching { MusicDuplicateSessionStore.removeTrashed(context, uris.toSet()) } }
            } })
    }
    fun toggleLibraryPlayback() {
        fileActionScope.launch {
            try { libraryModel.togglePublicAudio() }
            catch(e:CancellationException){throw e}
            catch(_:Exception){AppNotice.post(context,"Трек или доступ изменились")}
        }
    }
    fun previewLibraryAudioInContext(id: String, ids: List<String>, playlistId: String?) {
        libraryPreviewJob?.cancel()
        libraryPreviewJob = fileActionScope.launch {
            val ref = libraryRefsByAlias[id] ?: return@launch
            try {
                val status = withContext(Dispatchers.IO) { availabilityCache.get(ref.item.savedUri, fresh = true) }
                if (status != SavedFileStatus.PRESENT) {
                    AppNotice.post(context, "Трек недоступен. Проверьте файл и разрешения.")
                } else {
                    val queue = ids.mapNotNull(libraryRefsByAlias::get).distinctBy { it.key }
                    libraryModel.audioPreview.preview(queue, ref.key, playlistId)
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { AppNotice.post(context, "Не удалось начать воспроизведение") }
        }
    }

    val previewLibraryAudio: (String,List<String>) -> Unit = { id, ids -> previewLibraryAudioInContext(id,ids,null) }
    fun playRecentMusic(ids:List<String>,shuffle:Boolean,requestedId:String?=null,openPlayer:Boolean=false,analytics:Boolean=false){
        libraryPreviewJob?.cancel()
        libraryPreviewJob=fileActionScope.launch {
            try {
                val valid=validCollectionRefs(ids,recent=!analytics,analytics=analytics,selectedId=requestedId ?: ids.firstOrNull()).filter{it.item.mediaType==MediaType.AUDIO}
                check(valid.isNotEmpty() && (requestedId==null || valid.any{it.key==requestedId})) { "Треки недоступны" }
                if(requestedId!=null && openPlayer){
                    libraryModel.audioPreview.open(valid,requestedId)
                    navController.navigate("viewer/${android.net.Uri.encode(requestedId)}") { launchSingleTop=true }
                    navController.currentBackStackEntry?.savedStateHandle?.apply{
                        set("private_viewer",false);set("gallery_presentation",false);set("current_audio_session_only",true)
                    }
                } else if(requestedId!=null) libraryModel.audioPreview.preview(valid,requestedId)
                else libraryModel.audioPreview.playSelection(valid,shuffle)
            } catch(e:CancellationException){throw e}
            catch(_:Exception){AppNotice.post(context,"Нет доступных треков или плеер недоступен")}
        }
    }
    if (preview.duplicateMatches.isNotEmpty()) {
        val duplicates = preview.duplicateMatches
        val selectedCount = preview.selectedIds.size
        val singleExisting = duplicates.singleOrNull()?.takeIf { selectedCount == 1 }
        AlertDialog(
            onDismissRequest = viewModel::dismissDuplicatePrompt,
            title = { Text("Уже скачано") },
            text = {
                Text(
                    if (singleExisting != null) {
                        "«${singleExisting.name}» уже есть в загрузках."
                    } else {
                        "${duplicates.size} из $selectedCount выбранных файлов уже скачаны. " +
                            "Можно оставить существующие файлы и скачать только новые."
                    },
                )
            },
            confirmButton = {
                Row {
                    if (duplicates.size < selectedCount) {
                        TextButton(onClick = {
                            viewModel.downloadSelected(
                                pendingNames,
                                afterDownload,
                                DuplicateDownloadPolicy.SKIP_EXISTING,
                            )
                        }) { Text("Только новые") }
                    }
                    TextButton(onClick = {
                        viewModel.downloadSelected(
                            pendingNames,
                            afterDownload,
                            DuplicateDownloadPolicy.ALLOW_EXISTING,
                        )
                    }) { Text("Скачать ещё раз") }
                }
            },
            dismissButton = {
                Row {
                    if (singleExisting != null) {
                        TextButton(onClick = {
                            viewModel.dismissDuplicatePrompt()
                            showPicker = false
                            openDownload(singleExisting.historyId)
                        }) { Text("Открыть") }
                    }
                    TextButton(onClick = viewModel::dismissDuplicatePrompt) { Text("Отмена") }
                }
            },
        )
    }
    val reanalyzeMissingFile: (String) -> Unit = { id ->
        val old = downloads.firstOrNull { it.id == id }
        val link = listOfNotNull(old?.sourcePageUrl, old?.sourceUrl).firstOrNull { candidate ->
            InstagramLinkParser.parse(candidate) != null || YouTubeLinkParser.parse(candidate) != null ||
                VkLinkParser.parse(candidate) != null || RutubeLinkParser.parse(candidate) != null
        }
        if (link == null) {
            AppNotice.post(context, "Исходная ссылка недоступна — повторный поиск невозможен")
        } else {
            pendingUrl = link
            lastHandledLink = link
            navigateTo(AppDestination.Home.route)
            viewModel.analyze(link, recoveryHistoryId = id)
            showPicker = true
        }
    }

    LaunchedEffect(shortcutMode, shortcutGeneration) {
        val mode = shortcutMode ?: return@LaunchedEffect
        if (mode != AppMode.GALLERY && mode != AppMode.MUSIC) return@LaunchedEffect
        viewModel.privateAccess.lock()
        privateNavigation = false
        hiddenMusicTracksOpen = false
        galleryAlbumRequest = null
        galleryTab = 0
        if (mode == AppMode.MUSIC && !shortcutDuplicates) { musicTab = 0; musicTopRequest++ }
        changeAppMode(mode)
        navController.navigate(AppDestination.Home.route) {
            popUpTo(AppDestination.Home.route) { inclusive = false }
            launchSingleTop = true
        }
        if (shortcutDuplicates && mode == AppMode.GALLERY) navController.navigate("visual-duplicates") { launchSingleTop = true }
        else if (shortcutDuplicates) duplicateRequest++
        onShortcutConsumed()
    }
    LaunchedEffect(sharedText, sharedTextGeneration) {
        if (!sharedText.isNullOrBlank()) {
            // ACTION_SEND is explicit user intent and always wins over clipboard auto-import.
            // Mark the launch clipboard check as already handled so an older clipboard URL
            // cannot overwrite the newly shared URL when the activity resumes.
            checkedClipboardAtLaunch = true
            pendingUrl = extractFirstUrl(sharedText) ?: sharedText
            lastHandledLink = pendingUrl
            navigateTo(AppDestination.Home.route)
            showPicker = true
            viewModel.analyze(pendingUrl)
            onSharedTextConsumed()
        }
    }

    var usedLinkPrompt by rememberSaveable { mutableStateOf<String?>(null) }
    var clipboardLookup by remember { mutableStateOf<Job?>(null) }
    fun sameLink(a:String,b:String)=com.domenota.medialoader.core.provider.PublicationIdentity.same(a,b)
    fun checkExplicitLink(link:String) {
        clipboardLookup?.cancel()
        lastHandledLink=link
        clipboardLookup=fileActionScope.launch {
            val used=try{viewModel.wasInstagramDownloaded(link)}catch(e:CancellationException){throw e}catch(e:Exception){false}
            if(used)usedLinkPrompt=link else {viewModel.analyze(link);showPicker=true}
        }
    }
    usedLinkPrompt?.let{link->
        AlertDialog(onDismissRequest={usedLinkPrompt=null},title={Text("Ссылка уже использовалась")},
            text={Text("Публикация есть в истории. Повторная проверка может расходовать сетевые запросы.")},
            confirmButton={TextButton({usedLinkPrompt=null;viewModel.analyze(link);showPicker=true}){Text("Проверить заново")}},
            dismissButton={TextButton({usedLinkPrompt=null;goToDownloads()}){Text("История")}})
    }
    // Read only while the downloader is visible. Never replace a link the user typed herself.
    val clipboardView=androidx.compose.ui.platform.LocalView.current
    DisposableEffect(lifecycleOwner, currentRoute, sharedText, appMode, clipboardView) {
        fun importSupportedLink() {
            if (currentRoute != AppDestination.Home.route || sharedText != null || appMode != AppMode.DOWNLOADER || !clipboardView.hasWindowFocus()) return
            val firstCheck = !checkedClipboardAtLaunch
            val candidate = clipboardManager.getText()?.text?.let(::extractFirstUrl) ?: return
            checkedClipboardAtLaunch = true
            if (InstagramLinkParser.parse(candidate) == null && YouTubeLinkParser.parse(candidate) == null &&
                VkLinkParser.parse(candidate) == null && RutubeLinkParser.parse(candidate) == null) return
            if (sameLink(candidate,dismissedClipboard)) return
            if (firstCheck || pendingUrl.isBlank() || pendingUrl == lastImportedClipboard) {
                pendingUrl = candidate
                if (!sameLink(candidate,lastHandledLink)) {
                    lastHandledLink=candidate
                    clipboardLookup?.cancel()
                    clipboardLookup=fileActionScope.launch {
                        val used=try{viewModel.wasInstagramDownloaded(candidate)}catch(e:CancellationException){throw e}catch(e:Exception){true}
                        if(!used && pendingUrl==candidate && appMode==AppMode.DOWNLOADER &&
                            navController.currentDestination?.route==AppDestination.Home.route){
                            showPicker=true
                            viewModel.analyze(candidate)
                        }
                    }
                }
                lastImportedClipboard = candidate
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) importSupportedLink()
        }
        val focus=android.view.ViewTreeObserver.OnWindowFocusChangeListener{if(it)importSupportedLink()}
        lifecycleOwner.lifecycle.addObserver(observer)
        clipboardView.viewTreeObserver.addOnWindowFocusChangeListener(focus)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) importSupportedLink()
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); if(clipboardView.viewTreeObserver.isAlive)clipboardView.viewTreeObserver.removeOnWindowFocusChangeListener(focus) }
    }

    var collectionNoticeFooterPx by remember { mutableIntStateOf(0) }
    val noticeDensity = LocalDensity.current
    val collectionNoticeBottom = with(noticeDensity) {
        (collectionNoticeFooterPx + WindowInsets.navigationBars.getBottom(noticeDensity)).toDp()
    }
    val checkingPhoneAccess = if (collectionRouteActive) false
    else if (currentRoute == AppDestination.Home.route && appMode != AppMode.DOWNLOADER)
        visibleLibraryValidationPending(appMode, libraryIndex, phoneGallery, phoneMusic)
    else phoneValidationPending(libraryIndex, phoneGallery, phoneMusic)
    MediaManagementAccessPrompt(enabled = appMode != AppMode.DOWNLOADER &&
        currentRoute == AppDestination.Home.route && !checkingPhoneAccess)
    LibraryTheme(enabled = (appMode != AppMode.DOWNLOADER && currentRoute in setOf(AppDestination.Home.route, MEDIA_VIEWER_ROUTE)) || currentRoute in setOf(PLAYLIST_ADD_ROUTE, "equalizer")) {
    val galleryHomeOverlay = galleryBottomSwitcherIsOverlay(appMode, currentRoute)
    // Gallery media is drawn behind both transparent system bars. Do not remove
    // the insets of the private/audio fallback viewer or the other app routes.
    val fullBleedGalleryViewer = currentRoute == MEDIA_VIEWER_ROUTE &&
        navController.currentBackStackEntry?.savedStateHandle
            ?.get<Boolean>("gallery_presentation") == true
    Scaffold(
        containerColor = if (currentRoute == VIDEO_EDITOR_ROUTE) Color(0xFF101010)
            else MaterialTheme.colorScheme.background,
        contentWindowInsets = when {
            currentRoute in setOf("music-duplicates", "visual-duplicates", "batch-photo-editor") -> WindowInsets.safeDrawing
            fullBleedGalleryViewer || currentRoute == "collections" -> WindowInsets(0, 0, 0, 0)
            galleryHomeOverlay -> WindowInsets.statusBars
            else -> ScaffoldDefaults.contentWindowInsets
        },
        bottomBar = {
            if (!galleryHomeOverlay && currentRoute != PRIVATE_ROUTE && currentRoute != PREVIEW_ROUTE && currentRoute != BROWSER_ROUTE &&
                currentRoute != BULK_ROUTE && currentRoute != MEDIA_VIEWER_ROUTE &&
                currentRoute != VIDEO_EDITOR_ROUTE && currentRoute != AUDIO_EDITOR_ROUTE &&
                currentRoute != PHOTO_EDITOR_ROUTE && currentRoute != PLAYLIST_ADD_ROUTE && currentRoute != "equalizer" &&
                currentRoute != "trash" && currentRoute != "trash-viewer/{downloadId}" && currentRoute != "collections" &&
                currentRoute != "diagnostics" && currentRoute != "vk-community" && currentRoute != "music-duplicates" && currentRoute != "visual-duplicates" && currentRoute != "batch-photo-editor") {
                Column(if (appMode != AppMode.DOWNLOADER && currentRoute == AppDestination.Home.route)
                    Modifier.navigationBarsPadding() else Modifier) {
                    // Gallery intentionally has no player chrome. Playback is
                    // owned by the service and continues independently of this UI.
                    if (appMode != AppMode.GALLERY && (libraryAudioState.currentId==null || currentLibraryAudioId!=null) && visibilityReady) AudioMiniPlayer(onToggle=::toggleLibraryPlayback,
                        onOpenQueue = openDownloadWithQueue,
                        onOpen = { mediaId ->
                            if (publicLibraryRows.any { it.id == mediaId && it.savedUri != null }) {
                                navController.navigate("viewer/$mediaId")
                            }
                        },
                    )
                    if (appMode != AppMode.DOWNLOADER && currentRoute == AppDestination.Home.route) {
                        if ((appMode == AppMode.GALLERY && !galleryDetailOpen) || (appMode == AppMode.MUSIC && !musicDetailOpen))
                            LibraryBottomSwitcher(appMode, if (appMode == AppMode.GALLERY) galleryTab else musicTab) {
                                if (appMode == AppMode.GALLERY) {
                                    if (galleryTab == 0 && it == 0) galleryTopRequest++
                                    galleryTab = it
                                } else {
                                    if (musicTab == it) musicTopRequest++
                                    musicTab = it
                                }
                            }
                    } else NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        AppDestination.entries.filter { appMode == AppMode.DOWNLOADER || it != AppDestination.Downloads }.forEach { destination ->
                            val destinationLabel = if (destination == AppDestination.Home && appMode != AppMode.DOWNLOADER) appMode.label else destination.label
                            val destinationIcon = if (destination == AppDestination.Home) when (appMode) {
                                AppMode.GALLERY -> Icons.Rounded.PhotoLibrary
                                AppMode.MUSIC -> Icons.Rounded.MusicNote
                                else -> destination.icon
                            } else destination.icon
                            NavigationBarItem(
                                selected = currentRoute == destination.route,
                                onClick = {
                                    if (appMode != AppMode.DOWNLOADER && destination == AppDestination.Home) {
                                        com.domenota.medialoader.ui.navigation.returnToLibrary(navController)
                                    } else if (BottomTabPolicy.shouldScrollDownloadsToTop(
                                            currentRoute, destination)) {
                                        downloadsScrollToTopRequest++
                                    } else {
                                        navController.navigate(destination.route) {
                                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    }
                                },
                                icon = { Icon(imageVector = destinationIcon, contentDescription = destinationLabel) },
                                label = { Text(destinationLabel) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    disabledIconColor = Color.Gray,
                                    disabledTextColor = Color.Gray,
                                ),
                            )
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize()) {
        LibraryRefreshShield(
            checkingPhoneAccess && currentRoute != AppDestination.Settings.route &&
                !(appMode == AppMode.DOWNLOADER && currentRoute == AppDestination.Home.route),
            Modifier.padding(innerPadding),
            onExit = if (appMode != AppMode.DOWNLOADER && currentRoute == AppDestination.Home.route)
                ({ changeAppMode(AppMode.DOWNLOADER) }) else null,
        ) {
        StableLibraryNavHost(
            navController = navController,
            startDestination = AppDestination.Home.route,
            modifier = Modifier.fillMaxSize(),
            routes = listOf(
            LibraryRouteContent(AppDestination.Home.route) {
                val modeStates = rememberSaveableStateHolder()
                modeStates.SaveableStateProvider(appMode.name) {
                if (appMode == AppMode.DOWNLOADER) HomeScreen(
                    title = { AppModeTitle(appMode, ::changeAppMode) },
                    url = pendingUrl,
                    onUrlChange = {
                        if (it.isEmpty() && pendingUrl == lastImportedClipboard) dismissedClipboard = lastImportedClipboard
                        pendingUrl = it
                    },
                    onPasteClick = {
                        clipboardManager.getText()?.text?.takeIf { it.isNotBlank() }?.let { pendingUrl = it }
                    },
                    onCheckClick = {
                        checkExplicitLink(pendingUrl)
                    },
                    deferredEntries = deferredEntries, deferredActive = deferredActive, deferredMessage = deferredMessage,
                    onDeferredOpen = { id -> deferredEntries.firstOrNull{it.id==id}?.let{entry->
                        pendingUrl=entry.url;lastHandledLink=entry.url;viewModel.openDeferredLink(id);showPicker=true
                    } },
                    onDeferredRemove = viewModel::removeDeferredLink, onDeferredRetryAll = retryDeferred, onDeferredStop = viewModel::stopDeferred,
                    onOpenDownloads = goToDownloads,
                    onAddCollection=::addToCollection,onPassport=::openPassport,
                    organizationContent=if (showHomeOrganization) ({ Column {
                        OrganizationEntry("На разбор","Новых загрузок: $reviewCount",{navController.navigate("media-review")})
                        OrganizationEntry("Коллекции","Сохранённые материалы и ссылки",{openCollections()},Modifier.padding(top=8.dp))
                        if(pendingUrl.isNotBlank()&&runCatching{MediaOrganizationPolicy.link(pendingUrl)}.isSuccess)
                            TextButton({openCollections(link=pendingUrl)}){Text("Сохранить ссылку в коллекцию")}
                    } }) else null,
                    onOpenSource = { source ->
                        showPicker = false
                        navController.navigate("browser/$source")
                    },
                    recentDownloads = recentDownloadItems.take(3),
                    onOpenDownload = openDownload,
                    onCancelDownload = viewModel::cancelDownload,
                    onShareDownload = shareDownload,
                    onHideDownload = deleteLibraryFile,
                    onRetryDownload = viewModel::retryDownload,
                    onReanalyzeMissing = reanalyzeMissingFile,
                    onRenameDownload = renameVisibilityFile,
                    recognitionEnabled = musicRecognitionMode != MusicRecognitionMode.OFF,
                    onRecognizeDownload = recognizeVisibilityFile,
                )
                else if (appMode == AppMode.GALLERY) GalleryModeScreen(
                    items = galleryBrowse.value.items,
                    preparedBrowse = galleryBrowse,
                    preparedBrowseAccess = galleryRetentionAccess,
                    hiddenFolderIds = visibilityFolders,
                    pendingVisibilityFolderId = null,
                    onFolderVisibility = toggleFolderVisibility.takeIf { android.os.Build.VERSION.SDK_INT >= 21 },
                    source = librarySource, loading = !galleryBrowse.usable || !libraryIndex.ready || (librarySource == LibrarySource.PHONE && !phoneGallery.loaded),
                    message = if (librarySource == LibrarySource.PHONE) phoneGallery.message else null,
                    folders = catalogFolders, assignments = libraryCatalogAssignments, header = libraryHeader,
                    onOpen = openDownloadWithQueue,
                    albumRequest = galleryAlbumRequest, onAlbumRequestHandled = { galleryAlbumRequest = null },
                    onOpenFromAlbum = { id, queue, album -> openingAlbumKey = album; openDownloadWithQueue(id,queue) },
                    onOpenPrivate = { viewModel.privateAccess.lock(); privateNavigation = true; navController.navigate(PRIVATE_ROUTE) },
                    onFavorite = setLibraryFavorite, onSetFolder = setLibraryFolder,
                    onCreateFolder = viewModel::createCatalogFolder,
                    onDeleteFolder = deleteVisibilityFolder, onShare = shareLibraryFile,
                    onShareFromAlbum = { id, album -> shareScopedFile(id,album?.takeIf { it.startsWith("folder:") }?.removePrefix("folder:")) },
                    onTrashFolderContents = requestAlbumTrash,
                    collectionContent={
                        Column {
                            CollectionGalleryStrip(collections,organizationRefs,onOpen={openCollections(it)},onCreate={openCollections(create=true)},blockedIdentities=collectionBlocked.takeIf{collectionReadAccess!=null},cachedCovers=if(organizationProjection.ready && organizationUsable) emptyMap() else cachedCollectionCovers, coverAccess=collectionCoverAccess, collectionsReady=collectionsLoaded)
                            TextButton({navController.navigate("visual-duplicates"){launchSingleTop=true}}){Text("Дубликаты фото и видео")}
                        }
                    },
                    onAddCollection=::addToCollection,
                    onBatchPhotoEdit={ids->runRestoredAction(ids) {
                        val coordinator=com.domenota.medialoader.data.editor.EditorExportCoordinator.get(context)
                        if(!coordinator.batch.value.running){coordinator.clearBatchReport();batchPhotoIds=ArrayList(ids)}
                        navController.navigate("batch-photo-editor"){launchSingleTop=true}
                    }},
                     onTrashDeviceAlbum = requestDeviceAlbumTrash,
                    onDelete = deleteLibraryFile, onDeleteSelected = deleteLibraryFiles,
                    onDeviceAlbumsChanged = { libraryModel.refresh(AppMode.GALLERY, force = true) },
                    onRename = renameVisibilityFile,
                    onMoveToPrivate = { requestPrivateMove(listOf(it)) },
                    searchVisible = librarySearchVisible, tabOverride = galleryTab, datePickerRequest = galleryDateRequest,
                     scrollToTopRequest = galleryTopRequest, onTabChange = {
                         if (galleryTab == 0 && it == 0) galleryTopRequest++
                         galleryTab = it
                     },
                    onDetailChange = { galleryDetailOpen = it }, onTrash = { navController.navigate("trash") },
                    deviceAlbums = displayInventory.deviceAlbums,
                    )
                else MusicModeScreen(
                    duplicateRequest = duplicateRequest,
                    onDuplicateRequestHandled = { duplicateRequest = 0 },
                    hiddenTracksOpen = hiddenMusicTracksOpen,
                    onDismissHiddenTracks = { hiddenMusicTracksOpen = false },
                    deviceMusicFolders = recentDeviceFolders,
                    items = musicHomeItems,
                    allAudio = libraryAudioItems, playlists = playlists,
                    recentItems = recentProjection.value.items,
                    recentLoading = !recentProjection.usable,
                    onRecentPlay = { ids, shuffle -> playRecentMusic(ids,shuffle) },
                    onRecentPreview = { id, ids -> playRecentMusic(ids,false,id) },
                    onRecentOpen = { id, ids -> playRecentMusic(ids,false,id,true) },
                    favoriteIds = currentAudioFavorites,
                    header = libraryHeader, loading = !libraryIndex.ready || (librarySource == LibrarySource.PHONE && !phoneMusic.loaded),
                    message = if (librarySource == LibrarySource.PHONE) phoneMusic.message else null,
                    onOpen = openDownloadWithQueue,
                    onOpenPrivate = { viewModel.privateAccess.lock(); privateNavigation = true; navController.navigate(PRIVATE_ROUTE) },
                    onCreate = libraryModel.playlistRepository::create, onRename = libraryModel.playlistRepository::rename,
                    onDeletePlaylist = libraryModel.playlistRepository::delete, onAddTracks = { id, ids ->
                        val aliases = publicLibraryRefs.flatMap { ref -> ref.aliases.map { it to ref.key } }.toMap()
                        libraryModel.playlistRepository.addTracksResolved(id, ids, aliases)
                    },
                    onRemoveTrack = libraryModel.playlistRepository::removeTrack, onMoveTrack = libraryModel.playlistRepository::moveTrack,
                    onShare = shareLibraryFile, onDeleteFile = deleteLibraryFile,
                    onTrashDuplicates = trashDuplicates,
                    onOpenDuplicates = { navController.navigate("music-duplicates") { launchSingleTop = true } },
                    onOpenCollections={openCollections(audioOnly=true)},onAddCollection=::addToCollection,onPassport=::openPassport,
                    onRecognize={id->openTrackRecognition(id)},
                    browseAccess = libraryPrivateKeys?.takeIf { musicBrowseAccess != null }
                        ?.let { Triple(it, if (musicLibrarySource == LibrarySource.APP) 0L else musicAccessEpoch, musicLibrarySource) },
                    preparedBrowse = musicBrowse,
                    analyticsItems = analyticsProjection.value.items,
                    analyticsAccess = analyticsGuard,
                    analyticsReady = analyticsProjection.usable,
                    listeningKeys = analyticsProjection.value.keys,
                    validateFrequent = { ids,fresh ->
                        val candidates=ids.mapNotNull(::currentAudioRef)
                        val verified=withContext(Dispatchers.IO) {
                            candidates.filter { ref -> availabilityCache.get(ref.item.savedUri,fresh=fresh)==SavedFileStatus.PRESENT }
                        }
                        verified.filter { ref -> currentAudioRef(ref.key)?.item?.savedUri==ref.item.savedUri }.mapTo(mutableSetOf()) { it.key }
                    },
                    onPlayFrequent = { ids,shuffle -> playRecentMusic(ids,shuffle,analytics=true) },
                    onFolderPlay = { ids,shuffle ->
                        libraryPreviewJob?.cancel()
                        libraryPreviewJob=fileActionScope.launch {
                            try {
                                val refs=ids.mapNotNull(libraryRefsByAlias::get).distinctBy{it.key}
                                val epoch=DuplicatePrivacyGuard.generation(context)
                                val access=libraryModel.musicAccessEpoch.value
                                val valid=withContext(Dispatchers.IO){refs.filter{availabilityCache.get(it.item.savedUri)==SavedFileStatus.PRESENT}}
                                val blocked=libraryModel.privateKeys.value ?: return@launch
                                val hidden=musicVisibilityPrefs.getStringSet("hidden_tracks",emptySet()).orEmpty()
                                check(access==libraryModel.musicAccessEpoch.value&&epoch==DuplicatePrivacyGuard.generation(context))
                                val safe=valid.filter{!collectionRefBlocked(it,blocked)&&!com.domenota.medialoader.ui.screens.music.MusicTrackVisibility.hidden(it.item,hidden)&&DuplicatePrivacyGuard.accepts(context,epoch,it.key,it.aliases)}
                                check(safe.isNotEmpty());libraryModel.audioPreview.playSelection(safe,shuffle)
                            }catch(e:CancellationException){throw e}catch(_:Exception){AppNotice.post(context,"Нет доступных треков или плеер недоступен")}
                        }
                    },
                    onAnalyticsPreview = { id,ids -> playRecentMusic(ids,false,id,analytics=true) },
                    onAnalyticsOpen = { id,ids -> playRecentMusic(ids,false,id,true,analytics=true) },
                    onResumeSelection = { resolved ->
                        libraryPreviewJob?.cancel()
                        libraryPreviewJob = fileActionScope.launch {
                            try {
                                val ids=resolved.availableIds
                                val verified=validCollectionRefs(ids,analytics=true,selectedId=resolved.currentId).associateBy { it.key }
                                val refs=ids.map { verified[it] ?: error("Состав очереди изменился") }
                                check(refs.getOrNull(resolved.currentIndex)?.key==resolved.currentId) { "Трек недоступен" }
                                libraryModel.audioPreview.resumeSelection(refs,resolved)
                            } catch(e:CancellationException){throw e}
                            catch(e:Exception){AppNotice.post(context,"Не удалось продолжить сеанс")}
                        }
                    },
                    onSaveFrequent = { name,ids ->
                        val refs=validCollectionRefs(ids,analytics=true)
                        check(refs.map{it.key}==ids.distinct()) { "Состав подборки изменился" }
                        val aliases=withContext(Dispatchers.Default) { refs.flatMap { ref -> (ref.aliases+ref.key).map { it to ref.key } }.toMap() }
                        libraryModel.playlistRepository.createFromTracks(name,refs.map{it.key},aliases); Unit
                    },
                    onFavorite = setLibraryFavorite,
                    searchVisible = librarySearchVisible, tabOverride = musicTab,
                     scrollToTopRequest = musicTopRequest, onTabChange = {
                         if (musicTab == it) musicTopRequest++
                         musicTab = it
                     },
                    onEnqueue = { id, next ->
                        fileActionScope.launch {
                            val ref = libraryRefsByAlias[id] ?: return@launch
                            try { libraryModel.audioPreview.enqueue(ref, next) }
                            catch (e: CancellationException) { throw e }
                            catch (_: Exception) { AppNotice.post(context, "Не удалось добавить трек в очередь") }
                        }
                    },
                    onPreview = previewLibraryAudio,
                    onPreviewFromPlaylist = { id, ids, playlistId -> previewLibraryAudioInContext(id,ids,playlistId) },
                    onOpenFromPlaylist = { id, ids, playlistId -> openingPlaylistId = playlistId; openDownloadWithQueue(id,ids) },
                    onAddPlaylistTracks = { navController.navigate("playlist-add/$it") },
                    onDetailChange = { musicDetailOpen = it }, currentPlayingId = currentLibraryAudioId,
                    playing = libraryAudioState.playing)
                }
            },
            LibraryRouteContent("collections") {
                fun playCollection(ids:List<String>,requestedId:String?=null){
                    libraryPreviewJob?.cancel()
                    libraryPreviewJob=fileActionScope.launch {
                        try{
                            val valid=validCollectionRefs(ids).filter{it.item.mediaType==MediaType.AUDIO}
                            check(valid.isNotEmpty() && (requestedId == null || valid.any { it.key == requestedId }))
                            libraryModel.audioPreview.playSelection(valid,false,requestedId)
                        }catch(e:CancellationException){throw e}catch(_:Exception){AppNotice.post(context,"Нет доступных треков")}
                    }
                }
                CollectionsScreen(collections,collectionRefs,organization,collectionInitial,collectionAudioOnly,collectionAddIds,collectionLink,
                    collectionUsable,{navController.popBackStack()},{ id, ids ->
                        fileActionScope.launch {
                            val valid = validCollectionRefs(ids)
                            if (valid.any { it.key == id }) {
                                navController.navigate("viewer/${android.net.Uri.encode(id)}")
                                navController.currentBackStackEntry?.savedStateHandle?.apply {
                                    set("grid_viewer_token", ViewerQueueCache.store(valid.map { it.key }))
                                    set("gallery_presentation", true)
                                    set("private_viewer", false)
                                    set("collection_viewer", true)
                                    set("gallery_expected_uri", valid.first { it.key == id }.item.savedUri)
                                }
                            } else AppNotice.post(context,"Файл недоступен")
                        }
                    },
                    onPlay={ids->playCollection(ids)},onLink=::openOrganizationLink,onPassport=::openPassport,
                    onAddComplete={collectionAddIds=emptyList();collectionLink=null},usable=collectionUsable,initialCreate=collectionCreate,
                    blockedIdentities=collectionBlocked, history=collectionHistory,
                    identityIndex=collectionCatalog?.identityIndex, externalHistory=missingExternalRefs,
                    scopedDetails=true, detailCatalogId=collectionDetailId, detailCatalog=scopedCatalog,
                    onSelectedCollection={ if(collectionDetailId != it) collectionLegacyRequest=CollectionCatalogRequest.NONE; collectionDetailId=it },catalogByKey=fullProjection.value.byKey,
                    cachedCovers=if(fullCurrent && fullProjection.ready) emptyMap() else cachedCollectionCovers, overviewAccessReady=collectionReadAccess!=null && collectionsLoaded,
                    detailChecking=!collectionsLoaded || collectionReadAccess==null ||
                        (if(transferDetailRead) !materializedDetail.usable else scopeGuard!=null && !scopedProjection.ready),
                    coverAccess=collectionCoverAccess,
                    onCatalogRequest={collectionPickerRequest=it},
                    onRetryPicker={refreshPhoneLibraries(force=true)},
                    onRetryDetail={
                        if(scopeMembers.any { it.id in scopedCatalog?.pending.orEmpty() && CollectionAddressResolver.phoneAddresses(it).isEmpty() &&
                                MediaOrganizationPolicy.decode(it.identityJson).none { key -> key.startsWith("app:") } })
                            collectionLegacyRequest=if(collectionAudioOnly) CollectionCatalogRequest.AUDIO else CollectionCatalogRequest.ALL
                        collectionRefreshEpoch++
                    },
                    validateSelection={ selected ->
                        val valid=validCollectionRefs(selected.map { it.key })
                        check(valid.size==selected.size && selected.all { ref -> valid.any { it.key==ref.key && it.item.savedUri==ref.item.savedUri } }) { "Часть файлов недоступна" }
                        valid
                    },
                    canRecover={ row -> collectionHistory[row.id] == row && com.domenota.medialoader.data.download.MissingFileRecoveryPolicy.eligible(row) && fileStatusUris[row.id]==row.savedUri && fileStatuses[row.id]==SavedFileStatus.MISSING && listOfNotNull(row.sourcePageUrl,row.sourceUrl).any { candidate ->
                        InstagramLinkParser.parse(candidate)!=null || YouTubeLinkParser.parse(candidate)!=null || VkLinkParser.parse(candidate)!=null || RutubeLinkParser.parse(candidate)!=null
                    } },
                    onRecover={ row ->
                        fileActionScope.launch {
                            val current=mediaRepository.downloadById(row.id)
                            if(current!=null && com.domenota.medialoader.data.download.MissingFileRecoveryPolicy.eligible(current) && current.savedUri==row.savedUri &&
                                withContext(Dispatchers.IO){availabilityCache.get(current.savedUri,fresh=true)==SavedFileStatus.MISSING} &&
                                libraryModel.privateKeys.value?.let { keys -> row.id !in keys && current.savedUri?.let { uri ->
                                    com.domenota.medialoader.data.playback.PublicMediaIdentity.uriKeys(uri).none(keys::contains) } != false }==true &&
                                !com.domenota.medialoader.ui.screens.music.MusicTrackVisibility.hidden(current, musicVisibilityPrefs.getStringSet("hidden_tracks",emptySet()).orEmpty())) reanalyzeMissingFile(row.id)
                            else AppNotice.post(context,"Состояние файла изменилось")
                        }
                    },
                    onPlayTrack={id,ids->playCollection(ids,id)},currentPlayingId=currentLibraryAudioId,playing=libraryAudioState.playing,
                    onNoticeFooterHeight={collectionNoticeFooterPx=it},
                    miniPlayer={
                        if(visibilityReady && (libraryAudioState.currentId==null || currentLibraryAudioId!=null)) AudioMiniPlayer(onToggle=::toggleLibraryPlayback,interactiveSeek=true,onOpen={
                            val decision=com.domenota.medialoader.data.playback.CurrentPlayerNavigation.resolve(
                                controllerReady=libraryAudioState.connected,
                                catalogReady=libraryIndex.ready&&indexedLibraryProjection.ready&&libraryAliasProjection.ready,
                                privateReady=libraryPrivateKeys!=null&&visibilityReady,currentItem=libraryAudioState.currentItem,
                                rows=publicLibraryRows,blockedIdentities=libraryPrivateKeys.orEmpty()+organizationHidden+visibilityBlocked)
                            if(decision is com.domenota.medialoader.data.playback.CurrentPlayerDestination.Open){
                                navController.navigate("viewer/${android.net.Uri.encode(decision.row.id)}"){launchSingleTop=true}
                                navController.currentBackStackEntry?.savedStateHandle?.apply{
                                    set("private_viewer",false);set("gallery_presentation",false);set("current_audio_session_only",true)
                                }
                            }else AppNotice.post(context,"Текущий плеер недоступен")
                        })
                    })
            },
            LibraryRouteContent("media-review"){
                var playlistTarget by remember{mutableStateOf<String?>(null)}
                ReviewScreen(reviewEntries,organizationRefs,catalogFolders,
                    libraryCatalogAssignments.filter{it.isFavorite}.mapTo(mutableSetOf()){it.downloadId},
                    organization,organizationReady && organizationProjection.ready,{navController.popBackStack()},openDownloadWithQueue,
                    onEdit={row->navController.navigate(when(row.mediaType){MediaType.PHOTO->"photo-editor/";MediaType.VIDEO->"video-editor/";MediaType.AUDIO->"audio-editor/"}+android.net.Uri.encode(row.id))},
                    onTrash=deleteLibraryFile,onAddCollection=::addToCollection,onPassport=::openPassport,
                    onFavorite=setLibraryFavorite,onFolder=setLibraryFolder,onPlaylist={playlistTarget=it},usable=organizationUsable)
                playlistTarget?.let{id->AlertDialog(onDismissRequest={playlistTarget=null},title={Text("В плейлист")},
                    text={Column(Modifier.heightIn(max=320.dp).verticalScroll(rememberScrollState())){
                        playlists.forEach{p->TextButton({playlistTarget=null;fileActionScope.launch{
                            try{libraryModel.playlistRepository.addTracksResolved(p.id,listOf(id),libraryAliases)}
                            catch(e:CancellationException){throw e}catch(_:Exception){AppNotice.post(context,"Не удалось добавить трек")}
                        }}){Text(p.name)}}
                    }},confirmButton={TextButton({playlistTarget=null}){Text("Закрыть")}})}
            },
            LibraryRouteContent("media-passport/{mediaId}"){entry->
                val ref=entry.arguments?.getString("mediaId")?.let{if(organizationReady)libraryRefsByAlias[it] else null}
                val ops by remember(ref?.appDownloadId){organization.operations(ref?.appDownloadId.orEmpty())}.collectAsStateWithLifecycle(initialValue=emptyList())
                val names=ref?.let{r->
                    val ids=r.aliases+r.key
                    val folders=libraryCatalogAssignments.filter{it.downloadId in ids}.mapNotNull{a->catalogFolders.firstOrNull{it.id==a.folderId}?.name}
                    (folders+listOfNotNull(r.deviceAlbumName)).distinct()
                }.orEmpty()
                val playlistNames=ref?.let{r->playlists.filter{p->p.trackIds.any{it==r.key||it in r.aliases}}.map{it.name}}.orEmpty()
                MediaPassportScreen(ref?.takeIf{candidate->organizationRefs.any{it.key==candidate.key}},organizationRefs,collections,ops,names,playlistNames,
                    {navController.popBackStack()},openDownloadWithQueue,::openOrganizationLink,{openCollections(it)})
            },
            LibraryRouteContent("batch-photo-editor") {
                val coordinator=remember(context){com.domenota.medialoader.data.editor.EditorExportCoordinator.get(context)}
                val batch by coordinator.batch.collectAsStateWithLifecycle()
                val rows=batchPhotoIds.mapNotNull{libraryRefsByAlias[it]?.item}.distinctBy{it.savedUri}
                    .filter{it.mediaType==MediaType.PHOTO&&!it.isPrivate&&!it.hidden&&it.savedUri!=null}
                com.domenota.medialoader.ui.screens.BatchPhotoEditorScreen(rows,batch,editorExport.exporting,
                    ready=libraryIndex.ready && rows.size==batchPhotoIds.size,
                    onBack={navController.popBackStack()},
                    onStart=coordinator::startBatchPhotos,onCancel={coordinator.cancel()})
            },
            LibraryRouteContent("visual-duplicates") {
                com.domenota.medialoader.ui.screens.gallery.VisualDuplicatesScreen(
                    visualScanCatalog.value, visualScanReady, visualPhonePermission,
                    currentGeneration={
                        !libraryModel.galleryScanInProgress.value && libraryModel.index.value===libraryIndex &&
                            libraryModel.gallery.value.revision==phoneGallery.revision &&
                            libraryModel.galleryAccessEpoch.value==galleryAccessEpoch &&
                            libraryModel.collectionHistory.value===historySnapshot
                    },
                    onBack={navController.popBackStack()},onAppTrash=viewModel::trashVerifiedAlbumMedia,
                    onRefresh={libraryModel.refresh(AppMode.GALLERY, force = true)},
                    reportReady=visualReportAccess!=null && visualScanCatalog.usable)
            },
            LibraryRouteContent("music-duplicates") {
                if (!libraryIndex.ready || libraryPrivateKeys == null || (librarySource == LibrarySource.PHONE && !phoneMusic.loaded)) {
                    Column(Modifier.fillMaxSize().padding(16.dp)) {
                        TextButton({ navController.popBackStack() }) { Text("Назад") }
                        Text("Загрузка музыкальной библиотеки…")
                    }
                } else com.domenota.medialoader.ui.screens.music.MusicDuplicatesDialog(
                    libraryAudioItems, libraryCatalogAssignments.filter { it.folderId != null || it.isFavorite }.mapTo(mutableSetOf()) { it.downloadId } +
                        playlists.flatMap { it.trackIds } + publicLibraryRefs.filter { ref -> libraryModel.equalizerProfiles.value?.tracks.orEmpty().any { profile -> profile.keys.any { it in ref.aliases || it == "id:${ref.key}" } } }.map { it.key },
                    currentLibraryAudioId, libraryAudioState.playing,
                    onDismiss = { navController.popBackStack() }, onTrash = trashDuplicates)
            },
            LibraryRouteContent("equalizer") {
                val profiles by libraryModel.equalizerProfiles.collectAsStateWithLifecycle()
                val currentTrack by com.domenota.medialoader.data.playback.EqualizerPlaybackTrack.track.collectAsStateWithLifecycle()
                val sampleRate by com.domenota.medialoader.data.playback.EqualizerPlaybackFormat.sampleRate.collectAsStateWithLifecycle()
                val error by libraryModel.equalizerError.collectAsStateWithLifecycle()
                var editGlobal by rememberSaveable { mutableStateOf(false) }
                val track = currentTrack
                val target = if (editGlobal) null else track
                Column(Modifier.fillMaxSize()) {
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(17.dp)) }
                    profiles?.let { state ->
                        com.domenota.medialoader.ui.screens.music.EqualizerScreen(
                            state.effective(target), sampleRate,
                            { settings -> libraryModel.setEqualizer(target, settings) },
                            { navController.popBackStack() },
                            trackTitle = track?.title,
                            editGlobal = target == null,
                            onEditGlobal = { editGlobal = it },
                            hasTrackProfile = state.profile(target) != null,
                            onUseGlobal = { target?.let { libraryModel.setEqualizer(it, null) } },
                        )
                    } ?: Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
            },
            LibraryRouteContent(PLAYLIST_ADD_ROUTE) { entry ->
                val playlistId = entry.arguments?.getString("playlistId")
                val playlist = playlists.firstOrNull { it.id == playlistId }
                if (playlist == null) {
                    if (!playlistsReady) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    else LaunchedEffect(playlistId) { navController.popBackStack() }
                } else PlaylistTracksScreen(
                    playlist = playlist, source = musicLibrarySource,
                    onSource = { source ->
                        musicLibrarySource = source; libraryPreferences.setSource(AppMode.MUSIC, source)
                        if (source == LibrarySource.PHONE && !hasPhoneLibraryAccess(context, AppMode.MUSIC)) requestLibraryAccess(AppMode.MUSIC)
                    }, appTracks = appLibraryAudioItems,
                    allTracks = libraryAudioItems, aliases = libraryAliases,
                    onBack = { navController.popBackStack() }, onPreview = previewLibraryAudio,
                    onAdd = { libraryModel.playlistRepository.addTracksResolved(playlist.id, it, libraryAliases) },
                    validateSelection = { ids -> withContext(Dispatchers.IO) {
                        ids.filter { id -> libraryRefsByAlias[id]?.item?.savedUri?.let {
                            availabilityCache.get(it, fresh = true) == SavedFileStatus.PRESENT
                        } == true }
                    } },
                    miniPlayer = { if(visibilityReady && (libraryAudioState.currentId==null || currentLibraryAudioId!=null)) AudioMiniPlayer(onToggle=::toggleLibraryPlayback,onOpen = openDownload, onOpenQueue = openDownloadWithQueue) },
                    loading = musicLibrarySource == LibrarySource.PHONE && phoneMusic.loading,
                    message = phoneMusic.message, onAccess = { requestLibraryAccess(AppMode.MUSIC) },
                    onSettings = { navController.navigate(AppDestination.Settings.route) },
                    currentPlayingId = currentLibraryAudioId, playing = libraryAudioState.playing,
                )
            },
            LibraryRouteContent(BROWSER_ROUTE) { entry ->
                BrowserSource.fromId(entry.arguments?.getString("source"))?.let { source ->
                    SourceBrowserScreen(
                        source = source,
                        onBack = { navController.popBackStack() },
                        pickerVisible = showPicker,
                        onRutubeSessionCaptured = { viewModel.onRutubeLoginFinished(true) },
                        onDownload = { link ->
                            pendingUrl = link
                            lastHandledLink = link
                            viewModel.analyze(link)
                            showPicker = true
                        },
                    )
                }
            },
            LibraryRouteContent(AppDestination.Downloads.route) {
                DownloadsScreen(
                    items = downloadItems,
                    hiddenFolderIds = visibilityFolders,
                    pendingVisibilityFolderId = null,
                    nomediaFolderStatuses = nomediaFolderStatuses,
                    onCheckFolderAccess = { folder ->
                        val restored = nomediaRegistry.reconcile(folder)
                        val ids=catalogAssignments.filter { it.folderId==folder }
                            .mapTo(mutableSetOf()) { it.downloadId }
                        val rows=downloads.filter { it.id in ids && !it.isPrivate && !it.hidden }
                        var available=0
                        for (row in rows) if(nomediaRegistry.checkOldMediaLink(row.savedUri)) available++
                        val markerResult=if(restored.markerState==NomediaMarkerState.FOREIGN_MARKER)
                            "Чужой .nomedia сохранён" else "Проверка маркера выполнена"
                        AppNotice.post(context,
                            "Доступ по прежнему URI: $available из ${rows.size}. " +
                            "Неподтверждённые файлы не перепривязаны. $markerResult")
                    },
                    onFolderVisibility = toggleFolderVisibility.takeIf { android.os.Build.VERSION.SDK_INT >= 21 },
                    onBrowseSafFiles = { folder -> nomediaRegistry.listHiddenDocuments(folder) },
                    onVerifySafFile = { folder,document -> nomediaRegistry.verifySafDocumentRead(folder,document) },
                    onOpenSafFile = { folder,document -> nomediaRegistry.openSafMedia(folder,document) },
                    onOpenFromFolder = { id,queue,folder -> openingAlbumKey = folder?.let { "folder:$it" }; openDownloadWithQueue(id,queue) },
                    onShareFromFolder = { id,folder -> shareScopedFile(id,folder) },
                    onAddCollection=::addToCollection,onPassport=::openPassport,
                    onOpenPrivate = { viewModel.privateAccess.lock(); pendingPrivateMoves = emptyList(); returnAfterPrivateSetup = false; privateNavigation = true; navController.navigate(PRIVATE_ROUTE) },
                    onMoveToPrivate = { id -> requestPrivateMove(listOf(id)) },
                    onMoveGroupToPrivate = ::requestPrivateMove,
                    onSetFavorite = viewModel::setCatalogFavorite,
                    onDeleteGroupFile = ::hideVisibilityGroupFile,
                    scrollToTopRequest = downloadsScrollToTopRequest,
                    catalogFolders = catalogFolders,
                    catalogAssignments = catalogAssignments,
                    galleryOnlyFolderIds = galleryOnlyFolderIds,
                    onCreateFolder = viewModel::createCatalogFolder,
                    onRenameFolder = viewModel::renameCatalogFolder,
                    onDeleteFolder = deleteVisibilityFolder,
                    onSetFolder = setLibraryFolder,
                    onToggleFavorite = viewModel::toggleCatalogFavorite,
                    onOpenDownload = openDownload,
                    onOpenGrid = openDownloadWithQueue,
                    onCancelDownload = viewModel::cancelDownload,
                    onShareDownload = shareDownload,
                    onHideDownload = deleteLibraryFile,
                    onRetryDownload = viewModel::retryDownload,
                    onReanalyzeMissing = reanalyzeMissingFile,
                    onRenameDownload = renameVisibilityFile,
                    recognitionEnabled = musicRecognitionMode != MusicRecognitionMode.OFF,
                    onRecognizeDownload = recognizeVisibilityFile,
                    bulkState = bulk,
                    bulkDownloads = publicDownloads,
                    onBulkDetails = { navController.navigate(BULK_ROUTE) },
                    onBulkStopAnalysis = viewModel::stopBulkAnalysis,
                    onBulkPause = viewModel::pauseBulk,
                    onBulkResume = viewModel::resumeBulk,
                )
            },
            LibraryRouteContent(PRIVATE_ROUTE) {
                PrivateScreen(viewModel.privateAccess, privateDownloads,
                    onBack = { pendingPrivateMoves = emptyList(); returnAfterPrivateSetup = false; viewModel.privateAccess.lock(); navController.popBackStack() },
                    onOpen = openDownload,
                    onOpenGrid = openDownloadWithQueue,
                    onRestore = viewModel::restorePrivate,
                    onFinishMove = viewModel::finishPrivateMove,
                    onDelete = viewModel::deletePrivate,
                    onRename = renameVisibilityFile,
                    externalBusy = privateMoveBusy || (returnAfterPrivateSetup && privateState.mode != com.domenota.medialoader.data.privatevault.PrivateAccessMode.UNCONFIGURED),
                    catalogFolders = catalogFolders,
                    catalogAssignments = catalogAssignments,
                    onSetFolder = viewModel::setCatalogFolder,
                    onSetFavorite = viewModel::setCatalogFavorite,
                    onCreateFolder = viewModel::createCatalogFolder)
            },
            LibraryRouteContent(AppDestination.Settings.route) {
                SettingsScreen(
                    musicSource = musicSource,
                    onMusicSourceChange = ::changeMusicSource,
                    downloadFolder = downloadFolder,
                    versionName = BuildConfig.VERSION_NAME,
                    versionCode = BuildConfig.VERSION_CODE,
                    activeDownloadCount = downloads.count {
                        it.state == DownloadState.QUEUED || it.state == DownloadState.RUNNING
                    },
                    instagramSignedIn = signedIn,
                    instagramSessionRevision = instagramSessionRevision,
                    onInstagramSignIn = startLogin,
                    onInstagramSignOut = viewModel::logout,
                    onRefreshInstagramSession = viewModel::refreshInstagramSession,
                    onManualSessionId = viewModel::importSessionId,
                    youtubeSignedIn = youtubeSignedIn,
                    onYouTubeSignIn = startYouTubeLogin,
                    onYouTubeSignOut = viewModel::logoutYouTube,
                    vkSignedIn = vkSignedIn,
                    onVkSignIn = startVkLogin,
                    onVkSignOut = viewModel::logoutVk,
                    rutubeSignedIn = rutubeSignedIn,
                    onRutubeSignIn = startRutubeLogin,
                    onRutubeSignOut = viewModel::logoutRutube,
                    hiddenCount = hiddenCount,
                    hiddenItems = remember(hiddenItems) { hiddenItems.map { entry ->
                        entry.toUi().copy(savedUri = viewModel.trashedUri(entry.id))
                    } },
                    onEditorPickerChange = { kind ->
                        val mode = when (kind) {
                            com.domenota.medialoader.ui.model.MediaKind.AUDIO -> AppMode.MUSIC
                            null -> null
                            else -> AppMode.GALLERY
                        }
                        editorLibraryMode = mode
                        if (mode != null) {
                            if (!hasPhoneLibraryAccess(context,mode)) requestLibraryAccess(mode)
                            else libraryModel.refresh(mode)
                        }
                    },
                    editorLoading = when (editorLibraryMode) {
                        AppMode.MUSIC -> phoneMusic.loading
                        AppMode.GALLERY -> phoneGallery.loading
                        else -> false
                    },
                    editorMessage = when (editorLibraryMode) {
                        AppMode.MUSIC -> phoneMusic.message
                        AppMode.GALLERY -> phoneGallery.message
                        else -> null
                    },
                    editorItems = com.domenota.medialoader.ui.components.rememberEditorItems(accessibleDownloads, libraryAliases),
                    editorAlbumsById = remember(
                        accessibleDownloads, libraryRefsByAlias, libraryCatalogAssignments, catalogFolders, phoneRows
                    ) {
                        val folderNames = catalogFolders.associate { it.id to it.name }
                        val deviceFolders = phoneRows.mapNotNull { row -> row.savedUri?.let { it to row.groupId } }.toMap()
                        val assigned = libraryCatalogAssignments.associateBy { it.downloadId }
                        accessibleDownloads.associate { file ->
                            val ref = libraryRefsByAlias[file.id]
                            val assignment = assigned[file.id] ?: ref?.let { assigned[it.key] }
                            val names = listOfNotNull(
                                assignment?.folderId?.let(folderNames::get),
                                (ref?.deviceAlbumName ?: deviceFolders[file.savedUri])?.takeIf { it.isNotBlank() },
                            ).distinct()
                            file.id to names
                        }
                    },

                    onEditFile = { id ->
                        when (accessibleDownloads.firstOrNull { it.id == id }?.mediaType) {
                            MediaType.PHOTO -> navController.navigate("photo-editor/$id")
                            MediaType.VIDEO -> navController.navigate("video-editor/$id")
                            MediaType.AUDIO -> navController.navigate("audio-editor/$id")
                            null -> Unit
                        }
                    },
                    onOpenEqualizer = { navController.navigate("equalizer") },
                    onChangeFolder = viewModel::changeFolder,
                    onSelectFolder = { folderLauncher.launch(null) },
                    storageMigrationRunning = storageMigration.running,
                    storageMigrationMessage = storageMigration.message,
                    onMigrateLegacyMedia = viewModel::migrateLegacyDownloads,
                    galleryDateAudit = galleryDateAudit,
                    galleryDateAuditBusy = galleryDateAuditBusy,
                    onGalleryDateAudit = viewModel::inspectGalleryDateColumns,
                    migrationInventoryStatus = migrationInventoryStatus,
                    migrationInventoryBusy = migrationInventoryBusy,
                    migrationInventoryJson = migrationInventoryJson,
                    onRunMigrationInventory = viewModel::inspectMigrationReadOnly,
                    republishTrialBusy = republishTrialBusy,
                    republishTrialStatus = republishTrialStatus,
                    exifTrialStatus = exifTrialStatus,
                    exifTrialBusy = exifTrialBusy,
                    onRunExifTrial = viewModel::runExifChronologyTrial,
                    onRemoveExifTrial = viewModel::removeExifChronologyTrial,
                    videoTrialStatus = videoTrialStatus,
                    videoTrialBusy = videoTrialBusy,
                    onRunVideoTrial = viewModel::runVideoChronologyTrial,
                    onRemoveVideoTrial = viewModel::removeVideoChronologyTrial,
                    sameUriBusy = sameUriBusy,
                    sameUriJpegStatus = sameUriJpegStatus,
                    sameUriVideoStatus = sameUriVideoStatus,
                    onRunSameUriJpeg = viewModel::runSameUriJpegTrial,
                    onRunSameUriMp4 = viewModel::runSameUriMp4Trial,
                    onRepairSameUriJpegMtime = viewModel::repairSameUriJpegMtime,
                    onRepairSameUriMp4Mtime = viewModel::repairSameUriMp4Mtime,
                    onRestoreSameUriJpeg = viewModel::restoreSameUriJpegTrial,
                    onRestoreSameUriMp4 = viewModel::restoreSameUriMp4Trial,
                    onRemoveSameUriJpeg = viewModel::removeSameUriJpegTrial,
                    onRemoveSameUriMp4 = viewModel::removeSameUriMp4Trial,
                    onRunGalleryRepublishTrial = viewModel::runGalleryRepublishTrial,
                    onRemoveGalleryRepublishTestCopies =
                        viewModel::removeGalleryRepublishTrialCopies,
                    dateRecoveryStatus = dateRecoveryStatus,
                    dateRecoveryBusy = dateRecoveryBusy,
                    dateRecoveryReady = dateRecoveryReady,
                    onDateRecoveryPreview = viewModel::previewMediaDates,
                    onDateRecoveryTrial = viewModel::tryRestoreOneMediaDate,
                    dateRecoveryTrialDone = dateRecoveryTrialDone,
                    onRestoreRemainingDates = viewModel::restoreRemainingMediaDates,
                    onUndoMediaDateChanges = viewModel::undoMediaDateChanges,
                    onRestore = viewModel::restoreHidden,
                    onEmptyTrash = viewModel::clearHidden,
                    onOpenTrash = { navController.navigate("trash") },
                    onRequestLibraryAccess = ::requestLibraryAccess,
                    onClearHistory = viewModel::clearHistory,
                    ytDlpUpdate = ytDlpUpdate,
                    ytDlpUpdating = ytDlpUpdating,
                    onUpdateYtDlp = viewModel::updateYtDlp,
                    hasYouTubeCookies = youtubeCookies,
                    onImportYouTubeCookies = viewModel::importYouTubeCookies,
                    onClearYouTubeCookies = viewModel::clearYouTubeCookies,
                    musicRecognitionMode = musicRecognitionMode,
                    renameRecognizedTracks = renameRecognizedTracks,
                    onMusicRecognitionModeChange = viewModel::setMusicRecognitionMode,
                    onRenameRecognizedTracksChange = viewModel::setRenameRecognizedTracks,
                    closePickerAfterDownload = closePickerAfterDownload,
                    onClosePickerAfterDownloadChange = { enabled ->
                        closePickerAfterDownload = enabled
                        uiPrefs.edit().putBoolean("close_picker_after_download", enabled).apply()
                    },
                    internalViewerEnabled = internalViewerEnabled,
                    onInternalViewerEnabledChange = { enabled ->
                        internalViewerEnabled = enabled
                        uiPrefs.edit().putBoolean("internal_viewer_enabled", enabled).apply()
                    },
                    onBulkDownload = { navController.navigate(BULK_ROUTE) },
                    onVkCommunityImages = { navController.navigate("vk-community") },
                    onOpenDiagnostics = { navController.navigate("diagnostics") },
                )
            },
            LibraryRouteContent("diagnostics") {
                DiagnosticsScreen(
                    onBack = { navController.popBackStack() },
                    versionName = BuildConfig.VERSION_NAME,
                    versionCode = BuildConfig.VERSION_CODE,
                )
            },
            LibraryRouteContent("vk-community") {
                VkCommunityScreen(onBack = { navController.popBackStack() })
            },
            LibraryRouteContent("trash") {
                val trashRows = hiddenItems.map { entry ->
                    entry.toUi().copy(savedUri = viewModel.trashedUri(entry.id))
                }
                PhoneTrashController(
                    appItems = trashRows,
                    onBack = { navController.popBackStack() },
                    onOpenApp = { id ->
                        if (viewModel.trashedUri(id) == null) {
                            AppNotice.post(context, "Файл в корзине недоступен")
                        } else navController.navigate("trash-viewer/$id")
                    },
                    onRestoreApp = viewModel::restoreHidden,
                    onDeleteApp = viewModel::deleteHidden,
                )
            },
            LibraryRouteContent("trash-viewer/{downloadId}") { entry ->
                val id = entry.arguments?.getString("downloadId")
                val original = hiddenItems.firstOrNull { it.id == id }
                val privateUri = id?.let(viewModel::trashedUri)
                if (original == null || privateUri == null) {
                    LaunchedEffect(id) {
                        AppNotice.post(context, "Файл в корзине недоступен")
                        navController.popBackStack()
                    }
                } else {
                    val viewing = original.copy(savedUri = privateUri)
                    val photoRows = hiddenItems.filter {
                        it.mediaType == MediaType.PHOTO && it.state == DownloadState.COMPLETED
                    }.mapNotNull { row ->
                        viewModel.trashedUri(row.id)?.let { row.copy(savedUri = it) }
                    }
                    val audioRows = hiddenItems.filter {
                        it.mediaType == MediaType.AUDIO && it.state == DownloadState.COMPLETED
                    }.mapNotNull { row ->
                        viewModel.trashedUri(row.id)?.let { row.copy(savedUri = it) }
                    }
                    MediaViewerScreen(
                        item = viewing,
                        onBack = { navController.popBackStack() },
                        onOpenExternal = {},
                        onShare = {},
                        audioQueue = audioRows,
                        photoQueue = photoRows,
                        mediaQueue = hiddenItems.filter { it.mediaType != MediaType.AUDIO }.mapNotNull { row ->
                            viewModel.trashedUri(row.id)?.let { row.copy(savedUri = it) }
                        },
                        onEdit = null,
                        trashReadOnly = true,
                        onRestoreFromTrash = { row ->
                            viewModel.restoreHidden(setOf(row.id))
                            navController.popBackStack()
                        },
                        onDeleteFromTrash = { row ->
                            viewModel.deleteHidden(setOf(row.id))
                            navController.popBackStack()
                        },
                    )
                }
            },
            LibraryRouteContent(MEDIA_VIEWER_ROUTE) { entry ->
                val viewerState = rememberSaveableStateHolder()
                val downloadId = entry.arguments?.getString("downloadId")
                val galleryPresentation = entry.savedStateHandle.get<Boolean>("gallery_presentation") == true
                val privateViewer = entry.savedStateHandle.get<Boolean>("private_viewer") == true
                val snapshot = ViewerQueueCache.resolve(
                    entry.savedStateHandle.get<String>("grid_viewer_token"))
                val visualSelection by entry.savedStateHandle.getStateFlow<ArrayList<String>?>("gallery_selection", null).collectAsStateWithLifecycle()
                // Presentation controls system bars, not file identity. A standalone
                // visual open still has an exact route selection without changing chrome.
                val visualRoute = galleryPresentation || visualSelection != null
                val selectedGalleryId by entry.savedStateHandle.getStateFlow<String?>("gallery_current_id", null).collectAsStateWithLifecycle()
                val currentGalleryId = visualSelection?.getOrNull(0) ?: selectedGalleryId ?: downloadId
                val expectedGalleryUri = visualSelection?.getOrNull(1) ?: entry.savedStateHandle.get<String>("gallery_expected_uri")
                val selectedAppVisual = visualSelection?.getOrNull(2) == "app"
                val collectionViewer = entry.savedStateHandle.get<Boolean>("collection_viewer") == true
                val visibilityScope = entry.savedStateHandle.get<String>("folder_visibility_scope")
                val scopedFolderRefs = remember(visibilityScope,folderRefs,visibilityGuard) {
                    if (visibilityScope == null) emptyList() else folderRefs.filter {
                        visibilityGuard?.explicitFolder(it) == visibilityScope && canOpenFolderMedia(it.item,visibilityScope)
                    }
                }
                val viewerPublicRefs = if (visibilityScope != null) scopedFolderRefs else if (collectionViewer) scopedCatalog?.refs.orEmpty() else publicLibraryRefs
                val indexedCandidates = remember(visualRoute, viewerPublicRefs, libraryPrivateKeys,
                    snapshot, downloadId, currentGalleryId, fileStatuses, fileStatusUris, collectionViewer, visibilityScope) {
                    if (!visualRoute) emptyList() else {
                        val requested = snapshot.toSet() + listOfNotNull(downloadId, currentGalleryId)
                        // publicLibraryRefs is *already* UnifiedMediaLibrary.merge output.
                        // A second merge on the playback UI thread caused long stalls
                        // on the real Xiaomi 11k-item catalog. Scoped/collection
                        // refs are not guaranteed grouped and retain the strict path.
                        val candidates = if (visibilityScope == null && !collectionViewer)
                            GalleryViewerState.publicCandidatesFromUnified(viewerPublicRefs, libraryPrivateKeys, requested)
                        else GalleryViewerState.publicCandidates(viewerPublicRefs, libraryPrivateKeys, requested)
                        candidates.filter { row ->
                            !row.hidden && !row.isPrivate && row.savedUri != null &&
                                row.state == DownloadState.COMPLETED &&
                                (fileStatusUris[row.id] != row.savedUri ||
                                    (fileStatuses[row.id] != SavedFileStatus.MISSING &&
                                        fileStatuses[row.id] != SavedFileStatus.INACCESSIBLE))
                        }
                    }
                }
                // Resolve the exact fresh history row without waiting for a whole-phone index.
                // A scoped collection must never be expanded into raw public history.
                val selectedHistoryRow = if (visualRoute && !privateViewer && !collectionViewer)
                    downloads.firstOrNull { it.id == currentGalleryId && canOpenFolderMedia(it,visibilityScope) } else null
                val addressGuard = libraryPrivateKeys.takeIf { !recoveryRunning && !privateViewer && visibilityReady }
                var addressed by remember(selectedHistoryRow, expectedGalleryUri, addressGuard, galleryAccessEpoch,visibilityGuard,visibilityScope) { mutableStateOf<LibraryMediaRef?>(null) }
                var addressFinished by remember(selectedHistoryRow, expectedGalleryUri, addressGuard, galleryAccessEpoch,visibilityGuard,visibilityScope) { mutableStateOf(false) }
                LaunchedEffect(selectedHistoryRow, expectedGalleryUri, addressGuard, galleryAccessEpoch,visibilityGuard,visibilityScope) {
                    val row = selectedHistoryRow
                    val blocked = addressGuard
                    if (row != null && blocked != null && !row.hidden && !row.isPrivate && (expectedGalleryUri == null || row.savedUri == expectedGalleryUri)) {
                        try {
                            val resolved = withTimeoutOrNull(5000) { libraryModel.addressCollectionApp(row,visibilityScope) { uri -> availabilityCache.get(uri, fresh = true) } }
                            if (libraryModel.privateKeys.value == blocked && libraryModel.galleryAccessEpoch.value == galleryAccessEpoch && canOpenFolderMedia(row,visibilityScope) &&
                                !com.domenota.medialoader.data.storage.HistoricalRecoverySignals.isRunning.value)
                                addressed = resolved?.ref
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { AppLog.w("Viewer", "Selected visual address check failed") }
                    }
                    addressFinished = true
                }
                val seed = GalleryViewerState.addressedPublic(selectedHistoryRow, addressed, addressGuard)
                val galleryCandidates = remember(seed, indexedCandidates, selectedHistoryRow, selectedAppVisual, currentGalleryId, addressGuard) {
                    // A current app row is authoritative. A removed/moved app row
                    // cannot reappear from a stale index or a coincident phone alias.
                    if (addressGuard == null) emptyList()
                    else (listOfNotNull(seed) + indexedCandidates.filterNot {
                        it.id == currentGalleryId && (
                            (selectedAppVisual && selectedHistoryRow == null) ||
                            (selectedHistoryRow != null && (selectedHistoryRow.hidden || selectedHistoryRow.isPrivate ||
                                selectedHistoryRow.id in addressGuard || selectedHistoryRow.state != DownloadState.COMPLETED ||
                                it.savedUri != selectedHistoryRow.savedUri)))
                    }).distinctBy { it.id }
                }
                val privateCandidates = remember(privateViewer, privateState.unlocked,
                    privateDownloads, snapshot, downloadId, currentGalleryId, fileStatuses) {
                    if (!privateViewer) emptyList()
                    else com.domenota.medialoader.data.playback.PrivateViewerQueue.resolve(
                        snapshot, currentGalleryId, privateState.unlocked, privateDownloads,
                        fileStatuses)
                }
                val audioCurrentId = entry.savedStateHandle.get<String>("audio_current_id")
                val item = if (privateViewer) GalleryViewerState.exactItem(currentGalleryId, null, privateCandidates)
                    else if (visualRoute) GalleryViewerState.exactItem(currentGalleryId, expectedGalleryUri, galleryCandidates)?.takeIf { canOpenFolderMedia(it,visibilityScope) }
                    else if (visibilityScope != null) scopedFolderRefs.firstOrNull { it.key == (audioCurrentId ?: downloadId) }?.item
                    else accessibleDownloads.firstOrNull { it.id == (audioCurrentId ?: downloadId) }
                        ?: libraryRefsByAlias[audioCurrentId ?: downloadId]?.item
                        ?: accessibleDownloads.firstOrNull { it.id == downloadId }
                if (item == null || item.savedUri == null) {
                    if (!privateViewer &&
                        ((!collectionViewer && awaitingPhone(downloadId)) || (visualRoute &&
                            ((selectedHistoryRow != null && !addressFinished) || libraryPrivateKeys == null || (collectionViewer && scopedCatalog == null && scopeGuard != null && !scopedProjection.ready))))) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    else LaunchedEffect(currentGalleryId) {
                        if (!privateViewer) AppNotice.post(context, "Выбранный файл недоступен")
                        navController.popBackStack()
                    }
                } else {
                    val scopedRows=remember(scopedFolderRefs){scopedFolderRefs.map{it.item}}
                    val viewerRows = if (privateViewer) privateCandidates
                        else if (item.isPrivate && privateState.unlocked) privateDownloads
                        else if (visualRoute) galleryCandidates else if (visibilityScope != null) scopedRows else publicLibraryRows
                    // Prepare only the relevant viewer queue on a worker. Access,
                    // scope and file-status changes discard retained queues immediately.
                    val viewerGuard = if (item.isPrivate) {
                        if (privateState.unlocked) listOf(true,collectionPrivacyGeneration,fileStatuses) else null
                    } else publicProjectionGuard?.let { listOf(it,visibilityGuard,visibilityScope,collectionViewer,galleryAccessEpoch,musicAccessEpoch,collectionPrivacyGeneration,organizationHidden,fileStatuses) }
                    val queuePrivacy=if(item.isPrivate)emptySet<String>().takeIf{privateState.unlocked} else publicProjectionGuard
                    val queueProjection=com.domenota.medialoader.ui.components.backgroundLibraryProjection(
                        com.domenota.medialoader.ui.navigation.ViewerQueueInventory(),queuePrivacy,
                        snapshot,viewerRows,item.mediaType,item.isPrivate,fileStatuses,
                        if(item.mediaType==MediaType.PHOTO)VkDownloadGroup.key(item.id,item.providerId,item.groupId) else null,
                        accessGuard=viewerGuard,
                    ) {
                        com.domenota.medialoader.core.logging.DetailedPerformance.measure(com.domenota.medialoader.core.logging.PerfWork.VIEWER_QUEUE){
                            com.domenota.medialoader.ui.navigation.ViewerQueueInventory.build(snapshot,viewerRows,item,fileStatuses)
                        }
                    }
                    val audioQueue=queueProjection.value.audio
                    val gridQueue=queueProjection.value.grid
                    val photoQueue=queueProjection.value.photos
                    val visualQueue=remember(gridQueue,photoQueue,item){
                        gridQueue.ifEmpty{if(item.mediaType==MediaType.PHOTO&&photoQueue.isNotEmpty())photoQueue else listOf(item)}
                            .let{rows->if(rows.any{it.id==item.id&&it.savedUri==item.savedUri})rows else listOf(item)+rows.filter{it.id!=item.id}}
                    }
                    val galleryFavoriteIds=remember(libraryCatalogAssignments){libraryCatalogAssignments.filter{it.isFavorite}.mapTo(mutableSetOf()){it.downloadId}}
                    val viewerFavorites=remember(libraryCatalogAssignments,publicLibraryRefs,currentLibraryAudioId,libraryAudioState.currentId){
                        libraryCatalogAssignments.filter{it.isFavorite}.mapTo(mutableSetOf()){it.downloadId}.also{favorites->
                            val stableIds=favorites.toSet()
                            publicLibraryRefs.filter{it.key in stableIds}.forEach{favorites.addAll(it.aliases)}
                            if(currentLibraryAudioId in stableIds)libraryAudioState.currentId?.let(favorites::add)
                        }
                    }
                    if(!queueProjection.usable) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    } else viewerState.SaveableStateProvider("content") {
                    if (item.mediaType != MediaType.AUDIO) GalleryMediaViewer(
                        item = item,
                        media = visualQueue,
                        onBack = { navController.popBackStack() },
                        onShare = { selected ->
                            // Only an explicit user press while the vault is unlocked
                            // may share an otherwise private file.
                            if (!selected.isPrivate || privateState.unlocked) fileActionScope.launch {
                                if (libraryModel.verifyVisibilityOpen(selected,visibilityScope)) shareSavedFile(context,selected)
                                else AppNotice.post(context,"Файл недоступен")
                            }
                        },
                        onEdit = ::openVisibilityEditor,
                        onDelete = { selected ->
                            if (selected.isPrivate) {
                                if (privateState.unlocked) fileActionScope.launch {
                                    viewModel.deletePrivate(selected.id)
                                    navController.popBackStack()
                                }
                            } else deleteLibraryFile(selected.id)
                        },
                        favoriteIds = galleryFavoriteIds,
                        onFavorite = setLibraryFavorite, folders = catalogFolders, onSetFolder = setLibraryFolder,
                        onMoveToPrivate = { if (!it.isPrivate) requestPrivateMove(listOf(it.id)) },
                        onOpenExternal = { selected ->
                            if (!selected.isPrivate || privateState.unlocked) fileActionScope.launch {
                                if (libraryModel.verifyVisibilityOpen(selected,visibilityScope)) openSavedFile(context,selected)
                                else AppNotice.post(context,"Файл недоступен")
                            }
                        },
                        onCurrentItemChanged = { selected ->
                            if (!selected.isPrivate && visualRoute) entry.savedStateHandle["gallery_selection"] = arrayListOf(
                                selected.id, requireNotNull(selected.savedUri),
                                if (!collectionViewer && (downloads.any { it.id == selected.id } ||
                                        (selected.id == currentGalleryId && selectedAppVisual))) "app" else "index")
                            else entry.savedStateHandle["gallery_current_id"] = selected.id
                        },
                        privateMode = item.isPrivate,
                        allowNeighborOnQueueRemoval = !visualRoute && !privateViewer,
                        onPassport=if(item.isPrivate)null else { selected->openPassport(selected.id) },
                        onAddCollection=if(item.isPrivate)null else { selected->addToCollection(listOf(selected.id)) },
                        albumLinks = { selected ->
                            val ref = libraryRefsByAlias[selected.id]
                            if (selected.isPrivate || ref == null) emptyList() else GalleryLibraryState.albumLinks(ref.item, catalogFolders, libraryCatalogAssignments,
                                ref.deviceAlbumId?.let { albumId -> mapOf(ref.key to DeviceAlbum(albumId, ref.deviceAlbumName ?: "Без альбома")) }.orEmpty(),
                                entry.savedStateHandle.get<String>("gallery_origin_album"))
                        },
                        onOpenAlbum = { album, selected ->
                            val ref = libraryRefsByAlias[selected.id]
                            if (!selected.isPrivate && ref != null && !ref.item.isPrivate && !ref.item.hidden) {
                                galleryAlbumRequest = GalleryAlbumRequest(album,ref.key)
                                changeAppMode(AppMode.GALLERY)
                                if (album.key.startsWith("device:") && gallerySource != LibrarySource.PHONE) changeLibrarySource(LibrarySource.PHONE)
                                galleryTab = 0; librarySearchVisible = false
                                com.domenota.medialoader.ui.navigation.returnToLibrary(navController)
                            }
                        },
                    ) else MediaViewerScreen(
                        item = item,
                        currentAudioSessionOnly = entry.savedStateHandle.get<Boolean>("current_audio_session_only") == true,
                        onBack = { navController.popBackStack() },
                        onOpenExternal = { selected -> if (!selected.isPrivate) fileActionScope.launch {
                            if(libraryModel.verifyVisibilityOpen(selected,visibilityScope)) openSavedFile(context,selected)
                            else AppNotice.post(context,"Файл недоступен")
                        } },
                        onShare = { selected -> if (!selected.isPrivate) fileActionScope.launch {
                            if(libraryModel.verifyVisibilityOpen(selected,visibilityScope)) shareSavedFile(context,selected)
                            else AppNotice.post(context,"Файл недоступен")
                        } },
                        audioQueue = audioQueue,
                        photoQueue = photoQueue,
                        mediaQueue = gridQueue,
                        modernAudioLayout = !item.isPrivate,
                        onEqualizer = { navController.navigate("equalizer") },
                        audioMetadataRows = if (visibilityScope != null) viewerRows else publicLibraryRows,
                        audioBlockedIdentities = if (visibilityScope != null) libraryPrivateKeys.orEmpty() else collectionBlocked,
                        audioFavoriteIds = viewerFavorites,
                        onAudioFavorite = { selected -> fileActionScope.launch {
                            // Media3 may use an alias rather than the stable
                            // public-library ID. Always toggle the canonical row.
                            val canonicalId = libraryRefsByAlias[selected.id]?.key
                                ?: currentLibraryAudioId?.takeIf { selected.id == libraryAudioState.currentId }
                                ?: selected.id
                            val wasFavorite = libraryCatalogAssignments.any {
                                it.downloadId == canonicalId && it.isFavorite
                            }
                            try { setLibraryFavorite(canonicalId, !wasFavorite) }
                            catch (e: CancellationException) { throw e }
                            catch (_: Exception) { AppNotice.post(context, "Не удалось изменить избранное") }
                        } },
                        onAudioCurrentItemChanged = { entry.savedStateHandle["audio_current_id"] = it.id },
                        onPublicAudioToggle=::toggleLibraryPlayback,
                        onAudioRecognize={selected,position->runRestoredAction(listOf(selected.id)){restored->
                            val current=restored[selected.id]?:selected;openTrackRecognition(current.id,position,current.savedUri)
                        }},
                        onEdit = { selected ->
                            val selectedId=libraryRefsByAlias[selected.id]?.key ?: selected.id
                            openVisibilityEditor(selected.copy(id=selectedId))
                        },
                    ) }
                }
            },
            LibraryRouteContent(VIDEO_EDITOR_ROUTE) { entry ->
                val downloadId = entry.arguments?.getString("downloadId")
                val item = accessibleDownloads.firstOrNull { it.id == downloadId }
                if (item == null || item.savedUri == null || item.mediaType != MediaType.VIDEO) {
                    if (awaitingPhone(downloadId)) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    else LaunchedEffect(downloadId) { navController.popBackStack() }
                } else {
                    LaunchedEffect(item.id) { viewModel.clearEditorMessage() }
                    VideoEditorScreen(
                        item = item,
                        exportState = editorExport,
                        onBack = { navController.popBackStack() },
                        onCancelExport = viewModel::cancelVideoExport,
                        onExport = { spec ->
                            viewModel.exportVideo(item, spec)
                        },
                        onSaveFrame = { positionMs, spec ->
                            viewModel.saveVideoFrame(item, positionMs, spec)
                        },
                        onExtractAudio = { startMs, endMs ->
                            viewModel.extractAudioFromVideo(item, startMs, endMs)
                        },
                    )
                }
            },
            LibraryRouteContent(AUDIO_EDITOR_ROUTE) { entry ->
                val downloadId = entry.arguments?.getString("downloadId")
                val item = accessibleDownloads.firstOrNull { it.id == downloadId }
                if (item == null || item.savedUri == null || item.mediaType != MediaType.AUDIO) {
                    if (awaitingPhone(downloadId)) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    else LaunchedEffect(downloadId) { navController.popBackStack() }
                } else {
                    LaunchedEffect(item.id) { viewModel.clearEditorMessage() }
                    AudioEditorScreen(
                        item = item,
                        exportState = editorExport,
                        onBack = { navController.popBackStack() },
                        onCancelExport = viewModel::cancelVideoExport,
                        onExport = { spec -> viewModel.exportAudio(item, spec) },
                    )
                }
            },
            LibraryRouteContent(PHOTO_EDITOR_ROUTE) { entry ->
                val downloadId = entry.arguments?.getString("downloadId")
                val item = accessibleDownloads.firstOrNull { it.id == downloadId }
                if (item == null || item.savedUri == null || item.mediaType != MediaType.PHOTO) {
                    if (awaitingPhone(downloadId)) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    else LaunchedEffect(downloadId) { navController.popBackStack() }
                } else {
                    LaunchedEffect(item.id) { viewModel.clearEditorMessage() }
                    PhotoEditorScreen(
                        item = item,
                        exportState = editorExport,
                        onBack = { navController.popBackStack() },
                        onCancelExport = viewModel::cancelVideoExport,
                        onExport = { spec -> viewModel.exportPhoto(item, spec) },
                    )
                }
            },
            LibraryRouteContent(BULK_ROUTE) {
                BulkDownloadScreen(
                    state = bulk,
                    history = bulkHistory,
                    downloads = downloads,
                    onBack = { navController.popBackStack() },
                    onStart = viewModel::startBulk,
                    onToggle = viewModel::toggleBulk,
                    onSelectAll = viewModel::selectAllBulk,
                    onClearSelection = viewModel::clearBulkSelection,
                    onDiscardPreview = viewModel::discardBulkPreview,
                    onStopAnalysis = viewModel::stopBulkAnalysis,
                    onDisableAnalysisPause = viewModel::setBulkDisableAnalysisPause,
                    onConfirm = viewModel::confirmBulk,
                    onPause = viewModel::pauseBulk,
                    onResume = viewModel::resumeBulk,
                    onStop = viewModel::stopBulk,
                    onRetryFailed = viewModel::retryBulkFailed,
                    onOpenJob = viewModel::openBulkJob,
                    onClearHistory = viewModel::clearBulkHistory,
                    onOpenGroupMedia = openDownloadWithQueue,
                )
            },
            LibraryRouteContent(PREVIEW_ROUTE) {
                LaunchedEffect(preview.analysis) {
                    if (preview.analysis == AnalysisUiState.IDLE) navController.popBackStack()
                }
                PreviewScreen(
                    url = preview.url.ifBlank { pendingUrl },
                    state = preview.analysis,
                    items = preview.items.map { it.toPreviewUi(selected = it.id in preview.selectedIds) },
                    onBack = { navController.popBackStack() },
                    onToggleItem = viewModel::toggle,
                    onToggleAllPhotos = viewModel::toggleAllPhotos,
                    onDownloadSelected = requestDownload,
                    onSaveFrame = viewModel::savePreviewFrame,
                    resolvePlayback = viewModel::resolvePreviewPlayback,
                    onFindFullSong = viewModel::findFullSong,
                    onCloseFullSong = viewModel::dismissFullSong,
                    fullSongOpen = preview.fullSongOpen,
                    fullSongBusy = preview.fullSongBusy,
                    fullSongArtist = preview.fullSongArtist,
                    fullSongTitle = preview.fullSongTitle,
                    fullSongArtworkUrl = preview.fullSongArtworkUrl,
                    fullSongMessage = preview.fullSongMessage,
                    fullSongSessionId = preview.fullSongSessionId,
                    musicSource = preview.fullSongSource,
                    errorMessage = preview.message,
                    busy = preview.enqueuing,
                    onLogin = startLoginForCurrentUrl,
                    onRetry = { viewModel.analyze(preview.url.ifBlank { pendingUrl }, recoveryHistoryId = preview.recoveryHistoryId) },
                    recoveryMode=preview.recoveryHistoryId!=null,recoveryIdentityFailure=preview.recoveryIdentityFailure,
                    onCancelRecovery=viewModel::cancelRecoveryDownload,recoveryProgress=preview.recoveryProgress,
                    onDefer = { if(viewModel.deferLink(preview.url.ifBlank { pendingUrl },preview.recoveryHistoryId)){
                        showPicker=false;if(currentRoute==PREVIEW_ROUTE)navController.popBackStack()
                    } },
                )
            },
            ),
        )
        }
        val noticeExtra = when {
            currentRoute == "collections" -> collectionNoticeBottom
            galleryHomeOverlay -> 76.dp
            currentRoute in setOf(MEDIA_VIEWER_ROUTE, VIDEO_EDITOR_ROUTE, AUDIO_EDITOR_ROUTE, PHOTO_EDITOR_ROUTE, "batch-photo-editor", "trash-viewer/{downloadId}") -> 100.dp
            else -> 0.dp
        }
        val showWorkPanel = com.domenota.medialoader.ui.components.visibilityWorkPanelVisible(
            visibilityWorkState,visibilityWorkDismissed)
        val showCompactWorkPanel = showWorkPanel && !visibilityWorkExpanded &&
            visibilityMiniPanelHostAllowed(appMode,currentRoute)
        if (showCompactWorkPanel) {
            com.domenota.medialoader.ui.components.CollapsedTransferPanelHost(
                chromeBottom = innerPadding.calculateBottomPadding(),
            ) {
                com.domenota.medialoader.ui.components.FolderVisibilityMiniPanel(
                    state = visibilityWorkState,
                    onOpen = { visibilityWorkExpanded=true },
                    catalogStatus = postTransferCatalogStatus,
                )
            }
        }
        com.domenota.medialoader.ui.components.InAppNoticeHost(
            Modifier.align(Alignment.BottomCenter),
            bottomPadding = innerPadding.calculateBottomPadding() + noticeExtra +
                if(showCompactWorkPanel) 92.dp else 0.dp,
        )
        if (showWorkPanel && visibilityWorkExpanded) {
            com.domenota.medialoader.ui.components.FolderVisibilityTransferDialog(
                state = visibilityWorkState,
                folderName = visibilityProgress.folderId?.let { id ->
                    viewModel.catalogFolders.value.firstOrNull { it.id==id }?.name
                },
                onCollapse = { visibilityWorkExpanded=false },
                onPause = {
                    if(!visibilityWork.pauseAtNextCheckpoint())
                        AppNotice.post(context,"Пауза доступна при выполнении переноса")
                },
                onResume = { fileActionScope.launch {
                    try {
                        RetiredFolderVisibilityTransfer.requireActive()
                        visibilityAccess.ensure()
                        visibilityWork.resumeFromVisibleActivity()
                    } catch(cancelled:CancellationException){throw cancelled}
                    catch(error:Exception){AppNotice.post(context,error.message ?: "Не удалось продолжить перенос")}
                } },
                onDone = { visibilityWorkDismissed=true },
                catalogStatus = postTransferCatalogStatus,
            )
        }
        }
    }
    }
    if (showPicker && (currentRoute == AppDestination.Home.route || currentRoute == BROWSER_ROUTE)) {
        ModalBottomSheet(
            onDismissRequest = { showPicker = false },
            sheetState = pickerState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
        com.domenota.medialoader.ui.theme.TransparentWindowNavigation()
        Box {
            PreviewScreen(
                url = preview.url.ifBlank { pendingUrl },
                state = preview.analysis,
                items = preview.items.map { it.toPreviewUi(selected = it.id in preview.selectedIds) },
                onBack = { showPicker = false },
                onToggleItem = viewModel::toggle,
                onToggleAllPhotos = viewModel::toggleAllPhotos,
                onDownloadSelected = requestDownload,
                onSaveFrame = viewModel::savePreviewFrame,
                    resolvePlayback = viewModel::resolvePreviewPlayback,
                onFindFullSong = viewModel::findFullSong,
                onCloseFullSong = viewModel::dismissFullSong,
                fullSongOpen = preview.fullSongOpen,
                fullSongBusy = preview.fullSongBusy,
                fullSongArtist = preview.fullSongArtist,
                fullSongTitle = preview.fullSongTitle,
                fullSongArtworkUrl = preview.fullSongArtworkUrl,
                fullSongMessage = preview.fullSongMessage,
                fullSongSessionId = preview.fullSongSessionId,
                musicSource = preview.fullSongSource,
                errorMessage = preview.message,
                busy = preview.enqueuing,
                onLogin = startLoginForCurrentUrl,
                onRetry = { viewModel.analyze(preview.url.ifBlank { pendingUrl }, recoveryHistoryId = preview.recoveryHistoryId) },
                    recoveryMode=preview.recoveryHistoryId!=null,recoveryIdentityFailure=preview.recoveryIdentityFailure,
                    onCancelRecovery=viewModel::cancelRecoveryDownload,recoveryProgress=preview.recoveryProgress,
                    onDefer = { if(viewModel.deferLink(preview.url.ifBlank { pendingUrl },preview.recoveryHistoryId)){
                        showPicker=false;if(currentRoute==PREVIEW_ROUTE)navController.popBackStack()
                    } },
                asSheet = true,
            )
            com.domenota.medialoader.ui.components.InAppNoticeHost(
                Modifier.align(Alignment.BottomCenter), bottomPadding = 88.dp)
        }
        }
    }
}

/** Never reserve space for gallery's floating navigation in Scaffold.bottomBar. */
internal fun galleryBottomSwitcherIsOverlay(mode: AppMode, route: String?): Boolean =
    mode == AppMode.GALLERY && route == AppDestination.Home.route
