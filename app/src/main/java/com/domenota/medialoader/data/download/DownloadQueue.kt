package com.domenota.medialoader.data.download

import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.logging.AppLog
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.DownloadTask
import com.domenota.medialoader.core.model.MediaItem
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.core.storage.StorageNaming
import com.domenota.medialoader.data.HistoryRepository
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Up to three known-small photos, two HitMoz files or two RUTUBE files may overlap.
 * Other videos, large files and audio form barriers.
 */
class DownloadQueue(
    private val history: HistoryRepository,
    private val engine: DownloadEngine,
    /** Names already present in the target folder. */
    private val existingNames: suspend () -> Set<String>,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val onBatchChanged: suspend (String, List<DownloadEntity>) -> Unit = { _, _ -> },
    private val onCompleted: (DownloadTask, DownloadResult) -> Unit = { _, _ -> },
) {
    private val pending = Channel<DownloadTask>(Channel.UNLIMITED)
    private val enqueueLock = Mutex()
    private var recovered = false

    private val running = ConcurrentHashMap<String, Job>()
    private val lastProgressAt = ConcurrentHashMap<String, Long>()

    init {
        scope.launch {
            enqueueLock.withLock { recoverInterruptedLocked() }
            val photos = ArrayDeque<Job>()
            val hitMozAudio = ArrayDeque<Job>()
            val rutubeFiles = ArrayDeque<Job>()
            for (task in pending) {
                try {
                    if (task.item.type == MediaType.PHOTO &&
                        task.item.sizeBytes != null && task.item.sizeBytes in 1..MAX_PARALLEL_PHOTO_BYTES) {
                        while (hitMozAudio.isNotEmpty()) hitMozAudio.removeFirst().join()
                        while (rutubeFiles.isNotEmpty()) rutubeFiles.removeFirst().join()
                        while (photos.size >= MAX_PHOTO_TRANSFERS) photos.removeFirst().join()
                        photos.addLast(scope.launch {
                            try { process(task) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (error: Exception) {
                                AppLog.e("Download", "Parallel photo task failed · id=${task.id}", error)
                            }
                        })
                    } else if (task.item.providerId == "hitmoz" && task.item.type == MediaType.AUDIO) {
                        while (photos.isNotEmpty()) photos.removeFirst().join()
                        while (rutubeFiles.isNotEmpty()) rutubeFiles.removeFirst().join()
                        while (hitMozAudio.size >= MAX_HITMOZ_TRANSFERS) hitMozAudio.removeFirst().join()
                        hitMozAudio.addLast(scope.launch {
                            try { process(task) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (error: Exception) {
                                AppLog.e("Download", "Parallel music task failed · id=${task.id}", error)
                            }
                        })
                    } else if (task.item.providerId == "rutube") {
                        while (photos.isNotEmpty()) photos.removeFirst().join()
                        while (hitMozAudio.isNotEmpty()) hitMozAudio.removeFirst().join()
                        while (rutubeFiles.size >= MAX_RUTUBE_TRANSFERS) rutubeFiles.removeFirst().join()
                        rutubeFiles.addLast(scope.launch {
                            try { process(task) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (error: Exception) {
                                AppLog.e("Download", "Parallel RUTUBE task failed · id=${task.id}", error)
                            }
                        })
                    } else {
                        while (photos.isNotEmpty()) photos.removeFirst().join()
                        while (hitMozAudio.isNotEmpty()) hitMozAudio.removeFirst().join()
                        while (rutubeFiles.isNotEmpty()) rutubeFiles.removeFirst().join()
                        process(task)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    // A storage/database error must not stop the worker: the next task still runs.
                    AppLog.e("Download", "Queue worker error · id=${task.id}", error)
                }
            }
        }
    }

    /** Adds items in the given order. Returns the rows as saved (state QUEUED). */
    suspend fun enqueue(items: List<MediaItem>, stableIds: List<String>? = null,
        bulkMediaKeys: List<String>? = null, groupIds: List<String>? = null): List<DownloadEntity> = enqueueLock.withLock {
        require(stableIds == null || stableIds.size == items.size)
        require(bulkMediaKeys == null || bulkMediaKeys.size == items.size)
        require(groupIds == null || groupIds.size == items.size)
        recoverInterruptedLocked()
        val groupId = if (items.size > 1) newId() else null
        val batchCreatedAt = clock()
        var previousPublication: CompletableDeferred<Unit>? = null
        val queued = items.mapIndexed { index, item ->
            val stableId = stableIds?.get(index)
            if (stableId != null) {
                val existing = history.byId(stableId)
                if (existing != null) return@mapIndexed existing
            }
            val taken = existingNames() + history.reservedNames()
            val preferredName = if (item.customName) item.originalName
                else StorageNaming.normalizedMediaName(item.originalName, item.position)
            val fileName = StorageNaming.availableName(preferredName, taken)
            val entity = DownloadEntity(
                id = stableId ?: newId(),
                providerId = item.providerId,
                originalName = fileName,
                mediaType = item.type,
                state = DownloadState.QUEUED,
                savedUri = null,
                sizeBytes = item.sizeBytes,
                // Stable tie-breaker for a batch: selection order must survive identical wall-clock ticks.
                createdAtEpochMillis = batchCreatedAt - index,
                sourceUrl = item.downloadUrl,
                sourcePageUrl = item.sourcePageUrl,
                bulkMediaKey = bulkMediaKeys?.get(index),
                previewUrl = item.previewUrl,
                groupId = groupIds?.get(index) ?: groupId,
                formatSelector = item.formatSelector,
                sourceAudioArtist = item.audioArtist,
                sourceAudioTitle = item.audioTitle,
                sourceAudioArtworkUrl = item.audioArtworkUrl,
            )
            history.save(entity)
            val publicationDone = CompletableDeferred<Unit>()
            pending.send(
                DownloadTask(
                    id = entity.id,
                    item = item,
                    fileName = fileName,
                    publishAfter = previousPublication,
                    publishDone = publicationDone,
                ),
            )
            previousPublication = publicationDone
            AppLog.i(
                "Download",
                "Queued · id=${entity.id} · provider=${entity.providerId} · type=${entity.mediaType} · name=${entity.originalName}",
            )
            entity
        }
        queued.firstOrNull()?.let { refreshBatch(it) }
        queued
    }

    /** Retry in place: keeping the row ID also keeps its original carousel group. */
    suspend fun retry(previous: DownloadEntity) = enqueueLock.withLock {
        recoverInterruptedLocked()
        val current = history.byId(previous.id) ?: return@withLock
        if (current.state != DownloadState.FAILED && current.state != DownloadState.CANCELLED) return@withLock
        val url = current.sourceUrl ?: return@withLock
        val name = StorageNaming.availableName(current.originalName,
            existingNames() + history.reservedNames())
        val queued = current.copy(originalName = name, state = DownloadState.QUEUED,
            sizeBytes = if (current.mediaType == MediaType.AUDIO) null else current.sizeBytes,
            bytesDownloaded = 0, errorMessage = null, savedUri = null, hidden = false)
        history.save(queued)
        pending.send(DownloadTask(queued.id, MediaItem(queued.id, queued.providerId,
            queued.mediaType, queued.originalName, url, previewUrl = queued.previewUrl,
            sizeBytes = queued.sizeBytes, formatSelector = queued.formatSelector,
            audioArtist = queued.sourceAudioArtist, audioTitle = queued.sourceAudioTitle,
            audioArtworkUrl = queued.sourceAudioArtworkUrl,
            sourcePageUrl = queued.sourcePageUrl), name))
        AppLog.i("Download", "Retry queued · id=${queued.id} · provider=${queued.providerId} · name=${queued.originalName}")
        refreshBatch(queued)
    }

    /** Cancels a running task (stopping the transfer) or removes a waiting one from the queue. */
    suspend fun cancel(id: String) {
        enqueueLock.withLock {
            val job = running[id]
            if (job != null) {
                AppLog.i("Download", "Cancel running · id=$id")
                job.cancel()
            } else {
                val entity = history.byId(id)
                if (entity?.state == DownloadState.QUEUED) {
                    history.save(entity.copy(state = DownloadState.CANCELLED))
                    AppLog.i("Download", "Cancel queued · id=$id")
                    refreshBatch(entity)
                }
            }
        }
    }

    /** Bulk pause/stop must leave a transfer that has already started running. */
    suspend fun cancelQueued(id: String) = enqueueLock.withLock {
        val entity = history.byId(id) ?: return@withLock
        if (entity.state == DownloadState.QUEUED) {
            history.save(entity.copy(state = DownloadState.CANCELLED))
            refreshBatch(entity)
        }
    }

    /**
     * Cancels waiting work for one provider only. A transfer that has moved to RUNNING
     * deliberately stays untouched: a source preference is not a user cancellation.
     */
    suspend fun cancelQueuedForProvider(providerId: String, reason: String): Int = enqueueLock.withLock {
        val queued = history.all().filter {
            it.providerId == providerId && it.state == DownloadState.QUEUED
        }
        queued.forEach { entity ->
            history.save(entity.copy(state = DownloadState.CANCELLED, errorMessage = reason))
            refreshBatch(entity)
        }
        if (queued.isNotEmpty()) {
            AppLog.i("Download", "Queued tasks cancelled for source change · provider=$providerId · count=${queued.size}")
        }
        queued.size
    }

    /** Rows left QUEUED/RUNNING by a previous process have no worker any more. */
    private suspend fun recoverInterruptedLocked() {
        if (recovered) return
        recovered = true
        val interrupted = history.all()
            .filter { it.state == DownloadState.QUEUED || it.state == DownloadState.RUNNING }
        interrupted.forEach { history.save(it.copy(state = DownloadState.FAILED, errorMessage = INTERRUPTED)) }
        if (interrupted.isNotEmpty()) AppLog.w("Download", "Recovered interrupted tasks · count=${interrupted.size}")
    }

    private suspend fun process(task: DownloadTask) {
        try {
            // The job is registered before RUNNING becomes visible, so a cancel can always find it.
            val job = scope.launch(start = CoroutineStart.LAZY) { transfer(task) }
            val start = enqueueLock.withLock {
                val entity = history.byId(task.id)
                if (entity?.state != DownloadState.QUEUED) false
                else {
                    running[task.id] = job
                    history.save(entity.copy(state = DownloadState.RUNNING, errorMessage = null))
                    AppLog.i("Download", "Started · id=${task.id} · provider=${entity.providerId} · name=${entity.originalName}")
                    refreshBatch(entity)
                    true
                }
            }
            if (!start) {
                job.cancel()
                return
            }
            try {
                job.start()
                job.join()
            } finally {
                running.remove(task.id, job)
                lastProgressAt.remove(task.id)
            }
            // A job cancelled before it started never reaches transfer(): do not leave the row RUNNING.
            if (job.isCancelled) {
                update(task.id) {
                    if (it.state == DownloadState.RUNNING) it.copy(state = DownloadState.CANCELLED) else it
                }
            }
        } finally {
            // Always release the next item in the same batch, even after failure/cancellation.
            task.publishDone?.complete(Unit)
        }
    }

    private suspend fun transfer(task: DownloadTask) {
        try {
            val startedAt = clock()
            AppLog.verbose("Download", "Engine transfer begin · id=${task.id} · provider=${task.item.providerId}")
            val result = engine.download(task) { bytes, total -> reportProgress(task.id, bytes, total) }
            AppLog.verbose("Download", "Engine transfer finished · id=${task.id} · elapsedMs=${clock() - startedAt}")
            update(task.id) {
                it.copy(
                    state = DownloadState.COMPLETED,
                    savedUri = result.savedUri,
                    originalName = result.fileName ?: it.originalName,
                    sizeBytes = result.sizeBytes ?: it.sizeBytes,
                    bytesDownloaded = result.sizeBytes ?: it.bytesDownloaded,
                    errorMessage = null,
                )
            }
            AppLog.ok("Download", "Completed · id=${task.id} · bytes=${result.sizeBytes ?: -1}")
            runCatching { onCompleted(task, result) }
                .onFailure { AppLog.w("Download", "Completion hook failed · id=${task.id}", it) }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { update(task.id) { it.copy(state = DownloadState.CANCELLED) } }
            AppLog.i("Download", "Cancelled · id=${task.id}")
            throw cancelled
        } catch (error: Exception) {
            val message = (error as? DownloadFailure)?.message ?: GENERIC_FAILURE
            AppLog.e("Download", "Failed · id=${task.id} · message=$message", error)
            update(task.id) { it.copy(state = DownloadState.FAILED, errorMessage = message) }
        }
    }

    private suspend fun reportProgress(id: String, bytes: Long, total: Long?) {
        val now = clock()
        if (now - (lastProgressAt[id] ?: 0L) < PROGRESS_INTERVAL_MS) return
        lastProgressAt[id] = now
        AppLog.verbose("DownloadProgress", "id=$id · bytes=$bytes · total=${total ?: -1}")
        update(id) { if (it.state == DownloadState.RUNNING) it.copy(bytesDownloaded = bytes, sizeBytes = total ?: it.sizeBytes) else it }
    }

    private suspend fun update(id: String, change: (DownloadEntity) -> DownloadEntity) {
        val current = history.byId(id) ?: return
        val changed = change(current)
        if (changed != current) {
            history.save(changed)
            if (changed.state != current.state) refreshBatch(changed)
        }
    }

    private suspend fun refreshBatch(item: DownloadEntity) {
        val key = item.groupId ?: item.id
        val rows = history.all().filter { (it.groupId ?: it.id) == key && !it.hidden }
        runCatching { onBatchChanged(key, rows) }
    }

    private companion object {
        const val PROGRESS_INTERVAL_MS = 500L
        const val MAX_PARALLEL_PHOTO_BYTES = 5L * 1024 * 1024
        const val MAX_PHOTO_TRANSFERS = 3
        const val MAX_HITMOZ_TRANSFERS = 2
        const val MAX_RUTUBE_TRANSFERS = 2
        const val INTERRUPTED = "Загрузка прервана. Повторите."
        const val GENERIC_FAILURE = "Не удалось сохранить файл. Повторите попытку."
    }
}
