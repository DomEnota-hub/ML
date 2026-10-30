package com.domenota.medialoader.ui

import androidx.compose.foundation.layout.navigationBarsPadding

import com.domenota.medialoader.data.library.*
import com.domenota.medialoader.ui.components.AppModeTitle
import com.domenota.medialoader.ui.components.LibraryRefreshShield
import com.domenota.medialoader.ui.components.LibraryModeHeader
import com.domenota.medialoader.ui.components.LibraryTheme
import com.domenota.medialoader.ui.components.LibraryBottomSwitcher
import com.domenota.medialoader.ui.screens.gallery.DeviceAlbum
import com.domenota.medialoader.ui.screens.gallery.GalleryMediaViewer
import com.domenota.medialoader.ui.screens.gallery.GalleryViewerState
import com.domenota.medialoader.ui.screens.music.PlaylistTracksScreen
import com.domenota.medialoader.ui.components.LibraryMediaDeleteHost
import com.domenota.medialoader.ui.components.MediaDeletionTarget
import com.domenota.medialoader.ui.components.MediaManagementAccessPrompt
import com.domenota.medialoader.ui.components.label
import com.domenota.medialoader.ui.screens.GalleryModeScreen
import com.domenota.medialoader.ui.screens.MusicModeScreen
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
import android.widget.Toast
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
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
import com.domenota.medialoader.ui.model.AnalysisUiState
import com.domenota.medialoader.ui.model.BatchFileNames
import com.domenota.medialoader.ui.model.VkDownloadGroup
import com.domenota.medialoader.ui.model.toPreviewUi
import com.domenota.medialoader.ui.model.toUi
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
import com.domenota.medialoader.ui.screens.TrashScreen
import com.domenota.medialoader.ui.screens.VkCommunityScreen
import com.domenota.medialoader.ui.components.AudioMiniPlayer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Restore

private const val PRIVATE_ROUTE = "private"
private const val PLAYLIST_ADD_ROUTE = "playlist-add/{playlistId}"

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
    sharedText: String? = null,
    sharedTextGeneration: Int = 0,
    onSharedTextConsumed: () -> Unit = {},
    viewModel: MediaLoaderViewModel = viewModel(),
) {
    val context = LocalContext.current
    val navController = rememberNavController()
    val clipboardManager = LocalClipboardManager.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val fileActionScope = rememberCoroutineScope()
    val availabilityCache = remember(viewModel) { SavedFileStatusCache(viewModel::savedFileStatus) }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    var pendingUrl by rememberSaveable { mutableStateOf("") }
    var downloadsScrollToTopRequest by rememberSaveable { mutableIntStateOf(0) }
    var lastImportedClipboard by remember { mutableStateOf("") }
    var dismissedClipboard by remember { mutableStateOf("") }
    var checkedClipboardAtLaunch by remember { mutableStateOf(false) }
    var showPicker by rememberSaveable { mutableStateOf(false) }
    val uiPrefs = remember(context) { context.getSharedPreferences("ui", 0) }
    val libraryModel: LibraryViewModel = viewModel()
    val libraryPreferences = remember(context) { LibraryPreferences(context) }
    var appMode by rememberSaveable { mutableStateOf(libraryPreferences.mode) }
    var privateNavigation by remember { mutableStateOf(false) }
    var galleryTab by rememberSaveable { mutableIntStateOf(0) }
    var galleryTopRequest by rememberSaveable { mutableIntStateOf(0) }
    var galleryDetailOpen by remember { mutableStateOf(false) }
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
    val libraryIndex by libraryModel.index.collectAsStateWithLifecycle()
    val unifiedLibrary = libraryIndex.refs
    val libraryAudioState by libraryModel.audioPreview.state.collectAsStateWithLifecycle()
    val libraryPrivateKeys by libraryModel.privateKeys.collectAsStateWithLifecycle()
    val publicLibraryRefs = remember(unifiedLibrary, libraryPrivateKeys) {
        if (libraryPrivateKeys == null) emptyList() else UnifiedMediaLibrary.merge(unifiedLibrary, emptyList(), libraryPrivateKeys.orEmpty())
    }
    val libraryItems = remember(publicLibraryRefs) { publicLibraryRefs.map { it.item } }
    val libraryRefsByAlias = remember(publicLibraryRefs) { buildMap {
        publicLibraryRefs.forEach { ref -> (ref.aliases + ref.key).forEach { alias -> put(alias, ref) } }
    } }
    val libraryAliases = remember(libraryRefsByAlias) { libraryRefsByAlias.mapValues { it.value.key } }
    val currentLibraryAudioId = libraryAudioState.currentId?.let { id -> libraryRefsByAlias[id]?.key
        ?: libraryAudioState.identities.firstNotNullOfOrNull { libraryRefsByAlias[it]?.key } ?: id }
    val externalCatalog by libraryModel.externalCatalog.entries.collectAsStateWithLifecycle()
    val externalByAlias = remember(externalCatalog) { externalCatalog.flatMap { entry ->
        (entry.ref.aliases + entry.ref.key).map { it to entry }
    }.toMap() }
    val appLibraryItems = remember(publicLibraryRefs, externalByAlias) { publicLibraryRefs.filter { ref ->
        ref.appDownloadId != null || ref.aliases.any { externalByAlias[it]?.folderId != null }
    }.map { it.item } }
    val playlists by libraryModel.playlistRepository.playlists.collectAsStateWithLifecycle()
    var playlistsReady by remember { mutableStateOf(false) }
    LaunchedEffect(libraryModel) { libraryModel.playlistRepository.awaitLoaded(); playlistsReady = true }
    LaunchedEffect(libraryModel) {
        try { libraryModel.audioPreview.observe() }
        catch (e: CancellationException) { throw e } catch (_: Exception) { /* A later play request can reconnect. */ }
    }
    val phoneRows = phoneGallery.items + phoneMusic.items
    var permissionMode by rememberSaveable { mutableStateOf(AppMode.GALLERY) }
    val libraryPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        libraryModel.refresh(permissionMode)
    }
    fun requestLibraryAccess(mode: AppMode) {
        permissionMode = mode
        libraryPermissions.launch(PhoneMediaLibrary.permissions(mode))
    }
    fun changeAppMode(next: AppMode) {
        // Check access synchronously before a cached phone-backed screen can be rendered.
        if (next == AppMode.MUSIC || (next == AppMode.GALLERY &&
                (gallerySource == LibrarySource.PHONE || phoneGallery.loaded))) libraryModel.refresh(next)
        libraryPreferences.mode = next
        appMode = next
    }
    fun changeLibrarySource(next: LibrarySource) {
        if (next == LibrarySource.PHONE) libraryModel.refresh(appMode)
        libraryPreferences.setSource(appMode, next)
        if (appMode == AppMode.GALLERY) gallerySource = next else musicLibrarySource = next
        if (next == LibrarySource.PHONE && !hasPhoneLibraryAccess(context, appMode)) requestLibraryAccess(appMode)
    }
    val phoneViewingId = backStackEntry?.arguments?.getString("downloadId")
    fun refreshPhoneLibraries(force: Boolean = false) {
        val needed = mutableSetOf<AppMode>()
        if (appMode == AppMode.GALLERY && (librarySource == LibrarySource.PHONE ||
                externalCatalog.any { it.ref.item.mediaType != MediaType.AUDIO })) needed += AppMode.GALLERY
        if (appMode == AppMode.MUSIC) needed += AppMode.MUSIC
        if (phoneViewingId?.startsWith("phone:") == true) needed +=
            if (phoneViewingId.startsWith("phone:audio:")) AppMode.MUSIC else AppMode.GALLERY
        needed.forEach { mode ->
            val state = if (mode == AppMode.GALLERY) libraryModel.gallery.value else libraryModel.music.value
            if (force || (!state.loaded && !state.loading)) libraryModel.refresh(mode)
        }
    }
    LaunchedEffect(appMode, librarySource, currentRoute) {
        // Refresh on returning from edits/Personal so new outputs and removed public copies appear immediately.
        refreshPhoneLibraries(force = currentRoute == AppDestination.Home.route)
    }
    LaunchedEffect(phoneViewingId) { refreshPhoneLibraries() }
    LaunchedEffect(externalCatalog.map { it.ref.key }) { refreshPhoneLibraries() }
    DisposableEffect(lifecycleOwner, appMode, librarySource, phoneViewingId) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refreshPhoneLibraries(force = true) }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    fun awaitingPhone(id: String?): Boolean = id?.startsWith("phone:") == true &&
        (if (id.startsWith("phone:audio:")) !phoneMusic.loaded else !phoneGallery.loaded)
    val libraryHeader: @Composable () -> Unit = {
        LibraryModeHeader(appMode, librarySource, ::changeAppMode, ::changeLibrarySource,
            onAccess = { requestLibraryAccess(appMode) },
            onSettings = { navController.navigate(AppDestination.Settings.route) },
            onSearch = { librarySearchVisible = !librarySearchVisible },
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
            Toast.makeText(context, "Не удалось сохранить музыкальный источник", Toast.LENGTH_LONG).show()
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
    val bulk by viewModel.bulkState.collectAsStateWithLifecycle()
    val bulkHistory by viewModel.bulkHistory.collectAsStateWithLifecycle()
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val privateDownloads by viewModel.privateDownloads.collectAsStateWithLifecycle()
    val privateState by viewModel.privateAccess.state.collectAsState()
    // Keep old phone IDs resolvable for restored routes/playlists without showing duplicate cards.
    val publicLibraryRows = remember(downloads, publicLibraryRefs, libraryAudioState.queue, libraryPrivateKeys) {
        val knownIds = downloads.mapTo(mutableSetOf()) { it.id }
        val indexed = downloads + publicLibraryRefs.flatMap { ref ->
            (ref.aliases.filter { it.startsWith("phone:") } + ref.key).distinct().map { ref.item.copy(id = it) }
        }.filter { knownIds.add(it.id) }
        val session = libraryPrivateKeys?.let { keys ->
            com.domenota.medialoader.data.playback.AudioPlaybackMetadata.publicSessionRows(libraryAudioState.queue, indexed, keys)
        }.orEmpty()
        indexed + session.filter { knownIds.add(it.id) }
    }
    val accessibleDownloads = if (privateState.unlocked) publicLibraryRows + privateDownloads else publicLibraryRows
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
    fun requestPrivateMove(ids: List<String>) {
        if (privateMoveBusy || ids.isEmpty()) return
        if (privateState.mode == com.domenota.medialoader.data.privatevault.PrivateAccessMode.UNCONFIGURED) {
            pendingPrivateMoves = ids
            returnAfterPrivateSetup = true
            privateNavigation = true
            navController.navigate(PRIVATE_ROUTE)
        } else viewModel.startPrivateMove(ids)
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
            Toast.makeText(context, "Перенос в личное · ${result.summary}", Toast.LENGTH_LONG).show()
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
            Toast.makeText(context, "Перенос был прерван. Проверьте файлы в загрузках и личном.", Toast.LENGTH_LONG).show()
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
    val libraryCatalogAssignments = remember(publicLibraryRefs, externalCatalog, catalogAssignments, catalogFolders) {
        val validFolders = catalogFolders.mapTo(mutableSetOf()) { it.id }
        val externalAssignments = publicLibraryRefs.mapNotNull { ref ->
            ref.aliases.firstNotNullOfOrNull { externalByAlias[it] }?.let { entry ->
                com.domenota.medialoader.core.database.CatalogAssignmentEntity(ref.key,
                    entry.folderId?.takeIf { it in validFolders }, entry.isFavorite)
            }
        }
        (catalogAssignments + externalAssignments).distinctBy { it.downloadId }
    }
    val setLibraryFavorite: suspend (String, Boolean) -> Unit = { id, favorite ->
        val ref = libraryRefsByAlias[id] ?: currentLibraryAudioId?.takeIf { id == libraryAudioState.currentId }?.let(libraryRefsByAlias::get)
        requireNotNull(ref) { "Файл недоступен" }
        if (ref.appDownloadId != null) {
            libraryModel.externalCatalog.setExistingFavorite(ref, favorite)
            viewModel.setCatalogFavorite(ref.appDownloadId, favorite)
        }
        else libraryModel.externalCatalog.setFavorite(ref, favorite)
    }
    val setLibraryFolder: suspend (String, String?) -> Unit = { id, folder ->
        val ref = publicLibraryRefs.firstOrNull { it.key == id || id in it.aliases }
        requireNotNull(ref) { "Файл недоступен" }
        require(folder == null || catalogFolders.any { it.id == folder }) { "Папка не найдена" }
        if (ref.appDownloadId != null) {
            libraryModel.externalCatalog.setExistingFolder(ref, folder)
            viewModel.setCatalogFolder(ref.appDownloadId, folder)
        }
        else libraryModel.externalCatalog.setFolder(ref, folder)
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
    val completedUris = remember(downloads) {
        downloads.filter { it.state == DownloadState.COMPLETED && !it.hidden }
            .map { it.id to it.savedUri }
    }
    var fileStatuses by remember {
        mutableStateOf(emptyMap<String, SavedFileStatus>())
    }
    LaunchedEffect(completedUris, fileScanEpoch) {
        delay(1_200L)
        fileStatuses = withContext(Dispatchers.IO) {
            completedUris.associate { (id, uri) -> id to availabilityCache.get(uri) }
        }
    }
    val downloadItems = remember(downloads, fileStatuses) {
        downloads.map { it.toUi(fileStatuses[it.id] ?: SavedFileStatus.UNCHECKED) }
    }

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
        if (musicLookupAvailable && !closePickerAfterDownload) {
            // Keep analyzed assets available for both audio choices without another analysis.
            Toast.makeText(context, "Добавлено в загрузки. Можно выбрать второй вариант.",
                Toast.LENGTH_LONG).show()
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
        pendingNames = BatchFileNames.defaults(preview.items.filter { it.id in preview.selectedIds }
            .map { it.id to it.originalName })
        batchName = ""
        if (pendingNames.isNotEmpty()) showNameDialog = true
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
    val openDownloadWithQueue: (String, List<String>) -> Unit = { id, queueIds ->
        (accessibleDownloads.firstOrNull { it.id == id } ?: libraryRefsByAlias[id]?.item
            ?: currentLibraryAudioId?.takeIf { id == libraryAudioState.currentId }?.let { libraryRefsByAlias[it]?.item })?.let { item ->
          fileActionScope.launch {
            val status = withContext(Dispatchers.IO) { availabilityCache.get(item.savedUri, fresh = true) }
            fileStatuses = fileStatuses + (id to status)
            if (status == SavedFileStatus.MISSING) {
                AppLog.w("History", "Open prevented · saved file missing · id=$id")
                Toast.makeText(context, "Файл удалён с устройства", Toast.LENGTH_SHORT).show()
            } else if (status == SavedFileStatus.INACCESSIBLE) {
                Toast.makeText(context, "Нет доступа к файлу", Toast.LENGTH_SHORT).show()
            } else if ((internalViewerEnabled || item.isPrivate || appMode != AppMode.DOWNLOADER) && item.state == DownloadState.COMPLETED && item.savedUri != null) {
                if (item.mediaType == MediaType.AUDIO && !item.isPrivate) {
                    val selectedRows = LibraryAudioQueue.resolve(queueIds, publicLibraryRows, false, fileStatuses)
                    val selected = (listOf(item) + selectedRows).distinctBy { it.savedUri }.map { row ->
                        libraryRefsByAlias[row.id] ?: com.domenota.medialoader.data.library.LibraryMediaRef(
                            row.id, com.domenota.medialoader.data.playback.PublicMediaIdentity.uriKeys(requireNotNull(row.savedUri)) + row.id,
                            row, row.id.takeUnless { it.startsWith("phone:") })
                    }.toMutableList()
                    val ref = libraryRefsByAlias[item.id] ?: selected.first().copy(aliases = selected.first().aliases +
                        withContext(Dispatchers.IO) { com.domenota.medialoader.data.playback.PublicMediaIdentity.capture(context, requireNotNull(item.savedUri)) })
                    val queue = selectedRows.mapNotNull { row -> selected.firstOrNull { it.item.savedUri == row.savedUri } }
                    try { libraryModel.audioPreview.open(if (queue.any { it.key == ref.key }) queue.map { if (it.key == ref.key) ref else it } else listOf(ref) + queue, ref.key) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) {
                        Toast.makeText(context, "Не удалось подключиться к музыкальному плееру", Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                }
                navController.navigate("viewer/${item.id}")
                navController.currentBackStackEntry?.savedStateHandle?.set("grid_viewer_ids", queueIds)
                navController.currentBackStackEntry?.savedStateHandle?.set("gallery_presentation",
                    item.mediaType != MediaType.AUDIO && !item.isPrivate &&
                        (appMode == AppMode.GALLERY || queueIds.isNotEmpty()))
            } else {
                openSavedFile(context, item)
            }
          }
        }
    }
    val openDownload: (String) -> Unit = { openDownloadWithQueue(it, emptyList()) }
    val shareDownload: (String) -> Unit = { id ->
        downloads.firstOrNull { it.id == id }?.let { item ->
            shareSavedFile(context, item)
        }
    }

    val shareLibraryFile: (String) -> Unit = { id ->
        publicLibraryRows.firstOrNull { it.id == id && !it.isPrivate }?.let { shareSavedFile(context, it) }
    }
    // Store ID/URI pairs rather than re-looking up provider rows after Android deletes them.
    var pendingDeleteRecords by rememberSaveable(stateSaver = com.domenota.medialoader.ui.components.MediaSelectionState.saver) { mutableStateOf(arrayListOf<String>()) }
    val deleteLibraryFiles: (List<String>) -> Unit = { ids ->
        val phoneById = phoneRows.associateBy { it.id }
        val appById = accessibleDownloads.associateBy { it.id }
        pendingDeleteRecords = ArrayList(ids.distinct().mapNotNull { id ->
            val row = if (id.startsWith("phone:")) phoneById[id]
                else appById[id] ?: libraryRefsByAlias[id]?.item
            row?.takeUnless { it.isPrivate || it.hidden }?.let { listOf(id, it.savedUri.orEmpty()) }
        }.flatten())
    }
    val deleteLibraryFile: (String) -> Unit = { deleteLibraryFiles(listOf(it)) }
    if (pendingDeleteRecords.isNotEmpty()) {
        val targets = remember(pendingDeleteRecords) { pendingDeleteRecords.chunked(2).map {
            MediaDeletionTarget(it[0], it[1].ifBlank { null })
        } }
        fun finishDeletion() {
            pendingDeleteRecords = arrayListOf()
            if (targets.any { it.phone }) {
                libraryModel.refresh(AppMode.GALLERY, force = true)
                libraryModel.refresh(AppMode.MUSIC, force = true)
            }
        }
        LibraryMediaDeleteHost(targets, onDismiss = ::finishDeletion,
            onDeleteApp = viewModel::hideLibraryFile, onDeleted = ::finishDeletion)
    }
    val previewLibraryAudio: (String, List<String>) -> Unit = { id, ids ->
        libraryPreviewJob?.cancel()
        libraryPreviewJob = fileActionScope.launch {
            val ref = libraryRefsByAlias[id] ?: return@launch
            try {
                val status = withContext(Dispatchers.IO) { availabilityCache.get(ref.item.savedUri, fresh = true) }
                if (status != SavedFileStatus.PRESENT) {
                    Toast.makeText(context, "Трек недоступен. Проверьте файл и разрешения.", Toast.LENGTH_SHORT).show()
                } else {
                    val queue = ids.mapNotNull(libraryRefsByAlias::get).distinctBy { it.key }
                    libraryModel.audioPreview.preview(queue, ref.key)
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { Toast.makeText(context, "Не удалось начать воспроизведение", Toast.LENGTH_SHORT).show() }
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
            Toast.makeText(context, "Исходная ссылка недоступна — повторный поиск невозможен",
                Toast.LENGTH_LONG).show()
        } else {
            pendingUrl = link
            lastHandledLink = link
            navigateTo(AppDestination.Home.route)
            viewModel.analyze(link)
            showPicker = true
        }
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

    // Read only while the app is visible. Never replace a link the user typed herself.
    DisposableEffect(lifecycleOwner, currentRoute, sharedText) {
        fun importSupportedLink() {
            if (currentRoute != AppDestination.Home.route || sharedText != null) return
            val firstCheck = !checkedClipboardAtLaunch
            checkedClipboardAtLaunch = true
            val candidate = clipboardManager.getText()?.text?.let(::extractFirstUrl) ?: return
            if (InstagramLinkParser.parse(candidate) == null && YouTubeLinkParser.parse(candidate) == null &&
                VkLinkParser.parse(candidate) == null && RutubeLinkParser.parse(candidate) == null) return
            if (candidate == dismissedClipboard) return
            if (firstCheck || pendingUrl.isBlank() || pendingUrl == lastImportedClipboard) {
                pendingUrl = candidate
                if (candidate != lastHandledLink) {
                    showPicker = true
                    viewModel.analyze(candidate)
                    lastHandledLink = candidate
                }
                lastImportedClipboard = candidate
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) importSupportedLink()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) importSupportedLink()
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val checkingPhoneAccess = if (currentRoute == AppDestination.Home.route && appMode != AppMode.DOWNLOADER)
        visibleLibraryValidationPending(appMode, libraryIndex, phoneGallery, phoneMusic)
    else phoneValidationPending(libraryIndex, phoneGallery, phoneMusic)
    MediaManagementAccessPrompt(enabled = appMode != AppMode.DOWNLOADER &&
        currentRoute == AppDestination.Home.route && !checkingPhoneAccess)
    LibraryTheme(enabled = (appMode != AppMode.DOWNLOADER && currentRoute in setOf(AppDestination.Home.route, MEDIA_VIEWER_ROUTE)) || currentRoute in setOf(PLAYLIST_ADD_ROUTE, "equalizer")) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (currentRoute != PRIVATE_ROUTE && currentRoute != PREVIEW_ROUTE && currentRoute != BROWSER_ROUTE &&
                currentRoute != BULK_ROUTE && currentRoute != MEDIA_VIEWER_ROUTE &&
                currentRoute != VIDEO_EDITOR_ROUTE && currentRoute != AUDIO_EDITOR_ROUTE &&
                currentRoute != PHOTO_EDITOR_ROUTE && currentRoute != PLAYLIST_ADD_ROUTE && currentRoute != "equalizer" &&
                currentRoute != "trash" && currentRoute != "trash-viewer/{downloadId}" &&
                currentRoute != "diagnostics" && currentRoute != "vk-community") {
                Column(if (appMode != AppMode.DOWNLOADER && currentRoute == AppDestination.Home.route)
                    Modifier.navigationBarsPadding() else Modifier) {
                    if (!checkingPhoneAccess) AudioMiniPlayer(
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
                                    if (musicTab == 0 && it == 0) musicTopRequest++
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
        LibraryRefreshShield(
            checkingPhoneAccess && currentRoute != AppDestination.Settings.route &&
                !(appMode == AppMode.DOWNLOADER && currentRoute == AppDestination.Home.route),
            Modifier.padding(innerPadding),
            onExit = if (appMode != AppMode.DOWNLOADER && currentRoute == AppDestination.Home.route)
                ({ changeAppMode(AppMode.DOWNLOADER) }) else null,
        ) {
        NavHost(
            navController = navController,
            startDestination = AppDestination.Home.route,
            modifier = Modifier.fillMaxSize(),
        ) {
            composable(AppDestination.Home.route) {
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
                        lastHandledLink = pendingUrl
                        viewModel.analyze(pendingUrl)
                        showPicker = true
                    },
                    onOpenDownloads = goToDownloads,
                    onOpenSource = { source ->
                        showPicker = false
                        navController.navigate("browser/$source")
                    },
                    recentDownloads = downloadItems.take(3),
                    onOpenDownload = openDownload,
                    onCancelDownload = viewModel::cancelDownload,
                    onShareDownload = shareDownload,
                    onHideDownload = deleteLibraryFile,
                    onRetryDownload = viewModel::retryDownload,
                    onReanalyzeMissing = reanalyzeMissingFile,
                    onRenameDownload = viewModel::renameDownload,
                    recognitionEnabled = musicRecognitionMode != MusicRecognitionMode.OFF,
                    onRecognizeDownload = viewModel::recognizeDownload,
                )
                else if (appMode == AppMode.GALLERY) GalleryModeScreen(
                    items = if (librarySource == LibrarySource.APP) appLibraryItems else if (!phoneGallery.loaded) emptyList() else libraryItems,
                    source = librarySource, loading = !libraryIndex.ready || (librarySource == LibrarySource.PHONE && !phoneGallery.loaded && phoneGallery.loading),
                    message = if (librarySource == LibrarySource.PHONE) phoneGallery.message else null,
                    folders = catalogFolders, assignments = libraryCatalogAssignments, header = libraryHeader,
                    onOpen = openDownloadWithQueue,
                    onOpenPrivate = { viewModel.privateAccess.lock(); privateNavigation = true; navController.navigate(PRIVATE_ROUTE) },
                    onFavorite = setLibraryFavorite, onSetFolder = setLibraryFolder,
                    onCreateFolder = viewModel::createCatalogFolder,
                    onDeleteFolder = { folderId ->
                        viewModel.deleteCatalogFolder(folderId)
                        libraryModel.externalCatalog.removeFolder(folderId)
                    }, onShare = shareLibraryFile,
                    onDelete = deleteLibraryFile, onDeleteSelected = deleteLibraryFiles, onRename = viewModel::renameDownload,
                    onMoveToPrivate = { requestPrivateMove(listOf(it)) },
                    searchVisible = librarySearchVisible, tabOverride = galleryTab,
                     scrollToTopRequest = galleryTopRequest, onTabChange = {
                         if (galleryTab == 0 && it == 0) galleryTopRequest++
                         galleryTab = it
                     },
                    onDetailChange = { galleryDetailOpen = it }, onSettings = { navController.navigate(AppDestination.Settings.route) },
                    deviceAlbums = publicLibraryRefs.mapNotNull { ref -> ref.deviceAlbumId?.let { id ->
                        ref.key to DeviceAlbum(id, ref.deviceAlbumName ?: "Без альбома")
                    } }.toMap())
                else MusicModeScreen(
                    items = if (librarySource == LibrarySource.APP) appLibraryItems else if (!phoneMusic.loaded) emptyList() else libraryItems,
                    allAudio = libraryItems.filter { it.mediaType == MediaType.AUDIO }, playlists = playlists,
                    header = libraryHeader, loading = !libraryIndex.ready || (librarySource == LibrarySource.PHONE && !phoneMusic.loaded && phoneMusic.loading),
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
                    searchVisible = librarySearchVisible, tabOverride = musicTab,
                     scrollToTopRequest = musicTopRequest, onTabChange = {
                         if (musicTab == 0 && it == 0) musicTopRequest++
                         musicTab = it
                     },
                    onEnqueue = { id, next ->
                        fileActionScope.launch {
                            val ref = libraryRefsByAlias[id] ?: return@launch
                            try { libraryModel.audioPreview.enqueue(ref, next) }
                            catch (e: CancellationException) { throw e }
                            catch (_: Exception) { Toast.makeText(context, "Не удалось добавить трек в очередь", Toast.LENGTH_SHORT).show() }
                        }
                    },
                    onPreview = previewLibraryAudio,
                    onAddPlaylistTracks = { navController.navigate("playlist-add/$it") },
                    onDetailChange = { musicDetailOpen = it }, currentPlayingId = currentLibraryAudioId,
                    playing = libraryAudioState.playing)
            }
            composable("equalizer") {
                val settings by libraryModel.equalizerSettings.collectAsStateWithLifecycle()
                val sampleRate by com.domenota.medialoader.data.playback.EqualizerPlaybackFormat.sampleRate.collectAsStateWithLifecycle()
                val error by libraryModel.equalizerError.collectAsStateWithLifecycle()
                Column(Modifier.fillMaxSize()) {
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(17.dp)) }
                    settings?.let { com.domenota.medialoader.ui.screens.music.EqualizerScreen(it, sampleRate,
                        libraryModel::setEqualizer, { navController.popBackStack() }) }
                        ?: Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
            }
            composable(PLAYLIST_ADD_ROUTE) { entry ->
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
                    }, appTracks = appLibraryItems.filter { it.mediaType == MediaType.AUDIO },
                    allTracks = libraryItems.filter { it.mediaType == MediaType.AUDIO }, aliases = libraryAliases,
                    onBack = { navController.popBackStack() }, onPreview = previewLibraryAudio,
                    onAdd = { libraryModel.playlistRepository.addTracksResolved(playlist.id, it, libraryAliases) },
                    validateSelection = { ids -> withContext(Dispatchers.IO) {
                        ids.filter { id -> libraryRefsByAlias[id]?.item?.savedUri?.let {
                            availabilityCache.get(it, fresh = true) == SavedFileStatus.PRESENT
                        } == true }
                    } },
                    miniPlayer = { AudioMiniPlayer(onOpen = openDownload, onOpenQueue = openDownloadWithQueue) },
                    loading = musicLibrarySource == LibrarySource.PHONE && phoneMusic.loading,
                    message = phoneMusic.message, onAccess = { requestLibraryAccess(AppMode.MUSIC) },
                    onSettings = { navController.navigate(AppDestination.Settings.route) },
                    currentPlayingId = currentLibraryAudioId, playing = libraryAudioState.playing,
                )
            }
            composable(BROWSER_ROUTE) { entry ->
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
            }
            composable(AppDestination.Downloads.route) {
                DownloadsScreen(
                    items = downloadItems,
                    onOpenPrivate = { viewModel.privateAccess.lock(); pendingPrivateMoves = emptyList(); returnAfterPrivateSetup = false; privateNavigation = true; navController.navigate(PRIVATE_ROUTE) },
                    onMoveToPrivate = { id -> requestPrivateMove(listOf(id)) },
                    onMoveGroupToPrivate = ::requestPrivateMove,
                    onSetFavorite = viewModel::setCatalogFavorite,
                    onDeleteGroupFile = viewModel::hideGroupFile,
                    scrollToTopRequest = downloadsScrollToTopRequest,
                    catalogFolders = catalogFolders,
                    catalogAssignments = catalogAssignments,
                    onCreateFolder = viewModel::createCatalogFolder,
                    onRenameFolder = viewModel::renameCatalogFolder,
                    onDeleteFolder = viewModel::deleteCatalogFolder,
                    onSetFolder = viewModel::setCatalogFolder,
                    onToggleFavorite = viewModel::toggleCatalogFavorite,
                    onOpenDownload = openDownload,
                    onOpenGrid = openDownloadWithQueue,
                    onCancelDownload = viewModel::cancelDownload,
                    onShareDownload = shareDownload,
                    onHideDownload = deleteLibraryFile,
                    onRetryDownload = viewModel::retryDownload,
                    onReanalyzeMissing = reanalyzeMissingFile,
                    onRenameDownload = viewModel::renameDownload,
                    recognitionEnabled = musicRecognitionMode != MusicRecognitionMode.OFF,
                    onRecognizeDownload = viewModel::recognizeDownload,
                )
            }
            composable(PRIVATE_ROUTE) {
                PrivateScreen(viewModel.privateAccess, privateDownloads,
                    onBack = { pendingPrivateMoves = emptyList(); returnAfterPrivateSetup = false; viewModel.privateAccess.lock(); navController.popBackStack() },
                    onOpen = openDownload,
                    onOpenGrid = openDownloadWithQueue,
                    onRestore = viewModel::restorePrivate,
                    onFinishMove = viewModel::finishPrivateMove,
                    onDelete = viewModel::deletePrivate,
                    onRename = viewModel::renameDownload,
                    externalBusy = privateMoveBusy || (returnAfterPrivateSetup && privateState.mode != com.domenota.medialoader.data.privatevault.PrivateAccessMode.UNCONFIGURED),
                    catalogFolders = catalogFolders,
                    catalogAssignments = catalogAssignments,
                    onSetFolder = viewModel::setCatalogFolder,
                    onSetFavorite = viewModel::setCatalogFavorite,
                    onCreateFolder = viewModel::createCatalogFolder)
            }
            composable(AppDestination.Settings.route) {
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
                    hiddenItems = hiddenItems.map { entry ->
                        entry.toUi().copy(savedUri = viewModel.trashedUri(entry.id))
                    },
                    editorItems = accessibleDownloads.filter {
                        it.state == DownloadState.COMPLETED && it.savedUri != null
                    }.map { it.toUi() },
                    editorAlbumsById = remember(
                        accessibleDownloads, libraryRefsByAlias, libraryCatalogAssignments, catalogFolders
                    ) {
                        val folderNames = catalogFolders.associate { it.id to it.name }
                        val assigned = libraryCatalogAssignments.associateBy { it.downloadId }
                        accessibleDownloads.associate { file ->
                            val ref = libraryRefsByAlias[file.id]
                            val assignment = assigned[file.id] ?: ref?.let { assigned[it.key] }
                            val names = listOfNotNull(
                                assignment?.folderId?.let(folderNames::get),
                                ref?.deviceAlbumName?.takeIf { it.isNotBlank() },
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
                    onRestore = viewModel::restoreHidden,
                    onEmptyTrash = viewModel::clearHidden,
                    onOpenTrash = { navController.navigate("trash") },
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
            }
            composable("diagnostics") {
                DiagnosticsScreen(
                    onBack = { navController.popBackStack() },
                    versionName = BuildConfig.VERSION_NAME,
                    versionCode = BuildConfig.VERSION_CODE,
                )
            }
            composable("vk-community") {
                VkCommunityScreen(onBack = { navController.popBackStack() })
            }
            composable("trash") {
                val trashRows = hiddenItems.map { entry ->
                    entry.toUi().copy(savedUri = viewModel.trashedUri(entry.id))
                }
                TrashScreen(
                    items = trashRows,
                    onBack = { navController.popBackStack() },
                    onOpen = { id ->
                        if (viewModel.trashedUri(id) == null) {
                            Toast.makeText(context, "Файл в корзине недоступен", Toast.LENGTH_SHORT).show()
                        } else navController.navigate("trash-viewer/$id")
                    },
                    onRestore = viewModel::restoreHidden,
                    onDelete = viewModel::deleteHidden,
                )
            }
            composable("trash-viewer/{downloadId}") { entry ->
                val id = entry.arguments?.getString("downloadId")
                val original = hiddenItems.firstOrNull { it.id == id }
                val privateUri = id?.let(viewModel::trashedUri)
                if (original == null || privateUri == null) {
                    LaunchedEffect(id) {
                        Toast.makeText(context, "Файл в корзине недоступен", Toast.LENGTH_SHORT).show()
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
            }
            composable(MEDIA_VIEWER_ROUTE) { entry ->
                val viewerState = rememberSaveableStateHolder()
                val downloadId = entry.arguments?.getString("downloadId")
                val galleryPresentation = entry.savedStateHandle.get<Boolean>("gallery_presentation") == true
                val snapshot = entry.savedStateHandle.get<List<String>>("grid_viewer_ids").orEmpty()
                val currentGalleryId = entry.savedStateHandle.get<String>("gallery_current_id") ?: downloadId
                val galleryCandidates = if (galleryPresentation) GalleryViewerState.publicCandidates(publicLibraryRefs, libraryPrivateKeys,
                    snapshot.toSet() + listOfNotNull(downloadId)).filter { row ->
                    !row.hidden && !row.isPrivate && row.savedUri != null && row.state == DownloadState.COMPLETED &&
                        fileStatuses[row.id] != SavedFileStatus.MISSING && fileStatuses[row.id] != SavedFileStatus.INACCESSIBLE
                } else emptyList()
                val resolvedGalleryId = GalleryViewerState.neighbor(currentGalleryId, snapshot.ifEmpty { listOfNotNull(downloadId) },
                    galleryCandidates.mapTo(mutableSetOf()) { it.id })
                val audioCurrentId = entry.savedStateHandle.get<String>("audio_current_id")
                val item = if (galleryPresentation) galleryCandidates.firstOrNull { it.id == resolvedGalleryId }
                    else accessibleDownloads.firstOrNull { it.id == (audioCurrentId ?: downloadId) }
                        ?: libraryRefsByAlias[audioCurrentId ?: downloadId]?.item
                        ?: accessibleDownloads.firstOrNull { it.id == downloadId }
                if (item == null || item.savedUri == null) {
                    if (awaitingPhone(downloadId) || (galleryPresentation && (!libraryIndex.ready || libraryPrivateKeys == null))) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    else LaunchedEffect(downloadId) { navController.popBackStack() }
                } else {
                    val viewerRows = if (item.isPrivate) privateDownloads else if (galleryPresentation) galleryCandidates else publicLibraryRows
                    val availablePhotos = viewerRows.filter { row ->
                        row.mediaType == MediaType.PHOTO &&
                            row.state == DownloadState.COMPLETED &&
                            !row.hidden &&
                            row.savedUri != null &&
                            fileStatuses[row.id] != SavedFileStatus.MISSING
                    }.distinctBy { it.savedUri }
                    val photoQueue = if (item.mediaType == MediaType.PHOTO) {
                        val groupKey = VkDownloadGroup.key(item.id, item.providerId, item.groupId)
                        val grouped = availablePhotos
                            .filter { VkDownloadGroup.key(it.id, it.providerId, it.groupId) == groupKey }
                            .sortedWith(compareBy({ it.createdAtEpochMillis }, { it.originalName }))
                        if (grouped.size > 1) grouped else availablePhotos
                    } else {
                        emptyList()
                    }
                    viewerState.SaveableStateProvider("content") {
                    if (galleryPresentation && item.mediaType != MediaType.AUDIO) GalleryMediaViewer(
                        item = item,
                        media = com.domenota.medialoader.data.playback.GridMediaQueue.resolve(snapshot, viewerRows, false, fileStatuses)
                            .ifEmpty { listOf(item) },
                        onBack = { navController.popBackStack() }, onShare = { shareSavedFile(context, it) },
                        onEdit = { selected -> navController.navigate(if (selected.mediaType == MediaType.VIDEO)
                            "video-editor/${selected.id}" else "photo-editor/${selected.id}") },
                        onDelete = { deleteLibraryFile(it.id) },
                        favoriteIds = libraryCatalogAssignments.filter { it.isFavorite }.mapTo(mutableSetOf()) { it.downloadId },
                        onFavorite = setLibraryFavorite, folders = catalogFolders, onSetFolder = setLibraryFolder,
                        onMoveToPrivate = { requestPrivateMove(listOf(it.id)) }, onOpenExternal = { openSavedFile(context, it) },
                        onCurrentItemChanged = { entry.savedStateHandle["gallery_current_id"] = it.id },
                    ) else MediaViewerScreen(
                        item = item,
                        onBack = { navController.popBackStack() },
                        onOpenExternal = { selected -> if (!selected.isPrivate) openSavedFile(context, selected) },
                        onShare = { selected -> if (!selected.isPrivate) shareSavedFile(context, selected) },
                        audioQueue = LibraryAudioQueue.resolve(
                            entry.savedStateHandle.get<List<String>>("grid_viewer_ids").orEmpty(), viewerRows, item.isPrivate,
                            fileStatuses),
                        photoQueue = photoQueue,
                        mediaQueue = com.domenota.medialoader.data.playback.GridMediaQueue.resolve(
                            entry.savedStateHandle.get<List<String>>("grid_viewer_ids").orEmpty(),
                            viewerRows, item.isPrivate, fileStatuses),
                        modernAudioLayout = !item.isPrivate,
                        onEqualizer = { navController.navigate("equalizer") },
                        audioMetadataRows = publicLibraryRows, audioBlockedIdentities = libraryPrivateKeys.orEmpty(),
                        audioFavoriteIds = libraryCatalogAssignments.filter { it.isFavorite }.mapTo(mutableSetOf()) { it.downloadId }.also { favorites ->
                            if (currentLibraryAudioId in favorites) libraryAudioState.currentId?.let(favorites::add)
                        },
                        onAudioFavorite = { selected -> fileActionScope.launch {
                            try { setLibraryFavorite(selected.id, libraryCatalogAssignments.none { it.downloadId == currentLibraryAudioId && it.isFavorite }) }
                            catch (e: CancellationException) { throw e }
                            catch (_: Exception) { Toast.makeText(context, "Не удалось изменить избранное", Toast.LENGTH_SHORT).show() }
                        } },
                        onAudioCurrentItemChanged = { entry.savedStateHandle["audio_current_id"] = it.id },
                        onEdit = { selected ->
                            val selectedId = libraryRefsByAlias[selected.id]?.key
                                ?: currentLibraryAudioId?.takeIf { selected.mediaType == MediaType.AUDIO && selected.id == libraryAudioState.currentId }
                                ?: selected.id
                            when (selected.mediaType) {
                                MediaType.VIDEO -> navController.navigate("video-editor/$selectedId")
                                MediaType.AUDIO -> navController.navigate("audio-editor/$selectedId")
                                MediaType.PHOTO -> navController.navigate("photo-editor/$selectedId")
                            }
                        },
                    ) }
                }
            }
            composable(VIDEO_EDITOR_ROUTE) { entry ->
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
            }
            composable(AUDIO_EDITOR_ROUTE) { entry ->
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
            }
            composable(PHOTO_EDITOR_ROUTE) { entry ->
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
            }
            composable(BULK_ROUTE) {
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
                )
            }
            composable(PREVIEW_ROUTE) {
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
                    onRetry = { viewModel.analyze(preview.url.ifBlank { pendingUrl }) },
                )
            }
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
                onRetry = { viewModel.analyze(preview.url.ifBlank { pendingUrl }) },
                asSheet = true,
            )
        }
    }
}
