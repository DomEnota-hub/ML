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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
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
    (gallery.requiresValidation && (gallery.loading || gallery.revision != index.galleryRevision)) ||
        (music.requiresValidation && (music.loading || music.revision != index.musicRevision))
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
        rows.filter { it.state == com.domenota.medialoader.core.model.DownloadState.COMPLETED }
    }.distinctUntilChanged()
    val index = combine(completedDownloads, gallery, music, privateKeys) { app, pictures, audio, private ->
        LibraryIndexInput(app, pictures, audio, private)
    }.mapLatest { (app, pictures, audio, private) ->
        if (private == null) LibraryIndex() else withContext(Dispatchers.IO) {
            val phone = pictures.items + audio.items
            val scannedIdentities = pictures.identities + audio.identities
            val activeUris = (app + phone).mapNotNullTo(mutableSetOf()) { it.savedUri }
            identityCache.keys.retainAll(activeUris)
            suspend fun references(rows: List<DownloadEntity>, isApp: Boolean): List<LibraryMediaRef> = rows.map { row ->
                coroutineContext.ensureActive()
                val uri = row.savedUri
                val identities = if (uri == null) emptySet() else scannedIdentities[uri] ?: identityCache[uri]
                    ?: PublicMediaIdentity.capture(application, uri).also { identityCache[uri] = it }
                LibraryMediaRef(row.id, identities + row.id, row, row.id.takeIf { isApp },
                    deviceAlbumId = if (isApp) null else row.bulkMediaKey ?: row.groupId,
                    deviceAlbumName = if (isApp) null else row.groupId)
            }
            LibraryIndex(true, UnifiedMediaLibrary.merge(references(app, true), references(phone, false), private), pictures.revision, audio.revision)
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
    private val mediaObserver = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            mediaChangeJob?.cancel()
            mediaChangeJob = viewModelScope.launch {
                kotlinx.coroutines.delay(500)
                // An event during a scan must trigger another reconciliation, not be lost.
                jobs.values.toList().forEach { it.join() }
                if (galleryState.value.loaded) refresh(AppMode.GALLERY, force = true)
                if (musicState.value.loaded) refresh(AppMode.MUSIC, force = true)
            }
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
        val coldStart = accessSignatures[mode] == null && !invalidate
        val accessKey = permissions.zip(signature).filter { it.second }.map { it.first }.sorted().joinToString("|")
        val changedAccess = accessSignatures[mode] != signature
        val partial = android.os.Build.VERSION.SDK_INT >= 34 && mode == AppMode.GALLERY &&
            permissions.zip(signature).any { (name, granted) -> name == android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED && granted } &&
            permissions.zip(signature).any { (name, granted) -> name != android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED && !granted }
        val state = if (mode == AppMode.GALLERY) galleryState else musicState
        var blocking = !state.value.loaded || changedAccess || partial || invalidate
        val now = android.os.SystemClock.elapsedRealtime()
        // Multiple entry/resume effects must not cancel the initial disk restoration.
        if (!changedAccess && !invalidate && !partial && jobs[mode]?.isActive == true) return
        if (!blocking && (jobs[mode]?.isActive == true || (!force && now - (refreshedAt[mode] ?: Long.MIN_VALUE / 2) < 60_000))) return
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
        state.value = if (blocking) PhoneLibraryState(loading = true, requiresValidation = !(coldStart && !partial), revision = state.value.revision + 1)
            else state.value.copy(loading = true, requiresValidation = false, message = null)
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
                            fingerprints = cached.snapshot.fingerprints, loaded = true, loading = true,
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
                val snapshot = phone.scanSnapshot(mode, previous)
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
