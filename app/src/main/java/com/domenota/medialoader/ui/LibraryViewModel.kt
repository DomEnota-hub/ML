package com.domenota.medialoader.ui

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.data.library.*
import com.domenota.medialoader.data.catalog.visibility.*
import kotlinx.coroutines.flow.catch
import com.domenota.medialoader.data.MediaRepository
import com.domenota.medialoader.data.playback.PublicMediaIdentity
import com.domenota.medialoader.data.playback.AudioWaveformPrecompute
import com.domenota.medialoader.data.storage.HistoricalRecoverySignals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal enum class PhoneScanReason { REQUEST, MEDIASTORE_CHANGE, PRIVACY_CHANGE, ACCESS_CHANGED, RECOVERY }
internal enum class PostTransferCatalogStatus { IDLE, UPDATING, READY, INCOMPLETE }

/** Version updates during a running operation are not a second ENTER. */
internal fun enteringRecoveryGate(running: Boolean, previouslyRunning: Boolean): Boolean =
    running && !previouslyRunning

/** Only changed provider kinds require a physical rescan; an unrelated
 * MediaStore callback queued during the transfer is also honored. */
internal fun recoveryRefreshModes(
    kinds: Set<com.domenota.medialoader.data.storage.HistoricalMediaKind>,
    pending: Set<AppMode> = emptySet(),
): Set<AppMode> = buildSet {
    if (com.domenota.medialoader.data.storage.HistoricalMediaKind.VISUAL in kinds) add(AppMode.GALLERY)
    if (com.domenota.medialoader.data.storage.HistoricalMediaKind.AUDIO in kinds) add(AppMode.MUSIC)
    addAll(pending.filter { it == AppMode.GALLERY || it == AppMode.MUSIC })
}
internal data class PhoneLibraryState(
    val items: List<DownloadEntity> = emptyList(), val loading: Boolean = false,
    val loaded: Boolean = false, val message: String? = null, val revision: Long = 0,
    val identities: Map<String, Set<String>> = emptyMap(), val requiresValidation: Boolean = true,
    val fingerprints: Map<String, String> = emptyMap(),
    val accessEpoch: Long = 0,
    val complete: Boolean = false,
)
/** Transfer changes MediaStore locators, not the user's permission grant. Never
 * publish zero rows for a completed, authorized generation simply because
 * its background reconciliation began. Visibility is still filtered through
 * the *current* FolderVisibilityLibraryGuard on every index build.
 *
 * Do not retain partial/cold/permission-unvalidated snapshots. */
internal fun retainPhoneSnapshotAtTransferBoundary(
    previous: PhoneLibraryState, indexedRevision: Long, indexReady: Boolean,
): PhoneLibraryState =
    if (previous.loaded && previous.complete && !previous.loading && previous.message == null &&
        (!previous.requiresValidation || (indexReady && previous.revision == indexedRevision))) previous
    else PhoneLibraryState(revision = previous.revision + 1, accessEpoch = previous.accessEpoch)

/** If an unchanged recovery attempt interrupted a cold/partial phone scan,
 * its own ENTER must not strand the UI with an empty or incomplete catalogue.
 * A complete permission-validated generation is retained without a re-scan. */
internal fun restartScanAfterUnchangedRecovery(interrupted:Boolean,state:PhoneLibraryState):Boolean =
    interrupted && (!state.loaded || !state.complete || state.loading ||
        state.requiresValidation || state.message != null)

/** Equal complete snapshots do not invalidate downstream catalogs. Compare on a worker. */
internal fun samePhoneContent(previous: PhoneLibraryState, next: PhoneMediaSnapshot): Boolean =
    previous.loaded && previous.complete && previous.items == next.items &&
        previous.identities == next.identities && previous.fingerprints == next.fingerprints
internal data class LibraryIndex(val ready: Boolean = false, val refs: List<LibraryMediaRef> = emptyList(),
    val galleryRevision: Long = 0, val musicRevision: Long = 0, val appComplete: Boolean = false,
    val privacyKeys: Set<String>? = null,
    val appUris: Set<Pair<String, String?>> = emptySet(),
    val visibility: FolderVisibilityLibraryGuard? = null, val hiddenRefs: List<LibraryMediaRef> = emptyList(),
    val galleryAccessEpoch: Long = 0, val musicAccessEpoch: Long = 0) {
    // Index construction runs on IO. Derived fields are deliberately outside data-class
    // equality: equal inventory generations must still conflate in StateFlow.
    val display = LibraryDisplayInventory.build(refs, visibility?.external.orEmpty())
}
/** During a visibility-receipt change the canonical index is rebuilt on IO.
 * Its previous, already authenticated generation is safe to DISPLAY only after
 * immediate re-filtering with the new fail-closed guard. Without this bridge,
 * even unchanged 10k+ phone rows disappear until the slow index rebuild lands.
 * The result never authorizes a destructive action or admits a new locator.
 * Permission and private-access changes must not reuse this generation. */
internal fun guardedRetainedIndexRefs(
    index: LibraryIndex,
    visibility: FolderVisibilityLibraryGuard?,
    privacy: Set<String>?,
    gallery: PhoneLibraryState,
    music: PhoneLibraryState,
    allowUnaffectedKinds: Boolean = false,
): List<LibraryMediaRef>? {
    if (!index.ready || index.visibility == null || privacy == null ||
        index.privacyKeys != privacy || visibility == null ||
        visibility.policy.unknownPending) return null
    // Old index revisions are not allowed across an unvalidated READ_MEDIA_*
    // grant change, selected-only access or a private-media invalidation.
    val galleryPending = index.galleryAccessEpoch != gallery.accessEpoch ||
        (gallery.requiresValidation && index.galleryRevision != gallery.revision)
    val musicPending = index.musicAccessEpoch != music.accessEpoch ||
        (music.requiresValidation && index.musicRevision != music.revision)
    if (!allowUnaffectedKinds && (galleryPending || musicPending)) return null
    if (visibility == index.visibility && !galleryPending && !musicPending) return index.refs
    val denied = visibility.blockedKeys() + privacy
    return index.refs.filter { ref ->
        (ref.appDownloadId != null || !(if (ref.item.mediaType == com.domenota.medialoader.core.model.MediaType.AUDIO) musicPending else galleryPending)) &&
        ref.key !in denied && ref.aliases.none(denied::contains) &&
            !visibility.excluded(ref)
    }
}

/** Successful byte transfer precedes an independent, async MediaStore/index
 * reconciliation. Never report the second phase as complete from 46/46 alone. */
internal fun postTransferIndexAligned(
    index: LibraryIndex, gallery: PhoneLibraryState, music: PhoneLibraryState,
    guard: FolderVisibilityLibraryGuard?,
    affected: Set<AppMode> = setOf(AppMode.GALLERY,AppMode.MUSIC),
): Boolean {
    if (!index.ready || !index.appComplete || guard == null || index.visibility != guard) return false
    fun verified(phone: PhoneLibraryState, revision: Long, epoch: Long): Boolean =
        phone.loaded && phone.complete && !phone.loading && phone.message == null &&
            revision == phone.revision && epoch == phone.accessEpoch
    return (AppMode.GALLERY !in affected ||
        verified(gallery,index.galleryRevision,index.galleryAccessEpoch)) &&
        (AppMode.MUSIC !in affected ||
        verified(music,index.musicRevision,index.musicAccessEpoch))
}

/** Duplicate search requires the complete current visual generation, not a
 * browseable first page. Audio inventory is irrelevant to photo/video coverage. */
internal fun visualDuplicateCatalogComplete(index: LibraryIndex, gallery: PhoneLibraryState,
    accessEpoch: Long, currentAppUris: Set<Pair<String, String?>>?, scanInProgress:Boolean=false): Boolean =
    index.ready && index.appComplete && currentAppUris != null && index.appUris == currentAppUris &&
        gallery.loaded && gallery.complete && !gallery.loading && !scanInProgress &&
        gallery.accessEpoch == accessEpoch && index.galleryRevision == gallery.revision

internal data class CollectionHistorySnapshot(val rows: Map<String, DownloadEntity>, val appUris: Set<Pair<String, String?>>, val blocked: Set<String>,
    val completedUris: List<Pair<String, String?>>) {
    private val rowsByUri = rows.values.filter { !it.savedUri.isNullOrBlank() }.groupBy { it.savedUri }
    val publicPhotoUris = rows.values.asSequence().filter {
        it.mediaType == com.domenota.medialoader.core.model.MediaType.PHOTO && !it.isPrivate && !it.hidden &&
            it.state == com.domenota.medialoader.core.model.DownloadState.COMPLETED && it.id !in blocked
    }.mapNotNull { it.savedUri }.toSet()
    fun forMembers(members: List<com.domenota.medialoader.core.database.MediaCollectionMemberEntity>): Map<String, DownloadEntity> = buildMap {
        for (member in members) {
            for (key in MediaOrganizationPolicy.decode(member.identityJson)) {
                if (key.startsWith("app:")) rows[key.removePrefix("app:")]?.let { put(it.id,it) }
            }
            for (uri in CollectionAddressResolver.phoneAddresses(member)) rowsByUri[uri].orEmpty().forEach { put(it.id,it) }
        }
    }
}
private data class AppReferenceBatch(val refs: List<LibraryMediaRef>, val complete: Boolean)
internal data class AudioCatalogGuard(val privacy: Set<String>, val accessEpoch: Long,
    val visibility: FolderVisibilityLibraryGuard? = null)
internal data class AudioCatalogSnapshot(val guard: AudioCatalogGuard, val refs: List<LibraryMediaRef>)
internal data class AudioCatalogUpdate(val guard: AudioCatalogGuard?, val refs: List<LibraryMediaRef>?)
/** Incomplete routine updates never replace a validated audio generation. */
internal fun retainAudioCatalog(previous: AudioCatalogSnapshot?, update: AudioCatalogUpdate): AudioCatalogSnapshot? {
    val guard = update.guard ?: return null
    return update.refs?.let { AudioCatalogSnapshot(guard, it) } ?: previous?.takeIf { it.guard == guard }
}
private data class PhoneReferenceInput(
    val pictures: List<DownloadEntity>, val audio: List<DownloadEntity>, val app: List<DownloadEntity>,
    val pictureIdentities: Map<String, Set<String>>, val audioIdentities: Map<String, Set<String>>,
    val privacy: Set<String>, val pictureAccess: Long, val audioAccess: Long,
)
private data class AudioIndexInput(val app: AppReferenceBatch?, val phone: PhoneLibraryState,
    val privacy: Set<String>?, val recovering: Boolean, val accessEpoch: Long)
private data class LibraryIndexInput(val app: AppReferenceBatch?, val gallery: PhoneLibraryState,
    val music: PhoneLibraryState, val privateKeys: Set<String>?, val recovering: Boolean)

/** First-page and completed cold publications are bounded; per-page revision and
 * per-file visibility changes must never become full-materialization triggers. */
private fun PhoneLibraryState.bootstrapPhase(): Pair<Long, Int> = accessEpoch to
    if (!loaded || message != null) 0 else if (complete) 2 else 1
private fun bootstrapKey(app: AppReferenceBatch?, gallery: PhoneLibraryState, music: PhoneLibraryState): Any? {
    val appPhase = if (app?.complete == true) 2 else if (!app?.refs.isNullOrEmpty()) 1 else 0
    return if (!gallery.loaded && !music.loaded && appPhase == 0) null
        else Triple(appPhase, gallery.bootstrapPhase(), music.bootstrapPhase())
}

internal class PhoneTransferBootstrapAttempts {
    private val epochs = mutableMapOf<AppMode, Long>()
    fun clear() = epochs.clear()
    fun reserve(mode: AppMode, epoch: Long): Boolean {
        if (epochs[mode] == epoch) return false
        epochs[mode] = epoch
        return true
    }
}

/** Read-only cold scans are permitted only for an explicit request and an exact
 * known folder journal. A provider callback is never a bootstrap request. */
internal fun canBootstrapPhoneDuringTransfer(reason: PhoneScanReason, recovering: Boolean,
    guard: FolderVisibilityLibraryGuard?, state: PhoneLibraryState,
    changedAccess: Boolean, invalidated: Boolean, selectedOnly: Boolean): Boolean =
    reason == PhoneScanReason.REQUEST && recovering && guard != null &&
        !guard.policy.unknownPending && guard.policy.pendingFolder != null &&
        (!state.loaded || !state.complete || changedAccess || invalidated || selectedOnly)

/** A progressive scan has already checked the same permission signature and
 * private keys before each cumulative page. The first page of a new epoch
 * still needs its canonical index. Subsequent pages must not revoke that
 * epoch's already admitted index just because their revision advanced.
 * Selected-only grants have no progressive publication and never use this path. */
internal fun mayRetainProgressivePhoneIndex(
    previous: PhoneLibraryState, accessEpoch: Long, selectedOnly: Boolean,
): Boolean = !selectedOnly && previous.loaded && previous.loading &&
    !previous.complete && previous.message == null && previous.accessEpoch == accessEpoch

/** A missing exact phone visual is not a negative result until the relevant
 * scan and its canonical index have caught up. A failed scan is distinct
 * from a completed scan that proved absence. Never admit a stale locator. */
internal fun phoneVisualResolutionPending(id: String?, gallery: PhoneLibraryState,
    index: LibraryIndex, rowsReady: Boolean, aliasesReady: Boolean, scanInProgress: Boolean = false): Boolean {
    if (id?.startsWith("phone:") != true || id.startsWith("phone:audio:")) return false
    return scanInProgress || !gallery.loaded || gallery.loading ||
        (!gallery.complete && gallery.message == null) || !index.ready ||
        index.galleryAccessEpoch != gallery.accessEpoch ||
        index.galleryRevision != gallery.revision || !rowsReady || !aliasesReady
}

/** Retained editor references are not validated public display data until this snapshot lands. */
internal fun phoneValidationPending(index: LibraryIndex, gallery: PhoneLibraryState, music: PhoneLibraryState): Boolean =
    index.galleryAccessEpoch != gallery.accessEpoch || index.musicAccessEpoch != music.accessEpoch ||
        (gallery.requiresValidation && ((gallery.loading && !gallery.loaded) || gallery.revision != index.galleryRevision)) ||
        (music.requiresValidation && ((music.loading && !music.loaded) || music.revision != index.musicRevision))
/**
 * For a visible library home screen, only the active media kind can block it.
 * An audio-only refresh must never cover a validated photo grid (or vice versa).
 * Full shared validation is retained for routes which can display both kinds.
 */
internal fun visibleLibraryValidationPending(
    mode: AppMode, index: LibraryIndex, gallery: PhoneLibraryState, music: PhoneLibraryState,
): Boolean {
    if (mode == AppMode.DOWNLOADER) return false
    if (!index.ready) return true
    val state = if (mode == AppMode.GALLERY) gallery else music
    val indexedRevision = if (mode == AppMode.GALLERY) index.galleryRevision else index.musicRevision
    val indexedEpoch = if (mode == AppMode.GALLERY) index.galleryAccessEpoch else index.musicAccessEpoch
    return state.accessEpoch != indexedEpoch || (state.requiresValidation &&
        ((state.loading && !state.loaded) || state.revision != indexedRevision))
}

/**
 * A completed, already validated library can be refreshed entirely out of view.
 * Permission changes, new private-media exclusions, or Android's selected-only
 * media access still require revalidation before old entries may be displayed.
 */
internal fun canRefreshLibrarySilently(
    state: PhoneLibraryState, indexed: LibraryIndex, indexedRevision: Long,
    accessChanged: Boolean, selectedOnlyAccess: Boolean, privacyInvalidated: Boolean,
): Boolean = indexed.ready && state.loaded &&
    (!state.requiresValidation || state.revision == indexedRevision) &&
    !accessChanged && !selectedOnlyAccess && !privacyInvalidated

/** Navigation is not a provider-change notification. An already complete
 * full-access catalog remains usable, but cold, failed or changed-access
 * states must go through the existing validation path. */
internal fun needsPhoneRefreshOnNavigation(state:PhoneLibraryState,accessChanged:Boolean,selectedOnly:Boolean):Boolean =
    accessChanged||selectedOnly||!state.loaded||!state.complete||state.message!=null

/** Map MediaStore notifications to the affected library without guessing for unknown URIs. */
internal fun phoneModesForMediaChange(uri: String?): Set<AppMode> {
    val both = setOf(AppMode.GALLERY, AppMode.MUSIC)
    if (uri.isNullOrBlank()) return both
    val address = runCatching { java.net.URI(uri) }.getOrNull() ?: return both
    if(address.scheme!="content"||address.authority!="media")return both
    val segments=address.path.orEmpty().trim('/').split('/')
    if(segments.size !in 3..4||segments[0].isBlank()||segments[2]!="media"||
        (segments.size==4&&segments[3].toLongOrNull()?.let{it>=0}!=true))return both
    return when(segments[1]){
        "images","video" -> setOf(AppMode.GALLERY)
        "audio" -> setOf(AppMode.MUSIC)
        else -> both
    }
}

/** A busy gallery scan must not postpone audio reconciliation (and vice versa).
 * Busy modes remain pending; consuming one batch never clears another mode. */
internal fun readyPhoneMediaChanges(pending:Set<AppMode>,busy:Set<AppMode>):Set<AppMode> =
    pending.filterTo(linkedSetOf()){it!=AppMode.DOWNLOADER&&it !in busy}

/**
 * A ContentObserver callback can be redundant (e.g. provider/thumbnail metadata).
 * Only skip when the MediaStore generation AND version are unchanged, permission
 * access is identical, and the library isn't using Android's selected-only access.
 * Selected-only grants may change independently of the MediaStore generation.
 */
internal fun maySkipUnchangedMediaNotification(
    before: PhoneStoreStamp?, after: PhoneStoreStamp,
    permissionsUnchanged: Boolean, selectedOnlyAccess: Boolean,
): Boolean = permissionsUnchanged && !selectedOnlyAccess &&
    before?.versions != null && before.versions == after.versions &&
    before.generation != null && before.generation == after.generation

internal fun hasPhoneLibraryAccess(context: Context, mode: AppMode): Boolean =
    PhoneMediaLibrary.permissions(mode).any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

@OptIn(ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)
internal class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val equalizerRepository = com.domenota.medialoader.data.playback.EqualizerSettingsRepository.get(application)
    private val equalizerDraft = MutableStateFlow<com.domenota.medialoader.data.playback.EqualizerProfileState?>(null)
    val equalizerProfiles = equalizerDraft.asStateFlow()
    private val equalizerFailure = MutableStateFlow<String?>(null)
    val equalizerError = equalizerFailure.asStateFlow()
    // Conflate slider edits only for the SAME target. A quick track change must not
    // overwrite an unsaved write for another track or for the global profile.
    private val equalizerPending = linkedMapOf<com.domenota.medialoader.data.playback.EqualizerTrack?,
        com.domenota.medialoader.data.playback.EqualizerSettings?>()
    private val equalizerWrites = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    init {
        viewModelScope.launch {
            equalizerRepository.awaitLoaded()
            equalizerDraft.value = equalizerRepository.state.value
            for (signal in equalizerWrites) {
                while (equalizerPending.isNotEmpty()) {
                    val entry = equalizerPending.entries.first()
                    val track = entry.key
                    val settings = entry.value
                    equalizerPending.remove(track)
                    try {
                        if (track == null) equalizerRepository.setSettings(requireNotNull(settings))
                        else equalizerRepository.setTrackSettings(track, settings)
                        equalizerFailure.value = null
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { equalizerFailure.value = "Не удалось сохранить настройки эквалайзера" }
                    // Retain optimistic edits made during the disk write. All queue
                    // operations are confined to the ViewModel main dispatcher.
                    var snapshot = equalizerRepository.state.value
                    equalizerPending.forEach { (pendingTrack, pendingSettings) ->
                        snapshot = runCatching { snapshot.changed(pendingTrack, pendingSettings) }.getOrDefault(snapshot)
                    }
                    equalizerDraft.value = snapshot
                }
            }
        }
    }
    fun setEqualizer(track: com.domenota.medialoader.data.playback.EqualizerTrack?,
        settings: com.domenota.medialoader.data.playback.EqualizerSettings?) {
        val current = equalizerDraft.value ?: return
        val next = runCatching { current.changed(track, settings) }.getOrElse {
            equalizerFailure.value = "Не удалось определить профиль трека"
            return
        }
        equalizerDraft.value = next
        equalizerPending[track] = settings
        equalizerWrites.trySend(Unit)
    }
    val playlistRepository = PlaylistRepository(application)
    val externalCatalog = ExternalMediaCatalogRepository(application)
    val audioPreview = com.domenota.medialoader.data.playback.LibraryAudioPreview(application, ::authorizeFolderAudioRead)
    private fun folderAudioReadAccess(): com.domenota.medialoader.data.playback.FolderAudioReadAccess {
        val context = getApplication<Application>()
        val privacy = privateKeys.value
        val visibility = visibilityGuard.value
        val refs = guardedRetainedIndexRefs(index.value, visibility, privacy,
            gallery.value, music.value, allowUnaffectedKinds = true).orEmpty()
        return com.domenota.medialoader.data.playback.FolderAudioReadAccess(visibility, privacy,
            DuplicatePrivacyGuard.generation(context), musicAccessEpoch.value,
            PhoneMediaLibrary.permissions(AppMode.MUSIC).map {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }, HistoricalRecoverySignals.version.value, refs)
    }
    internal suspend fun togglePublicAudio() {
        val refs = if (HistoricalRecoverySignals.isRunning.value || visibilityGuard.value?.policy?.pendingFolder != null) {
            val allowed = folderAudioReadAccess().publicRefs.associateBy { it.key }
            audioPreview.state.value.queue.map { item ->
                val uri = item.mediaMetadata.extras?.getString(com.domenota.medialoader.data.playback.AudioPlaybackMetadata.PUBLIC_URI)
                    ?: item.localConfiguration?.uri?.toString()
                allowed[item.mediaId]?.takeIf { it.item.savedUri == uri } ?: error("Трек недоступен")
            }
        } else emptyList()
        audioPreview.toggleCurrent(refs)
    }
    private suspend fun authorizeFolderAudioRead(refs: List<LibraryMediaRef>, selectedId: String?):
        com.domenota.medialoader.data.playback.AudioQueueReadPermit? {
        if (!HistoricalRecoverySignals.isRunning.value && visibilityGuard.value?.policy?.pendingFolder == null) {
            HistoricalRecoverySignals.requireIdle()
            return null
        }
        val access = folderAudioReadAccess()
        val permit = com.domenota.medialoader.data.playback.AudioQueueReadPermit.acquire(access, refs, ::folderAudioReadAccess)
        val selected = refs.firstOrNull { it.key == selectedId || (selectedId != null && selectedId in it.aliases) }
            ?: refs.firstOrNull() ?: error("Нет доступных треков")
        // Fresh physical proof only for the selected file. The rest of the queue
        // must belong to the authenticated current model and exact known scope.
        val identity = PublicMediaIdentity.capture(getApplication(), requireNotNull(selected.item.savedUri))
        check(selected.aliases.any { it.startsWith("file-stat:") && it in identity }) { "Файл изменился" }
        permit.requireRefsCurrent(refs)
        return permit
    }
    private val phone = PhoneMediaLibrary(application)
    private val phoneCache = PhoneLibraryCache(application)
    private val appAudioIdentityCache=AppAudioIdentityCache(application)
    internal suspend fun addressCollectionPhone(uri: String): AddressedFile {
        val guard = visibilityGuard.value ?: return AddressedFile(null,com.domenota.medialoader.data.storage.SavedFileStatus.UNCHECKED)
        val result = phone.lookupCollectionAddress(uri)
        return if (visibilityGuard.value == guard && result.ref?.let { !guard.excluded(it) } == true) result.copy(ref=result.ref?.let(guard::normalizePublic))
            else AddressedFile(null,com.domenota.medialoader.data.storage.SavedFileStatus.UNCHECKED)
    }
    internal suspend fun addressCollectionApp(row: DownloadEntity,
        explicitFolder: String? = null,
        checkStatus: (String?) -> com.domenota.medialoader.data.storage.SavedFileStatus): AddressedFile = withContext(Dispatchers.IO) {
        val guard = visibilityGuard.value ?: return@withContext AddressedFile(null,com.domenota.medialoader.data.storage.SavedFileStatus.UNCHECKED)
        val current = mediaRepository.downloadById(row.id)
        val uri = current?.savedUri
        if (current == null || current.hidden || current.isPrivate || current.state != com.domenota.medialoader.core.model.DownloadState.COMPLETED ||
            uri.isNullOrBlank() || uri != row.savedUri) return@withContext AddressedFile(null, com.domenota.medialoader.data.storage.SavedFileStatus.UNCHECKED)
        val status = checkStatus(uri)
        if (status != com.domenota.medialoader.data.storage.SavedFileStatus.PRESENT) return@withContext AddressedFile(null, status)
        val keys = PublicMediaIdentity.capture(getApplication(), uri)
        val ref = LibraryMediaRef(current.id, keys + current.id, current, current.id)
        val permitted = if (guard.excluded(ref)) explicitFolder != null && guard.explicitFolder(ref) == explicitFolder && verifiedHidden(ref,guard) != null else true
        if (visibilityGuard.value != guard || !permitted) AddressedFile(null,com.domenota.medialoader.data.storage.SavedFileStatus.UNCHECKED)
        else AddressedFile(ref, status)
    }

    /** A remembered logical ID/URI is not a live identity after a MediaStore row is
     * removed and recreated. Recheck managed files immediately before opening. */
    internal suspend fun verifyVisibilityOpen(row: DownloadEntity, folder: String?): Boolean = withContext(Dispatchers.IO) {
        val guard=visibilityGuard.value ?: return@withContext false
        if(row.isPrivate) return@withContext true
        val uri=row.savedUri ?: return@withContext false
        if(guard.policy.unknownPending) return@withContext false
        val managed=guard.policy.snapshot.receipts.any { it.downloadId==row.id || it.destinationUri==uri || it.sourceUri==uri } ||
            guard.external.any { it.visibilityManaged && (it.ref.key==row.id || it.ref.item.savedUri==uri) }
        if(!managed) return@withContext guard.canOpen(row,folder,emptyList())
        val app=mediaRepository.downloadById(row.id)
        if(app!=null && (app.savedUri!=uri || app.hidden || app.isPrivate || app.sizeBytes!=row.sizeBytes)) return@withContext false
        val keys=PublicMediaIdentity.capture(getApplication(),uri)
        val ref=LibraryMediaRef(row.id,keys+row.id,row,app?.id)
        val allowed=if(guard.excluded(ref)) folder!=null && guard.explicitFolder(ref)==folder && verifiedHidden(ref,guard)!=null
            else guard.normalizePublic(ref).key==row.id && !row.hidden
        allowed && visibilityGuard.value==guard
    }

    private val galleryState = MutableStateFlow(PhoneLibraryState())
    private val musicState = MutableStateFlow(PhoneLibraryState())
    val gallery = galleryState.asStateFlow()
    // Admission-only status: never invalidate the general library index when an
    // unchanged silent MediaStore scan starts or ends.
    private val galleryScanInProgressState=MutableStateFlow(false)
    val galleryScanInProgress=galleryScanInProgressState.asStateFlow()
    private var galleryScanToken:Any?=null
    val music = musicState.asStateFlow()
    private val galleryAccessEpochState = MutableStateFlow(0L)
    private val musicAccessEpochState = MutableStateFlow(0L)
    val galleryAccessEpoch = galleryAccessEpochState.asStateFlow()
    val musicAccessEpoch = musicAccessEpochState.asStateFlow()
    private val jobs = mutableMapOf<AppMode, Job>()
    private var phoneScanGeneration = 0L
    private val transferBootstrapEpochs = PhoneTransferBootstrapAttempts()
    private val mediaRepository = MediaRepository.get(application)
    private val folderVisibility = mediaRepository.folderVisibility
    // Await the durable settings generation. Corrupt metadata must not publish a
    // speculative public library; an interrupted batch publishes exact members.
    val visibilityGuard = combine(folderVisibility.snapshot, folderVisibility.status,
        mediaRepository.catalogAssignments, externalCatalog.entries,
        mediaRepository.nomediaFolders.snapshot) { snapshot, progress, assignments, external, nomedia ->
        FolderVisibilityLibraryGuard(FolderVisibilityReadPolicy(snapshot,progress.folderId,
            (progress.pending && progress.folderId == null) || nomedia.unknown,progress.members,
            nomedia.hiddenFolderIds),assignments,external)
    }.combine(mediaRepository.selectiveArchive.privacy) { ordinary, archive ->
        ordinary.copy(policy=ordinary.policy.copy(
            unknownPending=ordinary.policy.unknownPending || !archive.verified,
            archiveBlockedIds=archive.blockedIds,
            archiveBlockedUris=archive.blockedUris,
            archiveBlockedAliases=archive.blockedAliases,
        ))
    }.catch { emit(FolderVisibilityLibraryGuard(FolderVisibilityReadPolicy(FolderVisibilitySnapshot(),unknownPending=true),emptyList(),emptyList())) }
        .flowOn(Dispatchers.Default).stateIn(viewModelScope,SharingStarted.Eagerly,null)
    init {
        viewModelScope.launch(Dispatchers.IO) { folderVisibility.pending() }
        // The public index cannot be published before all durable Archive
        // checkpoints and their member receipts are validated after restart.
        viewModelScope.launch(Dispatchers.IO) { mediaRepository.selectiveArchive.refreshPrivacy() }
    }

    private suspend fun verifiedHidden(ref: LibraryMediaRef, guard: FolderVisibilityLibraryGuard): LibraryMediaRef? {
        val record = guard.explicitReceipt(ref) ?: return null
        if (!FolderVisibilityStorageAccess.granted() || ref.item.isPrivate || ref.item.hidden ||
            ref.item.state != com.domenota.medialoader.core.model.DownloadState.COMPLETED ||
            ref.item.sizeBytes != record.fingerprint.bytes) return null
        val uri = android.net.Uri.parse(record.destinationUri ?: return null)
        if (uri.scheme != "file" || !uri.authority.isNullOrEmpty() || uri.query != null || uri.fragment != null) return null
        val file = java.io.File(uri.path ?: return null)
        if (!runCatching { FolderVisibilityStorageAccess.store().matchesReceipt(file,record) }.getOrDefault(false)) return null
        // No stale aliases from the old MediaStore row enter the explicit queue.
        return ref.copy(aliases=PublicMediaIdentity.capture(getApplication(),uri.toString()) + ref.key,
            deviceAlbumId=null,deviceAlbumName=null)
    }

    // null means privacy validation has not finished yet: never publish a speculative public list.
    val privateKeys = mediaRepository.privateMediaIds
        .mapLatest<Set<String>, Set<String>?> { it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val identityCache = java.util.concurrent.ConcurrentHashMap<String, Set<String>>()
    // Audio and visual identity work share the old eight-provider limit.
    private val identityGate = Semaphore(8)
    // Stable public/history metadata input. Download byte progress cannot restart
    // collection resolution, and large history maps are assembled off the main thread.
    private val collectionHistoryRows = mediaRepository.downloads.map { rows ->
        rows.filter { it.state == com.domenota.medialoader.core.model.DownloadState.COMPLETED || it.hidden || it.isPrivate }
    }.distinctUntilChanged().flowOn(Dispatchers.Default).shareIn(viewModelScope, SharingStarted.Eagerly, replay = 1)
    val collectionHistory = combine(collectionHistoryRows,visibilityGuard) { relevant, visibility ->
        CollectionHistorySnapshot(relevant.associateBy { it.id }, relevant.asSequence().filter {
            it.state == com.domenota.medialoader.core.model.DownloadState.COMPLETED && !it.hidden && !it.isPrivate && !it.savedUri.isNullOrBlank() && visibility?.appExcluded(it) == false
        }.map { it.id to it.savedUri }.toSet(), buildSet {
            addAll(visibility?.blockedKeys().orEmpty())
            relevant.filter { it.isPrivate || it.hidden || visibility?.appExcluded(it) != false }.forEach { row ->
                add(row.id); row.savedUri?.let { addAll(PublicMediaIdentity.uriKeys(it)) }
            }
        }, relevant.filter { it.state == com.domenota.medialoader.core.model.DownloadState.COMPLETED && !it.hidden && !it.isPrivate && visibility?.appExcluded(it) == false }
            .map { it.id to it.savedUri })
    }.distinctUntilChanged().flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    // Audio-only history prevents unrelated photo/video completion from rebuilding
    // listening projections. Current global privacy is still a separate guard.
    val listeningHistoryRows = collectionHistoryRows.map { rows ->
        rows.filter { it.mediaType == com.domenota.medialoader.core.model.MediaType.AUDIO }.associateBy { it.id }
    }.distinctUntilChanged().flowOn(Dispatchers.Default).stateIn(viewModelScope,SharingStarted.Eagerly,null as Map<String,DownloadEntity>?)
    // Active download progress is not library content. Do not cancel/restart identity indexing
    // on every byte/progress update while a gallery scan is in flight.
    private val completedDownloads = collectionHistoryRows.map { rows ->
        rows.filter { it.state == com.domenota.medialoader.core.model.DownloadState.COMPLETED &&
            !it.hidden && !it.isPrivate && !it.savedUri.isNullOrBlank() }
    }.distinctUntilChanged().flowOn(Dispatchers.Default).shareIn(viewModelScope, SharingStarted.Eagerly, replay = 1)
    init {
        // Only completed public audio may enter the silent index. Active download progress
        // does not continually restart background waveform work.
        viewModelScope.launch {
            completedDownloads.map { rows ->
                rows.filter { it.mediaType == com.domenota.medialoader.core.model.MediaType.AUDIO }
                    .map { "${it.savedUri}:${it.createdAtEpochMillis}" }
            }.distinctUntilChanged().collect {
                AudioWaveformPrecompute.request(getApplication())
            }
        }
    }
    private suspend fun references(rows: List<DownloadEntity>, isApp: Boolean,
        scannedIdentities: Map<String, Set<String>> = emptyMap(), freshTypedAudio:Boolean=false): List<LibraryMediaRef> = withContext(Dispatchers.IO) {
        suspend fun reference(row: DownloadEntity): LibraryMediaRef {
            coroutineContext.ensureActive()
            val uri = row.savedUri
            val fresh=freshTypedAudio && uri!=null && CollectionAddressResolver.isTypedPhoneAddress(uri) && uri.contains("/audio/media/")
            val identities = if (uri == null) emptySet() else scannedIdentities[uri] ?: (if(fresh)null else identityCache[uri])
                ?: identityGate.withPermit {
                    (if(fresh)null else identityCache[uri]) ?: PublicMediaIdentity.capture(getApplication(), uri).also { identityCache[uri] = it }
                }
            return LibraryMediaRef(row.id, identities + row.id, row, row.id.takeIf { isApp },
                deviceAlbumId = if (isApp) null else row.bulkMediaKey ?: row.groupId,
                deviceAlbumName = if (isApp) null else row.groupId)
        }
        if (isApp) rows.chunked(8).flatMap { batch -> coroutineScope {
            batch.map { row -> async { reference(row) } }.awaitAll()
        } } else rows.map { reference(it) }
    }
    // Partition the existing app scan, rather than adding another full scan. Each row is
    // captured once, and a large audio import cannot cancel the visual pass (or vice versa).
    private fun referenceBatches(rowsFlow: Flow<List<DownloadEntity>>,audio:Boolean=false): kotlinx.coroutines.flow.StateFlow<AppReferenceBatch?> {
        val publication = AppReferencePublicationPolicy()
        var confirmed = false
        var coldTransferRows: List<DownloadEntity>? = null
        return combine(rowsFlow, HistoricalRecoverySignals.isRunning,
        HistoricalRecoverySignals.version, visibilityGuard) { rows, running, version, visibility ->
            val admitted = when {
                !running -> rows.also { coldTransferRows = null }
                confirmed -> null
                visibility == null || visibility.policy.unknownPending || visibility.policy.pendingFolder == null -> null
                else -> coldTransferRows ?: rows.filterNot(visibility::appExcluded).also { coldTransferRows = it }
            }
            admitted to (version > 0L)
        }.distinctUntilChanged()
        .transformLatest<Pair<List<DownloadEntity>?, Boolean>, AppReferenceBatch?> { (rows, afterTransfer) ->
        if (rows == null) { emit(null); return@transformLatest }
        // Phone revisions must not restart this work. Keep cold first-page progress,
        // but do not feed every warm identity batch into a full catalog rebuild.
        val resolved = mutableListOf<LibraryMediaRef>()
        // The audio metadata projection can survive a process restart without
        // reopening every ML download. Only exact, fully authorized MediaStore
        // locators with the same version/generation are eligible.
        val cachePrivacy=if(audio&&rows.isNotEmpty())privateKeys.filterNotNull().first() else null
        val privacyGeneration=DuplicatePrivacyGuard.generation(getApplication())
        fun fullAudioAccess()=PhoneMediaLibrary.canReadMissingMetadata(com.domenota.medialoader.core.model.MediaType.AUDIO,android.os.Build.VERSION.SDK_INT){
            ContextCompat.checkSelfPermission(getApplication(),it)==PackageManager.PERMISSION_GRANTED
        }
        val cacheTag=cachePrivacy?.takeIf{fullAudioAccess()}?.let{PhoneLibraryCache.privacyTag(it+"privacy-epoch:$privacyGeneration")}
        val before=if(cacheTag!=null)CollectionLookupBudget.run(1500){phone.storeStamp()} else null
        val cached=if(before!=null && cacheTag!=null && cachePrivacy!=null)withContext(Dispatchers.IO){
            appAudioIdentityCache.read(rows,before,cacheTag,cachePrivacy)
        } else emptyMap()
        val reusable=cachePrivacy==privateKeys.value&&privacyGeneration==DuplicatePrivacyGuard.generation(getApplication())&&fullAudioAccess()
        val identities=if(reusable)cached else emptyMap()
        if(identities.isNotEmpty())com.domenota.medialoader.core.logging.LibraryWorkCounters.increment(com.domenota.medialoader.core.logging.LibraryWork.APP_AUDIO_IDENTITY_CACHE_HIT)
        if (rows.isEmpty()) {
            emit(AppReferenceBatch(emptyList(), true))
            publication.completed()
            confirmed = true
        }
        var publishedAt = android.os.SystemClock.elapsedRealtime()
        for (batch in rows.chunked(16)) {
            resolved += references(batch, true, identities,freshTypedAudio=audio&&before?.generation!=null)
            val now = android.os.SystemClock.elapsedRealtime()
            if (publication.shouldPublish(resolved.size, rows.size, resolved.size == batch.size, now - publishedAt)) {
                val complete = resolved.size == rows.size
                // After a move, build new aliases atomically: a partial rebound
                // app batch must not replace 747 already-confirmed identities.
                if (!afterTransfer || complete) emit(AppReferenceBatch(resolved.toList(), complete))
                if (complete) { publication.completed(); confirmed = true }
                publishedAt = now
            }
        }
        if(cacheTag!=null&&cachePrivacy!=null&&before?.generation!=null&&cachePrivacy==privateKeys.value&&
            privacyGeneration==DuplicatePrivacyGuard.generation(getApplication())&&fullAudioAccess()) {
            val after=CollectionLookupBudget.run(1500){phone.storeStamp()}
            if(before==after)try {withContext(Dispatchers.IO){appAudioIdentityCache.write(resolved,before,cacheTag,cachePrivacy){cachePrivacy==privateKeys.value&&privacyGeneration==DuplicatePrivacyGuard.generation(getApplication())&&fullAudioAccess()&&!HistoricalRecoverySignals.isRunning.value}}}
            catch(e:CancellationException){throw e}catch(_:Exception){/* derived snapshot can be rebuilt */}
        }
        com.domenota.medialoader.core.logging.AppLog.i("Library", "App identity index ready · count=${resolved.size}")
    }.runningFold<AppReferenceBatch?, AppReferenceBatch?>(null) { previous, update ->
        // The recovery signal temporarily suspends reference writes. Keep the
        // last *complete* snapshot and re-check its privacy/visibility under
        // the live guards; never retain an unvalidated partial batch.
        update ?: previous?.takeIf { it.complete }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    }
    private val appAudioReferences = referenceBatches(completedDownloads.map { rows ->
        rows.filter { it.mediaType == com.domenota.medialoader.core.model.MediaType.AUDIO }
    }.distinctUntilChanged().flowOn(Dispatchers.Default),audio=true)
    private val appVisualReferences = referenceBatches(completedDownloads.map { rows ->
        rows.filter { it.mediaType != com.domenota.medialoader.core.model.MediaType.AUDIO }
    }.distinctUntilChanged().flowOn(Dispatchers.Default))
    private val appReferences = combine(appAudioReferences, appVisualReferences) { audio, visual ->
        if (audio == null && visual == null) null else AppReferenceBatch(audio?.refs.orEmpty() + visual?.refs.orEmpty(),
            audio?.complete == true && visual?.complete == true)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // Receipt/progress changes need fresh visibility checks, not a second allocation of
    // every unchanged phone row and identity set. Cache only pre-visibility references;
    // both callers still filter/normalize under their exact current guard below.
    private val indexPhoneReferences = LatestProjectionCache<PhoneReferenceInput, List<LibraryMediaRef>>()
    private val audioPhoneReferences = LatestProjectionCache<PhoneReferenceInput, List<LibraryMediaRef>>()
    private suspend fun phoneReferences(cache: LatestProjectionCache<PhoneReferenceInput, List<LibraryMediaRef>>,
        pictures: PhoneLibraryState, audio: PhoneLibraryState, app: List<LibraryMediaRef>, privacy: Set<String>): List<LibraryMediaRef> {
        val key = PhoneReferenceInput(pictures.items, audio.items, app.map { it.item }, pictures.identities,
            audio.identities, privacy, pictures.accessEpoch, audio.accessEpoch)
        return cache.get(key) {
            val rows = PhoneLibraryChronology.reconcile(pictures.items + audio.items, key.app)
            references(rows, false, pictures.identities + audio.identities)
        }
    }

    // Independent complete audio projection. No photo/video readiness or collection
    // readiness enters this flow. Retention is invalidated by real access/privacy changes.
    val audioCatalog = combine(appAudioReferences, music, privateKeys, HistoricalRecoverySignals.isRunning,
        musicAccessEpoch) { app, audio, private, recovering, epoch -> AudioIndexInput(app, audio, private, recovering, epoch) }
        .combine(visibilityGuard) { input, visibility -> input to visibility }
        .map { (input, visibility) ->
            LibraryMaterializationRequest(input to visibility,
                input.privacy?.let { it to input.accessEpoch }, input.recovering,
                visibility?.policy?.pendingFolder != null,
                input.privacy != null && visibility != null && !visibility.policy.unknownPending,
                input.phone.takeIf { it.loaded }?.bootstrapPhase())
        }.materializeLibraryReadModel(AudioCatalogUpdate(null, null)) { (input, visibility) ->
            val (app, audio, private, _, epoch) = input
            val guard = private?.takeUnless { visibility == null || visibility.policy.unknownPending }?.let { AudioCatalogGuard(it, epoch, visibility) }
            val refs = if (guard != null && app?.complete == true && audio.loaded && audio.complete && !audio.loading && audio.accessEpoch == epoch) withContext(Dispatchers.IO) {
                val phoneRefs = phoneReferences(audioPhoneReferences, PhoneLibraryState(), audio, app.refs, guard.privacy)
                UnifiedMediaLibrary.merge(app.refs.filterNot { visibility!!.excluded(it) },
                    phoneRefs.filterNot { visibility!!.excluded(it) }.map { visibility!!.normalizePublic(it) }, guard.privacy).also {
                    com.domenota.medialoader.core.logging.AppLog.i("Library", "Audio catalog ready · count=${it.size}")
                }
            } else null
            AudioCatalogUpdate(guard, refs)
        }.runningFold<AudioCatalogUpdate, AudioCatalogSnapshot?>(null, ::retainAudioCatalog)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 0), null)

    val index = combine(appReferences, gallery, music, privateKeys,
        HistoricalRecoverySignals.isRunning) { app, pictures, audio, private, recovering ->
        LibraryIndexInput(app, pictures, audio, private, recovering)
    }.combine(visibilityGuard) { input, visibility -> input to visibility }
    .map { (input, visibility) ->
        LibraryMaterializationRequest(input to visibility,
            // The raw mixed index is never an access certificate. Current
            // per-kind phone revisions/epochs gate its display consumers; an
            // audio revoke must not discard unrelated authenticated photos.
            input.privateKeys,
            input.recovering, visibility?.policy?.pendingFolder != null,
            input.privateKeys != null && visibility != null && !visibility.policy.unknownPending,
            bootstrapKey(input.app, input.gallery, input.music))
    }.materializeLibraryReadModel(LibraryIndex()) { (input, visibility) ->
        val (app, pictures, audio, private, _) = input
        if (private == null || visibility == null || visibility.policy.unknownPending) LibraryIndex() else withContext(Dispatchers.IO) {
            // MediaStore's DATE_ADDED changes after a copied legacy download.
            // The matching app history still has the pre-migration timestamp.
            // Reconcile only identical URI/type pairs, never by name or size.
            com.domenota.medialoader.core.logging.LibraryWorkCounters.increment(
                com.domenota.medialoader.core.logging.LibraryWork.LIBRARY_INDEX_BUILD)
            val phoneRefs = phoneReferences(indexPhoneReferences, pictures, audio, app?.refs.orEmpty(), private)
            val merged = UnifiedMediaLibrary.merge(app?.refs.orEmpty().filterNot(visibility::excluded),
                phoneRefs.filterNot(visibility::excluded).map(visibility::normalizePublic), private)
            val hidden = (app?.refs.orEmpty() + visibility.external.map { it.ref }.filter { it.appDownloadId == null })
                .mapNotNull { verifiedHidden(it,visibility) }.filterNot { ref ->
                    ref.key in private || ref.aliases.any(private::contains)
                }.distinctBy { it.key }
            val complete = app?.complete == true
            LibraryIndex(true, merged, pictures.revision, audio.revision, complete, private,
                if (complete) app?.refs.orEmpty().filterNot(visibility::excluded).mapTo(mutableSetOf()) { it.key to it.item.savedUri } else emptySet(),
                visibility,hidden,pictures.accessEpoch,audio.accessEpoch)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LibraryIndex())

    init {
        // Persistence is downstream maintenance, never a prerequisite for showing known media.
        viewModelScope.launch(Dispatchers.IO) {
            combine(index, HistoricalRecoverySignals.isRunning) { current, recovering ->
                current.refs.takeIf { current.ready && current.appComplete && !recovering }
            }.distinctUntilChanged().collectLatest { refs ->
                if (refs == null) return@collectLatest
                kotlinx.coroutines.delay(500L)
                if (HistoricalRecoverySignals.hasPendingJournal(getApplication<Application>())) return@collectLatest
        try { playlistRepository.reconcileAliases(refs.flatMap { ref ->
            (ref.aliases + ref.key).map { it to ref.key }
        }.toMap()) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { com.domenota.medialoader.core.logging.AppLog.w("Library", "Playlist alias persistence deferred") }
        try { withContext(Dispatchers.IO) {
            com.domenota.medialoader.data.playback.VideoWatchProgress.linkAliases(
                getApplication<Application>().getSharedPreferences("video_playback_positions", Context.MODE_PRIVATE),
                refs.filter { it.item.mediaType == com.domenota.medialoader.core.model.MediaType.VIDEO }
                    .flatMap { ref -> (ref.aliases + ref.key).map { it to ref.key } }.toMap())
        } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { com.domenota.medialoader.core.logging.AppLog.w("Library", "Video alias persistence deferred") }

            }
        }
    }

    private val accessSignatures = mutableMapOf<AppMode, List<Boolean>>()
    private val refreshedAt = mutableMapOf<AppMode, Long>()
    private val storeStamps = mutableMapOf<AppMode, PhoneStoreStamp>()
    private var observedPrivateKeys: Set<String>? = null
    private var mediaChangeJob: Job? = null
    private var collectionChangeJob: Job? = null
    private val collectionAddressRevisionState = MutableStateFlow(CollectionMediaSignal())
    val collectionAddressRevision = collectionAddressRevisionState.asStateFlow()
    private val pendingCollectionUris = linkedSetOf<String>()
    private val pendingCollectionModes = linkedSetOf<AppMode>()
    private var unknownCollectionChange = false
    private fun notifyCollectionAddresses(uri: android.net.Uri?) {
        pendingCollectionModes += phoneModesForMediaChange(uri?.toString())
        val typed = uri?.pathSegments?.let { it.size==4 && it[2]=="media" && it[3].toLongOrNull()!=null } == true
        if(!typed || pendingCollectionUris.size>=128) unknownCollectionChange=true
        else pendingCollectionUris += requireNotNull(uri).toString()
        // Do not wait for a running full-gallery job. Continuous notifications
        // are coalesced into fixed windows rather than starving the address pass.
        if (collectionChangeJob?.isActive == true) return
        collectionChangeJob = viewModelScope.launch {
            kotlinx.coroutines.delay(750)
            collectionAddressRevisionState.value=CollectionMediaSignal(collectionAddressRevisionState.value.revision+1,
                pendingCollectionUris.toSet().takeUnless { unknownCollectionChange },pendingCollectionModes.toSet())
            pendingCollectionUris.clear();pendingCollectionModes.clear();unknownCollectionChange=false
        }
    }
    private val pendingMediaChanges = mutableSetOf<AppMode>()
    private var editorHoldsAutomaticIndexing = false
    private var recoveryRefreshPending = false
    private var observedRecoveryVersion = HistoricalRecoverySignals.version.value
    private var recoveryGateObservedRunning = false
    private var interruptedUnchangedRecoveryScans: Set<AppMode> = emptySet()
    private var recoveryAffectedModes: Set<AppMode> = setOf(AppMode.GALLERY,AppMode.MUSIC)
    private var postTransferSerial = 0L
    private var postTransferJob: Job? = null
    private val postTransferState = MutableStateFlow(PostTransferCatalogStatus.IDLE)
    val postTransferCatalogStatus = postTransferState.asStateFlow()
    fun holdAutomaticMediaIndexing(held: Boolean) {
        if (editorHoldsAutomaticIndexing == held) return
        editorHoldsAutomaticIndexing = held
        HistoricalRecoverySignals.setEditorHold(this, held)
        if (held) mediaChangeJob?.cancel()
        else if (recoveryRefreshPending) refreshAfterHistoricalRecovery()
        else if (pendingMediaChanges.isNotEmpty()) scheduleMediaChange(null, addModes = false)
    }

    private fun refreshAfterHistoricalRecovery() {
        if (!recoveryRefreshPending || editorHoldsAutomaticIndexing || HistoricalRecoverySignals.isRunning.value) return
        recoveryRefreshPending = false
        val modes = recoveryRefreshModes(
            recoveryAffectedModes.mapTo(mutableSetOf()) {
                if (it == AppMode.GALLERY)
                    com.domenota.medialoader.data.storage.HistoricalMediaKind.VISUAL
                else com.domenota.medialoader.data.storage.HistoricalMediaKind.AUDIO
            },pendingMediaChanges)
        if (com.domenota.medialoader.core.logging.AppLog.isDetailedPerformance()) {
            com.domenota.medialoader.core.logging.AppLog.i("Perf",
                "RECOVERY_REFRESH_REQUEST modes=${modes.joinToString(",")} pendingMediaChanges=${pendingMediaChanges.size} " +
                "galleryLoaded=${galleryState.value.loaded} musicLoaded=${musicState.value.loaded}")
        }
        pendingMediaChanges.clear()
        // A completed file batch is not yet a complete media-library refresh.
        val serial = ++postTransferSerial
        postTransferJob?.cancel()
        postTransferState.value = PostTransferCatalogStatus.UPDATING
        // Folder receipts/pending members already invalidate the exact visibility
        // projection. Do not revoke the unrelated, still-authorized 11k-row
        // phone generation: reconcile from it and publish once verified.
        for (mode in modes) refresh(mode, force = true, reason = PhoneScanReason.RECOVERY)
        val galleryJob = jobs[AppMode.GALLERY]
        val musicJob = jobs[AppMode.MUSIC]
        postTransferJob = viewModelScope.launch {
            // Bound the WHOLE background recovery, including provider scans.
            // A stalled Android cursor must not leave the UI in "updating" forever.
            // The deadline never cancels or rolls back the durable transfer.
            val ready = withTimeoutOrNull(90_000L) {
                galleryJob?.join()
                musicJob?.join()
                combine(index, gallery, music, visibilityGuard) { current, pictures, audio, guard ->
                    postTransferIndexAligned(current, pictures, audio, guard, modes)
                }.first { it }
                true
            } == true
            if (serial == postTransferSerial) {
                postTransferState.value =
                    if (ready) PostTransferCatalogStatus.READY else PostTransferCatalogStatus.INCOMPLETE
                com.domenota.medialoader.core.logging.AppLog.i("Library",
                    "RECOVERY_CATALOG_RESULT verified=$ready serial=$serial")
            }
        }
    }

    init {
        viewModelScope.launch {
            combine(HistoricalRecoverySignals.isRunning, HistoricalRecoverySignals.version) { running, version ->
                running to version
            }.collect { (running, version) ->
                val entered = enteringRecoveryGate(running,recoveryGateObservedRunning)
                val exited = !running && recoveryGateObservedRunning
                recoveryGateObservedRunning = running
                if (entered) {
                    interruptedUnchangedRecoveryScans = listOf(AppMode.GALLERY,AppMode.MUSIC).filter { mode ->
                        val phoneState = if(mode==AppMode.GALLERY) galleryState.value else musicState.value
                        restartScanAfterUnchangedRecovery(jobs[mode]?.isActive == true,phoneState)
                    }.toSet()
                    transferBootstrapEpochs.clear()
                    postTransferSerial++
                    postTransferJob?.cancel()
                    postTransferState.value = PostTransferCatalogStatus.IDLE
                    if (com.domenota.medialoader.core.logging.AppLog.isDetailedPerformance()) {
                        com.domenota.medialoader.core.logging.AppLog.i("Perf",
                            "RECOVERY_GATE state=ENTER activeScans=${jobs.values.count { it.isActive }} " +
                            "galleryLoaded=${galleryState.value.loaded} musicLoaded=${musicState.value.loaded}")
                    }
                    mediaChangeJob?.cancel()
                    jobs.values.forEach { it.cancel() }
                    jobs.clear()
                    val indexed = index.value
                    galleryState.value = retainPhoneSnapshotAtTransferBoundary(
                        galleryState.value, indexed.galleryRevision, indexed.ready)
                    musicState.value = retainPhoneSnapshotAtTransferBoundary(
                        musicState.value, indexed.musicRevision, indexed.ready)
                    // Actual permission changes still advance accessEpoch in
                    // refresh(); historical file operations must not masquerade
                    // as revoked READ_MEDIA_* grants.
                } else if (!running && version != observedRecoveryVersion) {
                    interruptedUnchangedRecoveryScans = emptySet()
                    if (com.domenota.medialoader.core.logging.AppLog.isDetailedPerformance()) {
                        com.domenota.medialoader.core.logging.AppLog.i("Perf",
                            "RECOVERY_GATE state=EXIT changedVersion=true " +
                            "invalidates=GALLERY,MUSIC,IDENTITY,STAMP,CACHE")
                    }
                    // Only a validated exact MIME list narrows provider rescans.
                    // On a skipped/conflated version, unknown source or unrelated
                    // historical recovery, the signal conservatively selects both.
                    val affectedKinds = HistoricalRecoverySignals.affectedMediaKindsSince(observedRecoveryVersion,version)
                    recoveryAffectedModes = recoveryRefreshModes(affectedKinds)
                    observedRecoveryVersion = version
                    recoveryRefreshPending = true
                    // Alias changes still require a fresh current canonical index.
                    // Provider inventories, however, only change for touched MIME.
                    identityCache.clear()
                    for (mode in recoveryAffectedModes) {
                        storeStamps.remove(mode)
                        refreshedAt.remove(mode)
                        phoneCache.clear(mode)
                    }
                    externalCatalog.reloadHistoricalMedia()
                    playlistRepository.reloadHistoricalMedia()
                    refreshAfterHistoricalRecovery()
                } else if (exited) {
                    // No durable media mutation: don't invalidate a completed
                    // catalogue or clear caches. Only restart scans actually
                    // interrupted before they could validate a usable snapshot.
                    val interrupted=interruptedUnchangedRecoveryScans
                    interruptedUnchangedRecoveryScans=emptySet()
                    for(mode in interrupted) {
                        val snapshot=if(mode==AppMode.GALLERY) galleryState.value else musicState.value
                        if(restartScanAfterUnchangedRecovery(true,snapshot))
                            refresh(mode,force=true,reason=PhoneScanReason.REQUEST)
                    }
                    if(interrupted.isNotEmpty()) com.domenota.medialoader.core.logging.AppLog.i(
                        "Library","RECOVERY_UNCHANGED restoredInterruptedScans=${interrupted.size} invalidates=NONE")
                }
            }
        }
    }
    private fun scheduleMediaChange(uri: android.net.Uri?, addModes: Boolean = true) {
        // Only a changed media kind needs rescanning. Preserve unknown URI events
        // conservatively, and coalesce successive callbacks while scans are busy.
        if (addModes) {
            pendingMediaChanges += phoneModesForMediaChange(uri?.toString())
            notifyCollectionAddresses(uri)
        }
        if (editorHoldsAutomaticIndexing || HistoricalRecoverySignals.isRunning.value) return
        // A fixed window, not a restartable debounce: notifications arriving
        // while a scan or stamp read is in flight belong to the next batch.
        // Do not cancel a worker on every callback or starve a busy provider.
        if(mediaChangeJob?.isActive==true)return
        mediaChangeJob = viewModelScope.launch {
            while(pendingMediaChanges.isNotEmpty()) {
                kotlinx.coroutines.delay(500)
                if (editorHoldsAutomaticIndexing || HistoricalRecoverySignals.isRunning.value) return@launch
                // Reconcile independently. A busy scan retains its pending
                // notification; do not query the provider while every mode is busy.
                val affected=readyPhoneMediaChanges(pendingMediaChanges,
                    pendingMediaChanges.filterTo(mutableSetOf()){jobs[it]?.isActive==true})
                if(affected.isEmpty())continue
                pendingMediaChanges.removeAll(affected)
                val loaded=affected.filter { mode ->
                    (if(mode==AppMode.GALLERY)galleryState else musicState).value.loaded
                }
                if(AppMode.MUSIC in affected) AudioWaveformPrecompute.request(getApplication())
                if(loaded.isEmpty())continue
                val currentStamp=try { phone.storeStamp() }
                catch(e:CancellationException){pendingMediaChanges+=affected;throw e}
                // A hold may have cancelled this coroutine during the IO call.
                // If not cancelled, still check the barrier before dispatching.
                if(editorHoldsAutomaticIndexing||HistoricalRecoverySignals.isRunning.value){
                    pendingMediaChanges+=affected
                    return@launch
                }
                for (mode in loaded) {
                    val state = if (mode == AppMode.GALLERY) galleryState else musicState
                    val permissions = PhoneMediaLibrary.permissions(mode)
                    val signature = permissions.map { permission ->
                        ContextCompat.checkSelfPermission(getApplication(), permission) == PackageManager.PERMISSION_GRANTED
                    }
                    val selectedOnly = android.os.Build.VERSION.SDK_INT >= 34 && mode == AppMode.GALLERY &&
                        permissions.zip(signature).any { (name, granted) ->
                            name == android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED && granted
                        } && permissions.zip(signature).any { (name, granted) ->
                            name != android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED && !granted
                        }
                    // Never treat partial or failed catalogs as a complete
                    // generation. Permissions and privacy invalidations keep
                    // their existing direct refresh path.
                    if (state.value.complete && state.value.message==null &&
                        maySkipUnchangedMediaNotification(storeStamps[mode], currentStamp,
                            accessSignatures[mode] == signature, selectedOnly)) {
                        com.domenota.medialoader.core.logging.DetailedPerformance.measure(com.domenota.medialoader.core.logging.PerfWork.PHONE_SCAN_SKIPPED){}
                        continue
                    }
                    if(jobs[mode]?.isActive==true){
                        pendingMediaChanges+=mode
                        continue
                    }
                    refresh(mode, force = true, reason = PhoneScanReason.MEDIASTORE_CHANGE)
                }
            }
        }
    }
    private val mediaObserver = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            scheduleMediaChange(null)
        }
        override fun onChange(selfChange: Boolean, uri: android.net.Uri?) {
            scheduleMediaChange(uri)
        }
    }
    init {
        application.contentResolver.registerContentObserver(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, mediaObserver)
        application.contentResolver.registerContentObserver(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, mediaObserver)
        application.contentResolver.registerContentObserver(android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, mediaObserver)
        viewModelScope.launch {
            privateKeys.filterNotNull().collect { keys ->
                val changed = observedPrivateKeys?.let { it != keys } == true
                observedPrivateKeys = keys
                if (changed) {
                    identityCache.clear()
                    for (mode in listOf(AppMode.GALLERY, AppMode.MUSIC)) {
                        val state = if (mode == AppMode.GALLERY) galleryState else musicState
                        if (state.value.loaded || jobs[mode]?.isActive == true) refresh(mode, force = true, invalidate = true, reason = PhoneScanReason.PRIVACY_CHANGE)
                    }
                }
            }
        }
    }

    fun refreshOnNavigation(mode:AppMode){
        if(mode==AppMode.DOWNLOADER)return
        val permissions=PhoneMediaLibrary.permissions(mode)
        val signature=permissions.map{permission->
            ContextCompat.checkSelfPermission(getApplication(),permission)==PackageManager.PERMISSION_GRANTED
        }
        val selectedOnly=android.os.Build.VERSION.SDK_INT>=34&&mode==AppMode.GALLERY&&
            permissions.zip(signature).any{(name,granted)->name==android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED&&granted}&&
            permissions.zip(signature).any{(name,granted)->name!=android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED&&!granted}
        val state=(if(mode==AppMode.GALLERY)galleryState else musicState).value
        if(needsPhoneRefreshOnNavigation(state,accessSignatures[mode]!=signature,selectedOnly))refresh(mode)
        // Keep existing observer reconciliation, including changes queued during
        // an editor/transfer; never consume or discard them just on navigation.
        else if(mode in pendingMediaChanges)scheduleMediaChange(null,addModes=false)
    }

    fun refresh(mode: AppMode, force: Boolean = false, invalidate: Boolean = false, reason: PhoneScanReason = PhoneScanReason.REQUEST) {
        if (mode == AppMode.DOWNLOADER) return
        val context = getApplication<Application>()
        val permissions = PhoneMediaLibrary.permissions(mode)
        fun currentSignature() = permissions.map { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        val signature = currentSignature()
        if (mode == AppMode.MUSIC && signature.any { it } && !HistoricalRecoverySignals.isRunning.value) AudioWaveformPrecompute.request(context)
        val coldStart = accessSignatures[mode] == null && !invalidate
        val accessKey = permissions.zip(signature).filter { it.second }.map { it.first }.sorted().joinToString("|")
        val changedAccess = accessSignatures[mode] != signature
        val partial = android.os.Build.VERSION.SDK_INT >= 34 && mode == AppMode.GALLERY &&
            permissions.zip(signature).any { (name, granted) -> name == android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED && granted } &&
            permissions.zip(signature).any { (name, granted) -> name != android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED && !granted }
        val state = if (mode == AppMode.GALLERY) galleryState else musicState
        val bootstrapGuard = visibilityGuard.value
        val bootstrapDuringTransfer = privateKeys.value != null && canBootstrapPhoneDuringTransfer(
            reason, HistoricalRecoverySignals.isRunning.value, bootstrapGuard, state.value,
            changedAccess, invalidate, partial)
        if (HistoricalRecoverySignals.isRunning.value && !bootstrapDuringTransfer && !changedAccess && !invalidate && (!partial || reason != PhoneScanReason.REQUEST)) {
            pendingMediaChanges += mode
            return
        }
        val canonical = index.value
        val indexedRevision = if (mode == AppMode.GALLERY) canonical.galleryRevision else canonical.musicRevision
        val silent = canRefreshLibrarySilently(state.value, canonical, indexedRevision,
            changedAccess, partial, invalidate)
        // Preserve the current usable snapshot for routine refreshes. Full invalidation
        // is reserved for permission or private-access changes, not MediaStore churn.
        var blocking = !silent && !(state.value.loaded && !changedAccess && !partial && !invalidate)
        val now = android.os.SystemClock.elapsedRealtime()
        // Even selected-only access must not repeatedly cancel an ongoing validation.
        // Permission/privacy invalidation is the only reason to restart it.
        if (!changedAccess && !invalidate && jobs[mode]?.isActive == true) return
        if (silent && !force && now - (refreshedAt[mode] ?: Long.MIN_VALUE / 2) < 60_000) return
        val scanToken=Any()
        val scanGeneration = ++phoneScanGeneration
        val scanReason = if (changedAccess) PhoneScanReason.ACCESS_CHANGED else reason
        if (com.domenota.medialoader.core.logging.AppLog.isDetailedPerformance() && reason == PhoneScanReason.RECOVERY) {
            com.domenota.medialoader.core.logging.AppLog.i("Perf",
                "RECOVERY_SCAN_DECISION mode=$mode effectiveReason=$scanReason force=$force invalidate=$invalidate " +
                "blocking=$blocking changedAccess=$changedAccess partialAccess=$partial " +
                "previousLoaded=${state.value.loaded} previousCount=${state.value.items.size} " +
                "previousScanActive=${jobs[mode]?.isActive == true}")
        }
        if(mode==AppMode.GALLERY){galleryScanToken=scanToken;galleryScanInProgressState.value=signature.any{it}}
        jobs.remove(mode)?.cancel()
        val epoch = if (mode == AppMode.GALLERY) galleryAccessEpochState else musicAccessEpochState
        if (changedAccess || invalidate || partial) epoch.value++
        val accessEpoch = epoch.value
        accessSignatures[mode] = signature
        if (signature.none { it }) {
            if (!changedAccess && !invalidate && state.value.loaded) return
            refreshedAt[mode] = now
            state.value = PhoneLibraryState(loaded = true, complete = true, revision = state.value.revision + 1, accessEpoch = accessEpoch, message =
                if (mode == AppMode.MUSIC) "Разрешите доступ к музыке телефона" else "Разрешите доступ к фото и видео телефона")
            viewModelScope.launch { phoneCache.clear(mode) }
            return
        }
        if (HistoricalRecoverySignals.isRunning.value && !bootstrapDuringTransfer) {
            pendingMediaChanges += mode
            // Grant changes (including a renewed selected-only selection) must
            // invalidate immediately even though provider scanning is deferred.
            if (changedAccess || invalidate || partial) state.value = PhoneLibraryState(
                revision = state.value.revision + 1, accessEpoch = accessEpoch)
            return
        }
        val bootstrapVersion = HistoricalRecoverySignals.version.value
        val bootstrapFolder = bootstrapGuard?.policy?.pendingFolder
        if (bootstrapDuringTransfer) {
            if (!transferBootstrapEpochs.reserve(mode, accessEpoch)) return
            pendingMediaChanges += mode
        }
        // Routine updates are strictly internal: keep the validated snapshot and
        // avoid publishing even a loading flag that the gallery UI could display.
        state.value = if (blocking) PhoneLibraryState(loading = true, requiresValidation = !(coldStart && !partial), revision = state.value.revision + 1, accessEpoch = accessEpoch)
            else state.value.copy(loading = false, requiresValidation = false, message = null, accessEpoch = accessEpoch)
        jobs[mode] = viewModelScope.launch {
            var revision = state.value.revision
            try {
                val keys = privateKeys.filterNotNull().first()
                val privacy = PhoneLibraryCache.privacyTag(keys)
                fun stillAllowed(): Boolean {
                    val live = visibilityGuard.value
                    val transferSafe = !HistoricalRecoverySignals.isRunning.value ||
                        (bootstrapDuringTransfer && HistoricalRecoverySignals.version.value == bootstrapVersion &&
                            live != null && !live.policy.unknownPending &&
                            (live.policy.pendingFolder == bootstrapFolder || live.policy.pendingFolder == null))
                    return transferSafe && currentSignature() == signature && privateKeys.value == keys
                }
                var stamp = phone.storeStamp()
                if (!stillAllowed()) { refresh(mode, force = true, invalidate = true, reason = PhoneScanReason.ACCESS_CHANGED); return@launch }
                if (coldStart && !partial && !bootstrapDuringTransfer) {
                    val cached = phoneCache.read(mode, accessKey, privacy, stamp)
                    if (!stillAllowed()) { refresh(mode, force = true, invalidate = true, reason = PhoneScanReason.ACCESS_CHANGED); return@launch }
                    if (cached != null) {
                        blocking = false
                        revision++
                        state.value = PhoneLibraryState(items = cached.snapshot.items, identities = cached.snapshot.identities,
                            fingerprints = cached.snapshot.fingerprints, loaded = true, loading = false,
                            revision = revision, requiresValidation = false, accessEpoch = accessEpoch, complete = cached.complete)
                        storeStamps[mode] = cached.stamp
                    }
                }
                val previousStamp = storeStamps[mode]
                if (!blocking && previousStamp?.versions != null && previousStamp.versions != stamp.versions) {
                    // Android can recreate a MediaStore database without revoking access.
                    // Reindex in the background; never discard photos/music already visible.
                    blocking = !state.value.loaded
                    if (blocking) state.value = PhoneLibraryState(loading = true, revision = ++revision, accessEpoch = accessEpoch)
                }
                if (!blocking && state.value.complete && stamp.generation != null && previousStamp == stamp) {
                    state.value = state.value.copy(loading = false)
                    com.domenota.medialoader.core.logging.DetailedPerformance.measure(com.domenota.medialoader.core.logging.PerfWork.PHONE_SCAN_SKIPPED) {}
                    com.domenota.medialoader.core.logging.AppLog.i("Library", "Phone scan skipped · owner=phone · mode=$mode · reason=$scanReason · generation=$scanGeneration · accessEpoch=$accessEpoch · stamp=unchanged")
                    refreshedAt[mode] = android.os.SystemClock.elapsedRealtime()
                    return@launch
                }
                if (blocking) state.value = PhoneLibraryState(loading = true, revision = revision, requiresValidation = true, accessEpoch = accessEpoch)
                val previous = state.value.takeIf { !blocking && it.loaded }?.let {
                    PhoneMediaSnapshot(it.items, it.identities, it.fingerprints)
                }
                val startedAt = android.os.SystemClock.elapsedRealtime()
                val scanTiming=com.domenota.medialoader.core.logging.DetailedPerformance.begin()
                val recoveryTiming=if (scanReason == PhoneScanReason.RECOVERY)
                    com.domenota.medialoader.core.logging.DetailedPerformance.begin() else Long.MIN_VALUE
                val cacheCheckpoint = PhoneScanCheckpointPolicy()
                com.domenota.medialoader.core.logging.AppLog.i("Library", "Phone scan started · owner=phone · mode=$mode · reason=$scanReason · generation=$scanGeneration · accessEpoch=$accessEpoch · retained=${previous?.items?.size ?: 0}")
                val progress: (suspend (PhoneMediaSnapshot) -> Unit)? = if (blocking && !partial) { { partialSnapshot ->
                    if (blocking && !partial && stillAllowed() && (!bootstrapDuringTransfer || !state.value.loaded)) {
                        val retainAdmittedIndex = mayRetainProgressivePhoneIndex(state.value, accessEpoch, partial)
                        state.value = PhoneLibraryState(items = partialSnapshot.items, identities = partialSnapshot.identities,
                            fingerprints = partialSnapshot.fingerprints, loaded = true, loading = true,
                            revision = ++revision, requiresValidation = !retainAdmittedIndex, accessEpoch = accessEpoch)
                        // A large library can publish progress every second; serializing
                        // 11k+ entries to JSON on *every* update stalls the cursor.
                        // Keep first-page recovery, then bounded checkpoints; the final
                        // validated snapshot is persisted after the scan.
                        if (!bootstrapDuringTransfer && cacheCheckpoint.shouldSave(partialSnapshot.items.size)) {
                            try { phoneCache.write(mode, accessKey, privacy,
                                PhoneStoreStamp(stamp.versions, null), partialSnapshot, complete = false) }
                            catch (e: CancellationException) { throw e }
                            catch (_: Exception) { /* The completed snapshot retries persistence. */ }
                        }
                    }
                }
                } else null
                val snapshot = phone.scanSnapshot(mode, previous, keys.filterTo(mutableSetOf()) { it.startsWith("file-stat:") }, onProgress = progress)
                com.domenota.medialoader.core.logging.DetailedPerformance.end(com.domenota.medialoader.core.logging.PerfWork.PHONE_SCAN,scanTiming)
                com.domenota.medialoader.core.logging.DetailedPerformance.end(com.domenota.medialoader.core.logging.PerfWork.PHONE_SCAN_RECOVERY,recoveryTiming)
                com.domenota.medialoader.core.logging.AppLog.i("Library", "Phone scan finished · owner=phone · mode=$mode · reason=$scanReason · generation=$scanGeneration · accessEpoch=$accessEpoch · count=${snapshot.items.size} · ms=${android.os.SystemClock.elapsedRealtime() - startedAt}")
                val after = phone.storeStamp()
                if (!stillAllowed()) { refresh(mode, force = true, invalidate = true, reason = PhoneScanReason.ACCESS_CHANGED); return@launch }
                if (stamp.versions != null && after.versions != stamp.versions)
                    throw java.io.IOException("MediaStore database changed during indexing")
                // Never label a scan with a newer generation that it may not have observed.
                stamp = PhoneStoreStamp(stamp.versions, stamp.generation.takeIf { after == stamp })
                val existing = state.value
                val unchanged = !blocking && existing.accessEpoch == accessEpoch &&
                    withContext(Dispatchers.Default) { samePhoneContent(existing,snapshot) }
                if (!stillAllowed()) { refresh(mode,force=true,invalidate=true,reason=PhoneScanReason.ACCESS_CHANGED); return@launch }
                if (unchanged) {
                    if (existing.loading || existing.message != null || existing.requiresValidation) state.value = existing.copy(loading=false,message=null,requiresValidation=false)
                    com.domenota.medialoader.core.logging.AppLog.i("Library", "Phone snapshot unchanged · mode=$mode")
                } else state.value = PhoneLibraryState(items = snapshot.items, identities = snapshot.identities,
                    fingerprints = snapshot.fingerprints, loaded = true, complete = true, revision = revision + 1, requiresValidation = blocking && !bootstrapDuringTransfer &&
                        !mayRetainProgressivePhoneIndex(existing, accessEpoch, partial), accessEpoch = accessEpoch)
                storeStamps[mode] = stamp
                refreshedAt[mode] = android.os.SystemClock.elapsedRealtime()
                if (!bootstrapDuringTransfer && !partial && (!unchanged || previousStamp != stamp)) try { phoneCache.write(mode, accessKey, privacy, stamp, snapshot) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { com.domenota.medialoader.core.logging.AppLog.w("Library", "Phone metadata cache save deferred") }
            }
            catch (e: CancellationException) {
                com.domenota.medialoader.core.logging.AppLog.i("Library", "Phone scan cancelled · owner=phone · mode=$mode · reason=$scanReason · generation=$scanGeneration · accessEpoch=$accessEpoch")
                throw e
            }
            catch (_: Exception) {
                com.domenota.medialoader.core.logging.AppLog.w("Library", "Phone scan failed · owner=phone · mode=$mode · reason=$scanReason · generation=$scanGeneration · accessEpoch=$accessEpoch")
                val message = "Не удалось обновить файлы телефона. Проверьте доступ и повторите."
                state.value = if (!blocking && state.value.loaded) state.value.copy(loading = false, message = message)
                    else PhoneLibraryState(loaded = true, revision = revision + 1, message = message, accessEpoch = accessEpoch)
            }finally{
                if(mode==AppMode.GALLERY&&galleryScanToken===scanToken)galleryScanInProgressState.value=false
            }
        }
    }

    override fun onCleared() {
        HistoricalRecoverySignals.setEditorHold(this, false)
        getApplication<Application>().contentResolver.unregisterContentObserver(mediaObserver)
        audioPreview.close()
        super.onCleared()
    }
}
