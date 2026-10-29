package com.domenota.medialoader.ui

import android.app.Application
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.database.CatalogFolderEntity
import com.domenota.medialoader.core.database.CatalogAssignmentEntity
import com.domenota.medialoader.core.logging.AppLog
import com.domenota.medialoader.core.model.MediaItem
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.core.recognition.MusicLookupPolicy
import com.domenota.medialoader.data.search.FullSongSource
import com.domenota.medialoader.core.provider.ProviderException
import com.domenota.medialoader.core.recognition.MusicRecognitionMode
import com.domenota.medialoader.core.recognition.MusicRecognitionResult
import com.domenota.medialoader.core.provider.RutubeLinkParser
import com.domenota.medialoader.core.provider.VkLinkParser
import com.domenota.medialoader.core.provider.YouTubeLinkParser
import com.domenota.medialoader.core.storage.StorageNaming
import com.domenota.medialoader.data.MediaRepository
import com.domenota.medialoader.data.bulk.BulkRules
import com.domenota.medialoader.data.download.ActiveDownloadService
import com.domenota.medialoader.data.editor.VideoEditSpec
import com.domenota.medialoader.data.editor.AudioEditSpec
import com.domenota.medialoader.data.editor.PhotoEditSpec
import com.domenota.medialoader.data.editor.EditorExportCoordinator
import com.domenota.medialoader.data.editor.EditorExportState
import com.domenota.medialoader.data.usecase.AnalyzeMedia
import com.domenota.medialoader.data.usecase.QueueMedia
import com.domenota.medialoader.data.youtube.YoutubeDlAndroid
import com.domenota.medialoader.ui.model.AnalysisUiState
import com.domenota.medialoader.ui.model.defaultSelectedIds
import com.domenota.medialoader.ui.model.togglePreviewSelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RecognitionProposal(
    val downloadId: String,
    val result: MusicRecognitionResult,
)

data class DuplicateMatch(
    val itemId: String,
    val historyId: String,
    val name: String,
)

enum class DuplicateDownloadPolicy {
    CHECK,
    SKIP_EXISTING,
    ALLOW_EXISTING,
}

data class PreviewState(
    val analysis: AnalysisUiState = AnalysisUiState.IDLE,
    val url: String = "",
    val items: List<MediaItem> = emptyList(),
    val selectedIds: Set<String> = emptySet(),
    val message: String? = null,
    val enqueuing: Boolean = false,
    val fullSongOpen: Boolean = false,
    val fullSongBusy: Boolean = false,
    val fullSongArtist: String = "",
    val fullSongTitle: String = "",
    val fullSongArtworkUrl: String? = null,
    val fullSongMessage: String? = null,
    val fullSongSessionId: Long = 0L,
    val fullSongSource: FullSongSource = FullSongSource.HITMOZ,
    val duplicateMatches: List<DuplicateMatch> = emptyList(),
)

class MediaLoaderViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = MediaRepository.get(application)
    private val editorCoordinator = EditorExportCoordinator.get(application)
    private val analyzeMedia = AnalyzeMedia(repository)
    private val queueMedia = QueueMedia(repository)
    val bulkState = repository.bulk.state
    val bulkHistory = repository.bulk.history
    fun startBulk(text: String, rules: BulkRules): String? = repository.bulk.start(text, rules)
    fun stopBulkAnalysis() = repository.bulk.stopAnalysis()
    fun setBulkDisableAnalysisPause(disabled: Boolean) = repository.bulk.setDisableAnalysisPause(disabled)
    fun toggleBulk(key: String) = repository.bulk.toggle(key)
    fun discardBulkPreview() = repository.bulk.discardPreview()
    fun confirmBulk() = repository.bulk.confirm()
    fun pauseBulk() = repository.bulk.pause()
    fun resumeBulk() = repository.bulk.resume()
    fun stopBulk() = repository.bulk.stop()
    fun openBulkJob(jobId: String) = repository.bulk.openJob(jobId)
    fun selectAllBulk() = repository.bulk.setAllSelected(true)
    fun clearBulkSelection() = repository.bulk.setAllSelected(false)
    fun clearBulkHistory() = repository.bulk.clearHistory()
    fun retryBulkFailed() = repository.bulk.retryFailed()
    private var analysisJob: Job? = null
    private var fullSongJob: Job? = null
    private val fullSongGate = FullSongSessionGate()

    val editorExport: StateFlow<EditorExportState> = editorCoordinator.state

    private val _preview = MutableStateFlow(PreviewState())
    val preview: StateFlow<PreviewState> = _preview.asStateFlow()

    val downloads: StateFlow<List<DownloadEntity>> =
        repository.downloads.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val privateMoveSession = com.domenota.medialoader.data.privatevault.PrivateMoveSession(viewModelScope, repository::moveToPrivate)
    val privateMoveState = privateMoveSession.state
    fun startPrivateMove(ids: List<String>) = privateMoveSession.start(ids)
    fun consumePrivateMoveResult(revision: Long) = privateMoveSession.consume(revision)
    val privateAccess = repository.privateAccess
    val privateDownloads: StateFlow<List<DownloadEntity>> =
        repository.privateDownloads.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    suspend fun moveToPrivate(id: String) = repository.moveToPrivate(id)
    suspend fun finishPrivateMove(id: String) = repository.finishPrivateMove(id)
    suspend fun restorePrivate(id: String) = repository.restorePrivate(id)
    suspend fun deletePrivate(id: String) = repository.deletePrivate(id)
    val catalogFolders: StateFlow<List<CatalogFolderEntity>> =
        repository.catalogFolders.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val catalogAssignments: StateFlow<List<CatalogAssignmentEntity>> =
        repository.catalogAssignments.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    suspend fun createCatalogFolder(name: String): String = repository.createCatalogFolder(name)
    suspend fun renameCatalogFolder(id: String, name: String) = repository.renameCatalogFolder(id, name)
    suspend fun deleteCatalogFolder(id: String) = repository.deleteCatalogFolder(id)
    suspend fun setCatalogFolder(downloadId: String, folderId: String?) =
        repository.setDownloadFolder(downloadId, folderId)
    suspend fun setCatalogFavorite(downloadId: String, favorite: Boolean) = repository.setDownloadFavorite(downloadId, favorite)
    suspend fun hideGroupFile(id: String) = repository.hide(id, completedOnly = true)
    suspend fun toggleCatalogFavorite(downloadId: String) = repository.toggleDownloadFavorite(downloadId)
    val hiddenCount: StateFlow<Int> =
        repository.hiddenCount.stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    val hiddenItems: StateFlow<List<DownloadEntity>> =
        repository.hiddenItems.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _recognitionProposal = MutableStateFlow<RecognitionProposal?>(null)
    val recognitionProposal: StateFlow<RecognitionProposal?> = _recognitionProposal.asStateFlow()

    private val _musicRecognitionMode = MutableStateFlow(repository.musicRecognitionMode)
    val musicRecognitionMode: StateFlow<MusicRecognitionMode> = _musicRecognitionMode.asStateFlow()
    private val _renameRecognizedTracks = MutableStateFlow(repository.renameRecognizedTracks)
    val renameRecognizedTracks: StateFlow<Boolean> = _renameRecognizedTracks.asStateFlow()

    private val _ytDlpUpdate = MutableStateFlow(YoutubeDlAndroid(application).lastUpdateStatus)
    val ytDlpUpdate: StateFlow<String?> = _ytDlpUpdate.asStateFlow()
    private val _ytDlpUpdating = MutableStateFlow(false)
    val ytDlpUpdating: StateFlow<Boolean> = _ytDlpUpdating.asStateFlow()

    private val _youTubeCookies = MutableStateFlow(repository.hasYouTubeCookies)
    val youTubeCookies: StateFlow<Boolean> = _youTubeCookies.asStateFlow()
    private val _youTubeSignedIn = MutableStateFlow(repository.hasYouTubeCookies)
    val youTubeSignedIn: StateFlow<Boolean> = _youTubeSignedIn.asStateFlow()
    private val _vkSignedIn = MutableStateFlow(repository.hasVkCookies)
    val vkSignedIn: StateFlow<Boolean> = _vkSignedIn.asStateFlow()
    private val _rutubeSignedIn = MutableStateFlow(repository.hasRutubeSession)
    val rutubeSignedIn: StateFlow<Boolean> = _rutubeSignedIn.asStateFlow()

    fun setMusicRecognitionMode(mode: MusicRecognitionMode) {
        repository.setMusicRecognitionMode(mode)
        _musicRecognitionMode.value = mode
    }

    fun setRenameRecognizedTracks(enabled: Boolean) {
        repository.setRenameRecognizedTracks(enabled)
        _renameRecognizedTracks.value = enabled
    }

    fun recognizeDownload(id: String) {
        viewModelScope.launch {
            Toast.makeText(getApplication(), "Ищу название трека…", Toast.LENGTH_SHORT).show()
            val previous = downloads.value.firstOrNull { it.id == id }
            val result = try { repository.recognizeAudio(id) }
            catch (error: IllegalStateException) {
                Toast.makeText(getApplication(), error.message, Toast.LENGTH_LONG).show()
                return@launch
            }
            val different = result != null && previous?.recognizedTitle != null &&
                (!previous.recognizedTitle.equals(result.title, ignoreCase = true) ||
                    !previous.recognizedArtist.equals(result.artist, ignoreCase = true))
            if (different && result != null) {
                _recognitionProposal.value = RecognitionProposal(id, result)
                return@launch
            }
            Toast.makeText(
                getApplication(),
                if (result == null) "Трек не удалось определить" else "${result.artist} — ${result.title}",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    fun dismissRecognitionProposal() {
        _recognitionProposal.value = null
    }

    fun confirmRecognitionProposal() {
        val proposed = _recognitionProposal.value ?: return
        _recognitionProposal.value = null
        viewModelScope.launch {
            val applied = try { repository.applyRecognizedAudio(proposed.downloadId, proposed.result) }
            catch (error: IllegalStateException) {
                Toast.makeText(getApplication(), error.message, Toast.LENGTH_LONG).show()
                return@launch
            }
            Toast.makeText(
                getApplication(),
                if (applied) "Обновлено: ${proposed.result.artist} — ${proposed.result.title}"
                else "Не удалось обновить данные трека",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    fun importYouTubeCookies(uri: Uri) {
        viewModelScope.launch {
            try {
                repository.importYouTubeCookies(uri)
                _youTubeCookies.value = true
                _youTubeSignedIn.value = true
                Toast.makeText(getApplication(), "Cookies YouTube импортированы", Toast.LENGTH_SHORT).show()
            } catch (error: Exception) {
                Toast.makeText(
                    getApplication(),
                    error.message ?: "Не удалось импортировать cookies",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    fun clearYouTubeCookies() {
        repository.clearYouTubeCookies()
        _youTubeCookies.value = false
        _youTubeSignedIn.value = false
    }

    fun updateYtDlp() {
        if (_ytDlpUpdating.value) return
        if (downloads.value.any {
                it.state == com.domenota.medialoader.core.model.DownloadState.QUEUED ||
                    it.state == com.domenota.medialoader.core.model.DownloadState.RUNNING
            }) {
            _ytDlpUpdate.value = "Дождитесь завершения загрузок"
            return
        }
        _ytDlpUpdating.value = true
        viewModelScope.launch {
            try {
                _ytDlpUpdate.value = repository.updateYtDlp()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _ytDlpUpdate.value = "Не удалось обновить yt-dlp: ${error.message ?: "проверьте соединение"}"
            } finally {
                _ytDlpUpdating.value = false
            }
        }
    }

    fun exportVideo(source: DownloadEntity, spec: VideoEditSpec) {
        editorCoordinator.startVideo(source, spec)
    }

    fun saveVideoFrame(source: DownloadEntity, positionMs: Long, spec: VideoEditSpec) {
        editorCoordinator.startVideoFrame(source, positionMs, spec)
    }

    fun savePreviewFrame(id: String) {
        val item = _preview.value.items.firstOrNull { it.id == id } ?: return
        viewModelScope.launch {
            try {
                repository.savePreviewFrame(item)
                Toast.makeText(getApplication(), "Кадр сохранён", Toast.LENGTH_SHORT).show()
            } catch (error: Exception) {
                AppLog.e("PreviewFrame", "Could not save frame", error)
                Toast.makeText(getApplication(), error.message ?: "Не удалось сохранить кадр",
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    suspend fun resolvePreviewPlayback(id: String): com.domenota.medialoader.core.provider.PreviewPlayback {
        val item = _preview.value.items.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Список медиа изменился. Открой предпросмотр заново.")
        return repository.previewPlayback(item)
    }

    fun extractAudioFromVideo(source: DownloadEntity, startMs: Long, endMs: Long?) {
        editorCoordinator.startAudioExtraction(source, startMs, endMs)
    }

    fun exportAudio(source: DownloadEntity, spec: AudioEditSpec) {
        editorCoordinator.startAudioEdit(source, spec)
    }

    fun exportPhoto(source: DownloadEntity, spec: PhotoEditSpec) {
        editorCoordinator.startPhotoEdit(source, spec)
    }

    fun cancelVideoExport() {
        editorCoordinator.cancel()
    }

    fun clearEditorMessage() {
        editorCoordinator.clearMessage()
    }

    fun consumeEditorCompletion() {
        editorCoordinator.consumeCompletion()
    }

    fun trashedUri(id: String): String? = repository.trashedUri(id)

    fun savedFileStatus(savedUri: String?) = repository.savedFileStatus(savedUri)

    private val _signedIn = MutableStateFlow(repository.isLoggedIn())
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()
    private val _instagramSessionRevision = MutableStateFlow(0L)
    val instagramSessionRevision: StateFlow<Long> = _instagramSessionRevision.asStateFlow()

    /** Folder text for Settings; comes from StorageManager, not from the UI. */
    private val _downloadFolder = MutableStateFlow(repository.storageDisplayPath)
    val downloadFolder: StateFlow<String> = _downloadFolder.asStateFlow()

    fun changeFolder(name: String) {
        runCatching { repository.setDownloadFolder(name) }
            .onSuccess { _downloadFolder.value = repository.storageDisplayPath }
    }

    fun selectFolder(uri: Uri) {
        runCatching { repository.selectDownloadFolder(uri) }
            .onSuccess { _downloadFolder.value = repository.storageDisplayPath }
            .onFailure {
                Toast.makeText(getApplication(), "Не удалось выбрать папку", Toast.LENGTH_SHORT).show()
            }
    }

    fun needsLegacyStoragePermission(): Boolean = !repository.usesSelectedFolder

    /** Public access first; providers fall back to their saved sessions only when needed. */
    fun analyze(url: String) {
        val link = url.trim()
        analysisJob?.cancel()
        fullSongGate.close()
        fullSongJob?.cancel()
        fullSongJob = null
        _preview.value = PreviewState(analysis = AnalysisUiState.ANALYZING, url = link)
        analysisJob = viewModelScope.launch {
            try {
                val items = analyzeMedia(link)
                if (items.any { it.providerId == "instagram" }) {
                    _instagramSessionRevision.value++
                }
                _preview.value = PreviewState(
                    analysis = AnalysisUiState.PREVIEW_READY,
                    url = link,
                    items = items,
                    selectedIds = items.defaultSelectedIds(),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: ProviderException) {
                _signedIn.value = repository.isLoggedIn()
                _youTubeSignedIn.value = repository.hasYouTubeCookies
                _youTubeCookies.value = repository.hasYouTubeCookies
                _vkSignedIn.value = repository.hasVkCookies
                _rutubeSignedIn.value = repository.hasRutubeSession
                _preview.value = PreviewState(
                    analysis = if (error.reason == ProviderException.Reason.ACCESS_REQUIRED) {
                        AnalysisUiState.ACCESS_REQUIRED
                    } else {
                        AnalysisUiState.ERROR
                    },
                    url = link,
                    message = when (error.reason) {
                        ProviderException.Reason.ACCESS_REQUIRED -> error.message ?: "Требуется вход."
                        ProviderException.Reason.TEMPORARY_FAILURE -> error.message ?: "Нет соединения. Повторите."
                        ProviderException.Reason.UNSUPPORTED -> error.message ?: "Медиа недоступно или ссылка не поддерживается."
                        ProviderException.Reason.RATE_LIMITED -> error.message ?: "Instagram ограничил запросы. Попробуйте позже."
                    },
                )
            } catch (_: Exception) {
                _preview.value = PreviewState(
                    analysis = AnalysisUiState.ERROR,
                    url = link,
                    message = "Не удалось проверить ссылку. Попробуйте ещё раз.",
                )
            }
        }
    }

    /**
     * Recognition may populate the launch snapshot exactly once, BEFORE the
     * search dialog is shown. Subsequent results are never allowed to change it.
     */
    fun findFullSong() {
        val state = _preview.value
        if (state.analysis != AnalysisUiState.PREVIEW_READY || state.fullSongOpen) return
        val provider = state.items.firstOrNull()?.providerId ?: return
        if (!MusicLookupPolicy.supports(provider)) return
        val items = state.items.filter { it.providerId == provider }
        if (items.isEmpty()) return
        // Capture this setting at the user action, not during later Compose rendering.
        // It is immutable for the lifetime of this full-song session.
        val sourcePrefs = getApplication<Application>().getSharedPreferences("ui", 0)
        val source = FullSongSource.fromStored(
            sourcePrefs.getString(FullSongSource.PREF_KEY, null)
        )
        val sessionId = fullSongGate.open()
        AppLog.i("MusicSource", "Full-song request accepted · session=" + sessionId +
            " · adapter=" + source.storedValue)
        _preview.update {
            it.copy(fullSongOpen = true, fullSongBusy = true,
                fullSongSessionId = sessionId,
                fullSongSource = source,
                fullSongArtist = "", fullSongTitle = "", fullSongArtworkUrl = null, fullSongMessage = null)
        }
        fullSongJob = viewModelScope.launch {
            try {
                val result = repository.recognizePreviewMusic(items)
                if (!fullSongGate.acceptRecognition(sessionId)) {
                    AppLog.verbose("Recognition", "Full song result ignored · reason=stale-session")
                    return@launch
                }
                _preview.update {
                    if (!it.fullSongOpen || !it.fullSongBusy || it.fullSongSessionId != sessionId) it
                    else it.copy(fullSongBusy = false, fullSongArtist = result?.artist.orEmpty(),
                        fullSongTitle = result?.title.orEmpty(),
                        fullSongArtworkUrl = result?.artworkUrl,
                        fullSongMessage = if (result == null)
                            "Композицию определить не удалось. Введите исполнителя и название вручную."
                        else "Трек распознан по звуку")
                }
            } catch (cancelled: CancellationException) {
                // Dismiss/analyze already own window state. A cancelled older job
                // must never close a newer full-song session.
                throw cancelled
            } catch (error: Exception) {
                AppLog.w("Recognition", "Preview music recognition failed", error)
                if (!fullSongGate.acceptRecognition(sessionId)) return@launch
                _preview.update {
                    if (!it.fullSongOpen || !it.fullSongBusy || it.fullSongSessionId != sessionId) it
                    else it.copy(fullSongBusy = false, fullSongArtist = "", fullSongTitle = "",
                        fullSongArtworkUrl = null,
                        fullSongMessage = "Не удалось распознать звук. Можно ввести название вручную.")
                }
            }
        }
    }

    fun dismissFullSong() {
        fullSongGate.close()
        fullSongJob?.cancel()
        fullSongJob = null
        _preview.update {
            it.copy(fullSongOpen = false, fullSongBusy = false, fullSongArtworkUrl = null, fullSongMessage = null)
        }
    }

    fun toggle(id: String) = _preview.update { state ->
        state.copy(selectedIds = togglePreviewSelection(state.items, state.selectedIds, id))
    }

    fun toggleAllPhotos() = _preview.update { state ->
        val photoIds = state.items.filter { it.type == MediaType.PHOTO }.map { it.id }.toSet()
        state.copy(
            selectedIds = if (photoIds.all { it in state.selectedIds }) {
                state.selectedIds - photoIds
            } else {
                state.selectedIds + photoIds
            },
        )
    }

    fun showMessage(text: String) = _preview.update { it.copy(message = text) }

    fun downloadSelected(onQueued: () -> Unit) {
        downloadSelected(emptyMap(), onQueued)
    }

    fun downloadSelected(
        names: Map<String, String>,
        onQueued: () -> Unit,
        duplicatePolicy: DuplicateDownloadPolicy = DuplicateDownloadPolicy.CHECK,
    ) {
        val state = _preview.value
        val chosen = state.items.filter { it.id in state.selectedIds }
        if (chosen.isEmpty() || state.enqueuing) return
        val renamed = try {
            chosen.map { item ->
                item.copy(
                    originalName = StorageNaming.customName(
                        names[item.id] ?: item.originalName,
                        item.originalName,
                    ),
                    customName = true,
                )
            }
        } catch (_: IllegalArgumentException) {
            showMessage("Проверьте имя файла")
            return
        }
        _preview.update { it.copy(enqueuing = true, message = null) }
        viewModelScope.launch {
            try {
                if (duplicatePolicy == DuplicateDownloadPolicy.CHECK) {
                    val matches = renamed.mapNotNull { item ->
                        repository.findDownloaded(item)?.let { existing ->
                            DuplicateMatch(item.id, existing.id, existing.originalName)
                        }
                    }
                    if (matches.isNotEmpty()) {
                        AppLog.i("Duplicate", "Existing downloads found · count=${matches.size}")
                        _preview.update {
                            it.copy(enqueuing = false, duplicateMatches = matches)
                        }
                        return@launch
                    }
                }

                val duplicateIds = _preview.value.duplicateMatches.map { it.itemId }.toSet()
                val toQueue = if (duplicatePolicy == DuplicateDownloadPolicy.SKIP_EXISTING) {
                    renamed.filterNot { it.id in duplicateIds }
                } else {
                    renamed
                }
                if (toQueue.isEmpty()) {
                    _preview.update {
                        it.copy(
                            enqueuing = false,
                            duplicateMatches = emptyList(),
                            message = "Все выбранные файлы уже скачаны.",
                        )
                    }
                    return@launch
                }

                ActiveDownloadService.start(getApplication())
                queueMedia(toQueue)
                _preview.update { it.copy(enqueuing = false, duplicateMatches = emptyList()) }
                onQueued()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                AppLog.e("Download", "Could not queue selected media", error)
                _preview.update {
                    it.copy(
                        enqueuing = false,
                        duplicateMatches = emptyList(),
                        message = "Не удалось добавить загрузку. Повторите.",
                    )
                }
            }
        }
    }

    fun dismissDuplicatePrompt() {
        _preview.update { it.copy(duplicateMatches = emptyList()) }
    }

    fun cancelDownload(id: String) {
        viewModelScope.launch { repository.cancel(id) }
    }

    fun onMusicSourceChanged(previous: FullSongSource, current: FullSongSource) {
        viewModelScope.launch {
            val cancelled = repository.onMusicSourceChanged(previous, current)
            if (cancelled > 0) {
                AppLog.i("MusicSource", "Cleared waiting music tasks · count=$cancelled")
            }
        }
    }

    fun hideDownload(id: String) {
        viewModelScope.launch {
            try {
                repository.hide(id)
            } catch (error: Exception) {
                com.domenota.medialoader.core.logging.AppLog.w(
                    "History", "Delete failed · id=$id · ${error.javaClass.simpleName}: ${error.message.orEmpty().take(160)}"
                )
                Toast.makeText(getApplication(), "Не удалось удалить файл: ${error.message ?: "проверьте доступ"}",
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    fun retryDownload(id: String) {
        viewModelScope.launch { repository.retry(id) }
    }

    fun renameDownload(id: String, name: String) {
        viewModelScope.launch {
            try {
                repository.rename(id, name)
            } catch (error: Exception) {
                com.domenota.medialoader.core.logging.AppLog.w(
                    "History", "Rename failed · id=$id · ${error.javaClass.simpleName}: ${error.message.orEmpty().take(160)}"
                )
                Toast.makeText(getApplication(), "Не удалось переименовать: ${error.message ?: "проверьте доступ"}",
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    fun restoreHidden(ids: Set<String>) {
        viewModelScope.launch {
            try {
                repository.restoreHidden(ids)
            } catch (_: Exception) {
                Toast.makeText(getApplication(), "Не удалось восстановить файл", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun deleteHidden(ids: Set<String>) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            try {
                repository.removeHidden(ids)
            } catch (error: Exception) {
                Toast.makeText(getApplication(), error.message ?: "Не удалось удалить выбранные файлы",
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    fun clearHidden() {
        viewModelScope.launch {
            try {
                repository.clearHidden()
            } catch (_: Exception) {
                Toast.makeText(getApplication(), "Не удалось очистить корзину", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun clearHistory() {
        viewModelScope.launch { repository.clearHistory() }
    }

    fun onLoginFinished(success: Boolean) {
        _signedIn.value = repository.isLoggedIn()
        val state = _preview.value
        if (success && state.analysis == AnalysisUiState.ACCESS_REQUIRED && state.url.isNotBlank() &&
            YouTubeLinkParser.parse(state.url) == null && VkLinkParser.parse(state.url) == null &&
            RutubeLinkParser.parse(state.url) == null) {
            analyze(state.url)
        }
    }

    fun onYouTubeLoginFinished(success: Boolean) {
        _youTubeSignedIn.value = repository.hasYouTubeCookies
        _youTubeCookies.value = repository.hasYouTubeCookies
        val state = _preview.value
        if (success && repository.hasYouTubeCookies && state.analysis == AnalysisUiState.ACCESS_REQUIRED &&
            state.url.isNotBlank() && YouTubeLinkParser.parse(state.url) != null) {
            analyze(state.url)
        }
    }

    fun onVkLoginFinished(success: Boolean) {
        _vkSignedIn.value = repository.hasVkCookies
        val state = _preview.value
        if (success && repository.hasVkCookies && state.analysis == AnalysisUiState.ACCESS_REQUIRED &&
            state.url.isNotBlank() && VkLinkParser.parse(state.url) != null) {
            analyze(state.url)
        }
    }

    fun onRutubeLoginFinished(success: Boolean) {
        _rutubeSignedIn.value = repository.hasRutubeSession
        val state = _preview.value
        if (success && repository.hasRutubeSession && state.analysis == AnalysisUiState.ACCESS_REQUIRED &&
            state.url.isNotBlank() && RutubeLinkParser.parse(state.url) != null) {
            analyze(state.url)
        }
    }

    fun logout() {
        repository.logout()
        _signedIn.value = false
    }

    fun refreshInstagramSession() {
        _signedIn.value = repository.isLoggedIn()
    }

    fun logoutYouTube() {
        repository.clearYouTubeCookies()
        _youTubeCookies.value = false
        _youTubeSignedIn.value = false
    }

    fun logoutVk() {
        repository.clearVkCookies()
        _vkSignedIn.value = false
    }

    fun logoutRutube() {
        repository.clearRutubeSession()
        _rutubeSignedIn.value = false
    }

    fun importSessionId(value: String): Boolean = runCatching {
        repository.importSessionId(value)
        _signedIn.value = true
        val state = _preview.value
        if (state.analysis == AnalysisUiState.ACCESS_REQUIRED && state.url.isNotBlank() &&
            YouTubeLinkParser.parse(state.url) == null && VkLinkParser.parse(state.url) == null &&
            RutubeLinkParser.parse(state.url) == null) {
            analyze(state.url)
        }
    }.isSuccess
}
