package com.domenota.medialoader.data.editor

import android.content.Context
import android.net.Uri
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.logging.AppLog
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.core.storage.StorageNaming
import com.domenota.medialoader.data.MediaRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EditorExportState(
    val exporting: Boolean = false,
    val progress: Int = 0,
    val operation: String = "Экспорт видео",
    val sourceId: String? = null,
    val completedItemId: String? = null,
    val message: String? = null,
    val outputDetails: String? = null,
)

/**
 * Application-scoped editor job owner.
 *
 * The Activity/ViewModel may disappear while an export is running. The coordinator keeps the
 * Transformer coroutine alive while [MediaProcessingService] keeps the process in the foreground.
 */
class EditorExportCoordinator private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val repository = MediaRepository.get(appContext)
    private val videoEditor = VideoEditor(appContext)
    private val videoInspector = VideoExportFileInspector(appContext)
    private val videoFrameExporter = VideoFrameExporter(appContext)
    private val audioEditor = AudioEditor(appContext)
    private val photoEditor = PhotoEditor(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    private val _state = MutableStateFlow(EditorExportState())
    val state: StateFlow<EditorExportState> = _state.asStateFlow()

    fun startVideo(source: DownloadEntity, spec: VideoEditSpec) {
        if (_state.value.exporting) return
        try { repository.requireMediaAccess(source) }
        catch (error: IllegalStateException) {
            _state.value = EditorExportState(message = error.message)
            return
        }
        val savedUri = source.savedUri ?: run {
            _state.value = EditorExportState(message = "Исходный файл недоступен")
            return
        }

        _state.value = EditorExportState(
            exporting = true,
            operation = "Экспорт видео",
            sourceId = source.id,
        )
        MediaProcessingService.start(appContext)

        job = scope.launch {
            var temp: java.io.File? = null
            try {
                temp = videoEditor.export(Uri.parse(savedUri), spec) { progress ->
                    _state.update {
                        it.copy(exporting = true, progress = progress.coerceIn(0, 100))
                    }
                }
                // Do not publish a broken MP4 after apparently successful processing.
                val inspection = withContext(Dispatchers.IO) {
                    videoInspector.inspect(requireNotNull(temp), spec)
                }
                val stem = StorageNaming.editableStem(source.originalName).ifBlank { "Видео" }
                val published = repository.publishEditedFile(
                    source = source,
                    file = requireNotNull(temp),
                    preferredName = "${stem}_edit.mp4",
                    mediaType = MediaType.VIDEO,
                    mimeType = "video/mp4", operation = "Редактирование видео",
                )
                _state.value = EditorExportState(
                    exporting = false,
                    progress = 100,
                    operation = "Экспорт видео",
                    sourceId = source.id,
                    completedItemId = published.id,
                    outputDetails = inspection.summary,
                )
            } catch (cancelled: CancellationException) {
                _state.value = EditorExportState()
                throw cancelled
            } catch (error: Exception) {
                AppLog.e("Editor", "Video export failed · source=${source.id}", error)
                _state.value = EditorExportState(
                    operation = "Экспорт видео",
                    sourceId = source.id,
                    message = error.message ?: "Не удалось сохранить видео",
                )
            } finally {
                temp?.delete()
                job = null
            }
        }
    }

    fun startVideoFrame(source: DownloadEntity, positionMs: Long, spec: VideoEditSpec) {
        if (_state.value.exporting) return
        try { repository.requireMediaAccess(source) }
        catch (error: IllegalStateException) {
            _state.value = EditorExportState(message = error.message)
            return
        }
        val savedUri = source.savedUri ?: run {
            _state.value = EditorExportState(message = "Исходный файл недоступен")
            return
        }
        _state.value = EditorExportState(
            exporting = true,
            operation = "Сохранение кадра",
            sourceId = source.id,
        )
        MediaProcessingService.start(appContext)
        job = scope.launch {
            var temp: java.io.File? = null
            try {
                temp = videoFrameExporter.export(Uri.parse(savedUri), positionMs, spec)
                _state.update { it.copy(progress = 80) }
                val stem = StorageNaming.editableStem(source.originalName).ifBlank { "Видео" }
                    .take(70)
                val published = repository.publishEditedFile(
                    source = source,
                    file = requireNotNull(temp),
                    preferredName = "${stem}_frame.jpg",
                    mediaType = MediaType.PHOTO,
                    mimeType = "image/jpeg", operation = "Сохранение кадра видео",
                )
                _state.value = EditorExportState(
                    exporting = false,
                    progress = 100,
                    operation = "Сохранение кадра",
                    sourceId = source.id,
                    completedItemId = published.id,
                )
            } catch (cancelled: CancellationException) {
                _state.value = EditorExportState()
                throw cancelled
            } catch (error: Exception) {
                AppLog.e("Editor", "Video frame export failed · source=${source.id}", error)
                _state.value = EditorExportState(
                    operation = "Сохранение кадра",
                    sourceId = source.id,
                    message = error.message ?: "Не удалось сохранить кадр",
                )
            } finally {
                temp?.delete()
                job = null
            }
        }
    }

    fun startAudioExtraction(source: DownloadEntity, startMs: Long, endMs: Long?) {
        if (_state.value.exporting) return
        try { repository.requireMediaAccess(source) }
        catch (error: IllegalStateException) {
            _state.value = EditorExportState(message = error.message)
            return
        }
        val savedUri = source.savedUri ?: run {
            _state.value = EditorExportState(message = "Исходный файл недоступен")
            return
        }

        _state.value = EditorExportState(
            exporting = true,
            operation = "Извлечение аудио",
            sourceId = source.id,
        )
        MediaProcessingService.start(appContext)

        job = scope.launch {
            var extracted: java.io.File? = null
            var temp: java.io.File? = null
            try {
                extracted = videoEditor.extractAudio(
                    source = Uri.parse(savedUri),
                    startMs = startMs,
                    endMs = endMs,
                ) { progress ->
                    _state.update {
                        it.copy(exporting = true, progress = (progress / 2).coerceIn(0, 50))
                    }
                }
                _state.update { it.copy(operation = "Конвертация аудио в MP3", progress = 50) }
                temp = audioEditor.convertExtractedAudioToMp3(requireNotNull(extracted)) { progress ->
                    _state.update { it.copy(exporting = true, progress = (50 + progress / 2).coerceIn(50, 100)) }
                }
                val stem = StorageNaming.editableStem(source.originalName).ifBlank { "Аудио" }
                val published = repository.publishEditedFile(
                    source = source,
                    file = requireNotNull(temp),
                    preferredName = "${stem}_audio.mp3",
                    mediaType = MediaType.AUDIO,
                    mimeType = "audio/mpeg", operation = "Извлечение аудио из видео",
                )
                _state.value = EditorExportState(
                    exporting = false,
                    progress = 100,
                    operation = "Извлечение аудио",
                    sourceId = source.id,
                    completedItemId = published.id,
                )
            } catch (cancelled: CancellationException) {
                _state.value = EditorExportState()
                throw cancelled
            } catch (error: Exception) {
                AppLog.e("Editor", "Audio extraction failed · source=${source.id}", error)
                _state.value = EditorExportState(
                    operation = "Извлечение аудио",
                    sourceId = source.id,
                    message = error.message ?: "Не удалось извлечь аудио",
                )
            } finally {
                extracted?.delete()
                temp?.delete()
                job = null
            }
        }
    }

    fun startAudioEdit(source: DownloadEntity, spec: AudioEditSpec) {
        if (_state.value.exporting) return
        try { repository.requireMediaAccess(source) }
        catch (error: IllegalStateException) {
            _state.value = EditorExportState(message = error.message)
            return
        }
        val savedUri = source.savedUri ?: run {
            _state.value = EditorExportState(message = "Исходный файл недоступен")
            return
        }

        _state.value = EditorExportState(
            exporting = true,
            operation = if (spec.replaceOriginal) "Замена оригинала" else "Экспорт аудио",
            sourceId = source.id,
        )
        MediaProcessingService.start(appContext)

        job = scope.launch {
            var temp: java.io.File? = null
            try {
                temp = audioEditor.export(Uri.parse(savedUri), spec) { progress ->
                    _state.update {
                        val shown = if (spec.replaceOriginal)
                            (progress.coerceIn(0, 100) * 85 / 100) else progress.coerceIn(0, 100)
                        it.copy(exporting = true, progress = shown)
                    }
                }
                val published = if (spec.replaceOriginal) {
                    // The write+verify+rollback window is non-cancellable.
                    _state.update { it.copy(operation = "Запись оригинала", progress = 90) }
                    repository.replaceEditedAudioFile(
                        source = source,
                        file = requireNotNull(temp),
                        title = spec.title, artist = spec.artist, album = spec.album,
                    )
                } else {
                    val stem = StorageNaming.editableStem(source.originalName).ifBlank { "Аудио" }
                    repository.publishEditedAudioFile(
                        source = source,
                        file = requireNotNull(temp),
                        preferredName = "${stem}_edit.mp3",
                        title = spec.title, artist = spec.artist, album = spec.album,
                    )
                }
                _state.value = EditorExportState(
                    exporting = false,
                    progress = 100,
                    operation = if (spec.replaceOriginal)
                        "Оригинал обновлён" else "Экспорт аудио",
                    sourceId = source.id,
                    completedItemId = published.id,
                )
            } catch (cancelled: CancellationException) {
                _state.value = EditorExportState()
                throw cancelled
            } catch (error: Exception) {
                AppLog.e("Editor", "Audio edit failed · source=${source.id}", error)
                _state.value = EditorExportState(
                    operation = "Экспорт аудио",
                    sourceId = source.id,
                    message = error.message ?: "Не удалось сохранить аудио",
                )
            } finally {
                temp?.delete()
                job = null
            }
        }
    }

    fun startPhotoEdit(source: DownloadEntity, spec: PhotoEditSpec) {
        if (_state.value.exporting) return
        try { repository.requireMediaAccess(source) }
        catch (error: IllegalStateException) {
            _state.value = EditorExportState(message = error.message)
            return
        }
        val savedUri = source.savedUri ?: run {
            _state.value = EditorExportState(message = "Исходный файл недоступен")
            return
        }

        _state.value = EditorExportState(
            exporting = true,
            operation = "Экспорт фото",
            sourceId = source.id,
        )
        MediaProcessingService.start(appContext)

        job = scope.launch {
            var result: PhotoExportResult? = null
            try {
                result = photoEditor.export(Uri.parse(savedUri), spec) { progress ->
                    _state.update {
                        it.copy(exporting = true, progress = progress.coerceIn(0, 100))
                    }
                }
                val stem = StorageNaming.editableStem(source.originalName).ifBlank { "Фото" }
                val published = repository.publishEditedFile(
                    source = source,
                    file = result.file,
                    preferredName = "${stem}_edit.${result.extension}",
                    mediaType = MediaType.PHOTO,
                    mimeType = result.mimeType, operation = "Редактирование фото",
                )
                _state.value = EditorExportState(
                    exporting = false,
                    progress = 100,
                    operation = "Экспорт фото",
                    sourceId = source.id,
                    completedItemId = published.id,
                )
            } catch (cancelled: CancellationException) {
                _state.value = EditorExportState()
                throw cancelled
            } catch (error: Exception) {
                AppLog.e("Editor", "Photo edit failed · source=${source.id}", error)
                _state.value = EditorExportState(
                    operation = "Экспорт фото",
                    sourceId = source.id,
                    message = error.message ?: "Не удалось сохранить фото",
                )
            } finally {
                result?.file?.delete()
                job = null
            }
        }
    }

    private val _batch = MutableStateFlow(BatchPhotoState())
    val batch = _batch.asStateFlow()
    fun startBatchPhotos(sources:List<DownloadEntity>,spec:BatchPhotoSpec) {
        if (_state.value.exporting || job!=null) return
        val rows=sources.distinctBy{it.savedUri}
        if(rows.isEmpty())return
        _batch.value=BatchPhotoState(running=true,total=rows.size)
        _state.value=EditorExportState(exporting=true,operation="Пакетная обработка фото")
        try { MediaProcessingService.start(appContext) } catch(e:Exception) {
            _batch.value=BatchPhotoState(total=rows.size,outcomes=rows.map{BatchPhotoOutcome(it.id,it.originalName,e.message?:"Не удалось запустить обработку")})
            _state.value=EditorExportState(message=e.message?:"Не удалось запустить обработку")
            return
        }
        job=scope.launch {
            val editor=BatchPhotoEditor(appContext)
            val outcomes=mutableListOf<BatchPhotoOutcome>()
            var cancelled=false
            try {
                for(source in rows){
                    var result:PhotoExportResult?=null
                    try {
                        require(source.mediaType==MediaType.PHOTO && !source.hidden)
                        repository.requireMediaAccess(source)
                        val uri=source.savedUri?:error("Исходный файл недоступен")
                        result=editor.export(Uri.parse(uri),spec)
                        val stem=StorageNaming.editableStem(source.originalName).ifBlank{"Фото"}.take(70)
                        // Publishing and recording the outcome form one non-cancellable boundary.
                        withContext(kotlinx.coroutines.NonCancellable) {
                            repository.publishEditedFile(source,requireNotNull(result).file,"${stem}_batch.${requireNotNull(result).extension}",
                                MediaType.PHOTO,requireNotNull(result).mimeType,operation="Пакетная обработка фото")
                            outcomes.add(BatchPhotoOutcome(source.id,source.originalName))
                        }
                    }catch(e:CancellationException){throw e}
                    catch(e:Exception){outcomes.add(BatchPhotoOutcome(source.id,source.originalName,e.message?:"Ошибка обработки"))}
                    finally{result?.file?.delete()}
                    _batch.value=BatchPhotoState(true,rows.size,outcomes.toList())
                    _state.update{it.copy(progress=outcomes.size*100/rows.size)}
                }
            }catch(e:CancellationException){cancelled=true;throw e}
            finally{
                _batch.value=BatchPhotoState(false,rows.size,outcomes.toList(),cancelled)
                _state.value=EditorExportState(operation="Пакетная обработка фото",progress=outcomes.size*100/rows.size,
                    message="Сохранено: ${outcomes.count{it.error==null}} из ${rows.size}")
                job=null
            }
        }
    }

    fun cancel(message: String? = null) {
        if(_batch.value.running){job?.cancel();return}

        if (_state.value.exporting && _state.value.operation == "Запись оригинала") {
            _state.update { it.copy(message =
                "Идёт безопасная запись MP3 и проверка резервной копии. Дождитесь завершения.") }
            return
        }
        job?.cancel()
        job = null
        _state.value = if (message == null) EditorExportState()
        else EditorExportState(message = message)
    }

    fun clearMessage() {
        _state.update { it.copy(message = null) }
    }

    fun consumeCompletion() {
        if (!_state.value.exporting) _state.value = EditorExportState()
    }

    companion object {
        @Volatile private var instance: EditorExportCoordinator? = null

        fun get(context: Context): EditorExportCoordinator =
            instance ?: synchronized(this) {
                instance ?: EditorExportCoordinator(context).also { instance = it }
            }
    }
}
