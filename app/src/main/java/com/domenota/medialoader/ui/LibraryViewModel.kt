package com.domenota.medialoader.ui

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.data.library.*
import com.domenota.medialoader.data.MediaRepository
import com.domenota.medialoader.data.playback.PublicMediaIdentity
import com.domenota.medialoader.data.playback.AudioWaveformPrecompute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class PhoneLibraryState(
    val items: List<DownloadEntity> = emptyList(), val loading: Boolean = false,
    val loaded: Boolean = false, val message: String? = null, val revision: Long = 0,
    val identities: Map<String, Set<String>> = emptyMap(), val requiresValidation: Boolean = true,
    val fingerprints: Map<String, String> = emptyMap(),
)
internal data class LibraryIndex(val ready: Boolean = false, val refs: List<LibraryMediaRef> = emptyList(),
    val galleryRevision: Long = 0, val musicRevision: Long = 0)
private data class LibraryIndexInput(val app: List<DownloadEntity>, val gallery: PhoneLibraryState,
    val music: PhoneLibraryState, val privateKeys: Set<String>?)

/** Retained editor references are not validated public display data until this snapshot lands. */
internal fun phoneValidationPending(index: LibraryIndex, gallery: PhoneLibraryState, music: PhoneLibraryState): Boolean =
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
    return state.requiresValidation &&
        ((state.loading && !state.loaded) || state.revision != indexedRevision)
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

/** Map MediaStore notifications to the affected library without guessing for unknown URIs. */
internal fun phoneModesForMediaChange(uri: String?): Set<AppMode> {
    val both = setOf(AppMode.GALLERY, AppMode.MUSIC)
    if (uri.isNullOrBlank()) return both
    val segments = runCatching { java.net.URI(uri).path.orEmpty()
        .split('/').map { it.lowercase(java.util.Locale.ROOT) } }.getOrNull() ?: return both
    val pictures = "images" in segments || "video" in segments
    val audio = "audio" in segments
    return when {
        pictures && !audio -> setOf(AppMode.GALLERY)
        audio && !pictures -> setOf(AppMode.MUSIC)
        else -> both
    }
}

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

@OptIn(ExperimentalCoroutinesApi::class)
internal class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val equalizerRepository = com.domenota.medialoader.data.playback.EqualizerSettingsRepository.get(application)
    private val equalizerDraft = MutableStateFlow<com.domenota.medialoader.data.playback.EqualizerSettings?>(null)
    val equalizerSettings = equalizerDraft.asStateFlow()
    private val equalizerFailure = MutableStateFlow<String?>(null)
    val equalizerError = equalizerFailure.asStateFlow()
    private val equalizerWrites = kotlinx.coroutines.channels.Channel<com.domenota.medialoader.data.playback.EqualizerSettings>(kotlinx.coroutines.channels.Channel.CONFLATED)
    init {
        viewModelScope.launch {
            equalizerRepository.awaitLoaded()
            equalizerDraft.value = equalizerRepository.settings.value
            for (settings in equalizerWrites) {
                try { equalizerRepository.setSettings(settings); equalizerFailure.value = null }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { equalizerFailure.value = "Не удалось сохранить настройки эквалайзера" }
            }
        }
    }
    fun setEqualizer(settings: com.domenota.medialoader.data.playback.EqualizerSettings) {
        equalizerDraft.value = settings
        equalizerWrites.trySend(settings)
    }
    val playlistRepository = PlaylistRepository(application)
    val externalCatalog = ExternalMediaCatalogRepository(application)
    val audioPreview = com.domenota.medialoader.data.playback.LibraryAudioPreview(application)
    private val phone = PhoneMediaLibrary(application)
    private val phoneCache = PhoneLibraryCache(application)
    private val galleryState = MutableStateFlow(PhoneLibraryState())
    private val musicState = MutableStateFlow(PhoneLibraryState())
    val gallery = galleryState.asStateFlow()
    val music = musicState.asStateFlow()
    private val jobs = mutableMapOf<AppMode, Job>()
    private val mediaRepository = MediaRepository.get(application)
    // null means privacy validation has not finished yet: never publish a speculative public list.
    val privateKeys = mediaRepository.privateMediaIds
        .mapLatest<Set<String>, Set<String>?> { it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val identityCache = java.util.concurrent.ConcurrentHashMap<String, Set<String>>()
    // Active download progress is not library content. Do not cancel/restart identity indexing
    // on every byte/progress update while a gallery scan is in flight.
    private val completedDownloads = mediaRepository.downloads.map { rows ->
        rows.filter { it.state == com.domenota.medialoader.core.model.DownloadState.COMPLETED &&
            !it.hidden && !it.isPrivate && !it.savedUri.isNullOrBlank() }
    }.distinctUntilChanged()
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
        scannedIdentities: Map<String, Set<String>> = emptyMap()): List<LibraryMediaRef> = withContext(Dispatchers.IO) {
        suspend fun reference(row: DownloadEntity): LibraryMediaRef {
            coroutineContext.ensureActive()
            val uri = row.savedUri
            val identities = if (uri == null) emptySet() else scannedIdentities[uri] ?: identityCache[uri]
                ?: PublicMediaIdentity.capture(getApplication(), uri).also { identityCache[uri] = it }
            return LibraryMediaRef(row.id, identities + row.id, row, row.id.takeIf { isApp },
                deviceAlbumId = if (isApp) null else row.bulkMediaKey ?: row.groupId,
                deviceAlbumName = if (isApp) null else row.groupId)
        }
        if (isApp) rows.chunked(8).flatMap { batch -> coroutineScope {
            batch.map { row -> async { reference(row) } }.awaitAll()
        } } else rows.map { reference(it) }
    }
    private val appReferences = completedDownloads.transformLatest<List<DownloadEntity>, List<LibraryMediaRef>?> { rows ->
        // Phone revisions must not restart this work, and a large history must not delay
        // the first visible page until its last provider URI has been resolved.
        val resolved = mutableListOf<LibraryMediaRef>()
        if (rows.isEmpty()) emit(emptyList())
        for (batch in rows.chunked(16)) {
            resolved += references(batch, true)
            emit(resolved.toList())
        }
        com.domenota.medialoader.core.logging.AppLog.i("Library", "App identity index ready · count=${resolved.size}")
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val index = combine(appReferences, gallery, music, privateKeys) { app, pictures, audio, private ->
        if (private == null || app == null) LibraryIndex() else withContext(Dispatchers.IO) {
            LibraryIndex(true, UnifiedMediaLibrary.merge(app,
                references(pictures.items + audio.items, false, pictures.identities + audio.identities), private),
                pictures.revision, audio.revision)
        }
    }.onEach { index ->
        try { playlistRepository.reconcileAliases(index.refs.flatMap { ref ->
            (ref.aliases + ref.key).map { it to ref.key }
        }.toMap()) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { com.domenota.medialoader.core.logging.AppLog.w("Library", "Playlist alias persistence deferred") }
        try { withContext(Dispatchers.IO) {
            com.domenota.medialoader.data.playback.VideoWatchProgress.linkAliases(
                getApplication<Application>().getSharedPreferences("video_playback_positions", Context.MODE_PRIVATE),
                index.refs.filter { it.item.mediaType == com.domenota.medialoader.core.model.MediaType.VIDEO }
                    .flatMap { ref -> (ref.aliases + ref.key).map { it to ref.key } }.toMap())
        } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { com.domenota.medialoader.core.logging.AppLog.w("Library", "Video alias persistence deferred") }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LibraryIndex())

    private val accessSignatures = mutableMapOf<AppMode, List<Boolean>>()
    private val refreshedAt = mutableMapOf<AppMode, Long>()
    private val storeStamps = mutableMapOf<AppMode, PhoneStoreStamp>()
    private var observedPrivateKeys: Set<String>? = null
    private var mediaChangeJob: Job? = null
    private val pendingMediaChanges = mutableSetOf<AppMode>()
    private fun scheduleMediaChange(uri: android.net.Uri?) {
        // Only a changed media kind needs rescanning. Preserve unknown URI events
        // conservatively, and coalesce successive callbacks while scans are busy.
        pendingMediaChanges += phoneModesForMediaChange(uri?.toString())
        if (AppMode.MUSIC in pendingMediaChanges) AudioWaveformPrecompute.request(getApplication())
        mediaChangeJob?.cancel()
        mediaChangeJob = viewModelScope.launch {
            kotlinx.coroutines.delay(500)
            val affected = pendingMediaChanges.toSet()
            // The current scan must finish before the next reconciliation starts.
            affected.forEach { jobs[it]?.join() }
            pendingMediaChanges.removeAll(affected)
            affected.forEach { mode ->
                val state = if (mode == AppMode.GALLERY) galleryState else musicState
                if (!state.value.loaded) return@forEach
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
                val currentStamp = phone.storeStamp()
                if (maySkipUnchangedMediaNotification(storeStamps[mode], currentStamp,
                        accessSignatures[mode] == signature, selectedOnly)) return@forEach
                refresh(mode, force = true)
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
                        if (state.value.loaded || jobs[mode]?.isActive == true) refresh(mode, force = true, invalidate = true)
                    }
                }
            }
        }
    }

    fun refresh(mode: AppMode, force: Boolean = false, invalidate: Boolean = false) {
        if (mode == AppMode.DOWNLOADER) return
        val context = getApplication<Application>()
        val permissions = PhoneMediaLibrary.permissions(mode)
        fun currentSignature() = permissions.map { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        val signature = currentSignature()
        if (mode == AppMode.MUSIC && signature.any { it }) AudioWaveformPrecompute.request(context)
        val coldStart = accessSignatures[mode] == null && !invalidate
        val accessKey = permissions.zip(signature).filter { it.second }.map { it.first }.sorted().joinToString("|")
        val changedAccess = accessSignatures[mode] != signature
        val partial = android.os.Build.VERSION.SDK_INT >= 34 && mode == AppMode.GALLERY &&
            permissions.zip(signature).any { (name, granted) -> name == android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED && granted } &&
            permissions.zip(signature).any { (name, granted) -> name != android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED && !granted }
        val state = if (mode == AppMode.GALLERY) galleryState else musicState
        val canonical = index.value
        val indexedRevision = if (mode == AppMode.GALLERY) canonical.galleryRevision else canonical.musicRevision
        val silent = canRefreshLibrarySilently(state.value, canonical, indexedRevision,
            changedAccess, partial, invalidate)
        var blocking = !silent
        val now = android.os.SystemClock.elapsedRealtime()
        // Even selected-only access must not repeatedly cancel an ongoing validation.
        // Permission/privacy invalidation is the only reason to restart it.
        if (!changedAccess && !invalidate && jobs[mode]?.isActive == true) return
        if (silent && !force && now - (refreshedAt[mode] ?: Long.MIN_VALUE / 2) < 60_000) return
        jobs.remove(mode)?.cancel()
        accessSignatures[mode] = signature
        if (signature.none { it }) {
            if (!changedAccess && state.value.loaded) return
            refreshedAt[mode] = now
            state.value = PhoneLibraryState(loaded = true, revision = state.value.revision + 1, message =
                if (mode == AppMode.MUSIC) "Разрешите доступ к музыке телефона" else "Разрешите доступ к фото и видео телефона")
            viewModelScope.launch { phoneCache.clear(mode) }
            return
        }
        // Routine updates are strictly internal: keep the validated snapshot and
        // avoid publishing even a loading flag that the gallery UI could display.
        state.value = if (blocking) PhoneLibraryState(loading = true, requiresValidation = !(coldStart && !partial), revision = state.value.revision + 1)
            else state.value.copy(loading = false, requiresValidation = false, message = null)
        jobs[mode] = viewModelScope.launch {
            var revision = state.value.revision
            try {
                val keys = privateKeys.filterNotNull().first()
                val privacy = PhoneLibraryCache.privacyTag(keys)
                fun stillAllowed(): Boolean = currentSignature() == signature && privateKeys.value == keys
                var stamp = phone.storeStamp()
                if (!stillAllowed()) { refresh(mode, force = true, invalidate = true); return@launch }
                if (coldStart && !partial) {
                    val cached = phoneCache.read(mode, accessKey, privacy, stamp)
                    if (!stillAllowed()) { refresh(mode, force = true, invalidate = true); return@launch }
                    if (cached != null) {
                        blocking = false
                        revision++
                        state.value = PhoneLibraryState(items = cached.snapshot.items, identities = cached.snapshot.identities,
                            fingerprints = cached.snapshot.fingerprints, loaded = true, loading = false,
                            revision = revision, requiresValidation = false)
                        storeStamps[mode] = cached.stamp
                    }
                }
                val previousStamp = storeStamps[mode]
                if (!blocking && previousStamp?.versions != null && previousStamp.versions != stamp.versions) {
                    blocking = true
                    state.value = PhoneLibraryState(loading = true, revision = ++revision)
                }
                if (!blocking && stamp.generation != null && previousStamp == stamp) {
                    state.value = state.value.copy(loading = false)
                    refreshedAt[mode] = android.os.SystemClock.elapsedRealtime()
                    return@launch
                }
                if (blocking) state.value = PhoneLibraryState(loading = true, revision = revision, requiresValidation = true)
                val previous = state.value.takeIf { !blocking && it.loaded }?.let {
                    PhoneMediaSnapshot(it.items, it.identities, it.fingerprints)
                }
                val startedAt = android.os.SystemClock.elapsedRealtime()
                com.domenota.medialoader.core.logging.AppLog.i("Library", "Phone scan started · mode=$mode · retained=${previous?.items?.size ?: 0}")
                val snapshot = phone.scanSnapshot(mode, previous, keys.filterTo(mutableSetOf()) { it.startsWith("file-stat:") }) { partialSnapshot ->
                    if (blocking && !partial && stillAllowed()) {
                        state.value = PhoneLibraryState(items = partialSnapshot.items, identities = partialSnapshot.identities,
                            fingerprints = partialSnapshot.fingerprints, loaded = true, loading = true,
                            revision = ++revision, requiresValidation = true)
                        // Save useful progress too: process death must not lose the whole first scan.
                        try { phoneCache.write(mode, accessKey, privacy, PhoneStoreStamp(stamp.versions, null), partialSnapshot) }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { /* The completed snapshot will retry persistence. */ }
                    }
                }
                com.domenota.medialoader.core.logging.AppLog.i("Library", "Phone scan finished · mode=$mode · count=${snapshot.items.size} · ms=${android.os.SystemClock.elapsedRealtime() - startedAt}")
                val after = phone.storeStamp()
                if (!stillAllowed()) { refresh(mode, force = true, invalidate = true); return@launch }
                if (stamp.versions != null && after.versions != stamp.versions)
                    throw java.io.IOException("MediaStore database changed during indexing")
                // Never label a scan with a newer generation that it may not have observed.
                stamp = PhoneStoreStamp(stamp.versions, stamp.generation.takeIf { after == stamp })
                state.value = PhoneLibraryState(items = snapshot.items, identities = snapshot.identities,
                    fingerprints = snapshot.fingerprints, loaded = true, revision = revision + 1, requiresValidation = blocking)
                storeStamps[mode] = stamp
                refreshedAt[mode] = android.os.SystemClock.elapsedRealtime()
                if (!partial) try { phoneCache.write(mode, accessKey, privacy, stamp, snapshot) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { com.domenota.medialoader.core.logging.AppLog.w("Library", "Phone metadata cache save deferred") }
            }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                val message = "Не удалось обновить файлы телефона. Проверьте доступ и повторите."
                state.value = if (!blocking && state.value.loaded) state.value.copy(loading = false, message = message)
                    else PhoneLibraryState(loaded = true, revision = revision + 1, message = message)
            }
        }
    }

    override fun onCleared() {
        getApplication<Application>().contentResolver.unregisterContentObserver(mediaObserver)
        audioPreview.close()
        super.onCleared()
    }
}
