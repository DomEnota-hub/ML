package com.domenota.medialoader.data.download

import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.logging.AppLog
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.DownloadTask
import com.domenota.medialoader.core.model.MediaItem
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.core.storage.StorageNaming
import com.domenota.medialoader.data.HistoryRepository
import com.domenota.medialoader.data.tasks.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive

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
    private val destinationFor: (MediaItem) -> String? = { null },
    private val networkWait: (MediaItem, Boolean) -> DownloadNetworkWait? = { _, _ -> null },
    private val networkUpdates: Flow<Unit> = emptyFlow(),
    private val restoreWaitingAllowed: (MediaItem) -> Boolean = { true },
    private val onDeferredRestored: () -> Unit = {},
) {
    private val pending = Channel<DownloadTask>(Channel.UNLIMITED)
    private val enqueueLock = Mutex()
    private var recovered = false
    // All three maps are protected by enqueueLock. Only pending feeds the existing executor.
    private val queuedTasks = mutableMapOf<String, DownloadTask>()
    private val waiting = linkedMapOf<String, DownloadTask>()
    private val forceNetwork = mutableSetOf<String>()

    private val running = ConcurrentHashMap<String, Job>()
    private val lastProgressAt = ConcurrentHashMap<String, Long>()

    init {
        scope.launch {
            networkUpdates.collect {
                try { enqueueLock.withLock { resumeWaitingLocked() } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { AppLog.w("Download", "Could not recheck network wait", error) }
            }
        }
        scope.launch {
            enqueueLock.withLock { recoverInterruptedLocked() }
            val photos = ArrayDeque<Job>()
            val hitMozAudio = ArrayDeque<Job>()
            val rutubeFiles = ArrayDeque<Job>()
            for (task in pending) {
                try {
                    if (!enqueueLock.withLock { admitLocked(task) }) {
                        releasePublication(task)
                        continue
                    }
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
            val destination = destinationFor(item)
            val taken = withDestination(destination) { existingNames() } + history.reservedNames()
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
            submitLocked(
                DownloadTask(
                    id = entity.id,
                    item = item,
                    fileName = fileName,
                    publishAfter = previousPublication,
                    publishDone = publicationDone,
                    destination = destination,
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

    /** Restore-only transfer. The old completed row remains untouched until verified commit. */
    suspend fun restoreMissing(previous: DownloadEntity, item: MediaItem,
        onProgress:suspend (RecoveryTransferProgress)->Unit={},
        beforeRetry:suspend ()->Unit={},
        refresh:suspend ()->MediaItem?={null},
        commit: suspend (DownloadResult) -> Unit) {
        val job = kotlinx.coroutines.currentCoroutineContext()[Job] ?: error("Нет операции восстановления")
        val name = enqueueLock.withLock {
            check(history.byId(previous.id) == previous && MissingFileRecoveryPolicy.eligible(previous)) {
                "Запись изменилась. Открой восстановление заново"
            }
            check(running.putIfAbsent(previous.id, job) == null) { "Восстановление уже выполняется" }
            try { StorageNaming.availableName(previous.originalName, existingNames() + history.reservedNames()) }
            catch (error: Throwable) { running.remove(previous.id, job); throw error }
        }
        try {
            var candidate=item
            var attempt=1
            while(true){
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                onProgress(RecoveryTransferProgress(attempt))
                val result=try{
                    engine.download(DownloadTask(previous.id,candidate,name)){bytes,total->
                        onProgress(RecoveryTransferProgress(attempt,bytes,total))
                    }
                }catch(error:RecoveryTransferFailure){
                    if(attempt>=RecoveryTransferPolicy.MAX_ATTEMPTS)throw error
                    attempt++
                    onProgress(RecoveryTransferProgress(attempt,phase=RecoveryTransferPhase.RETRY_WAIT))
                    kotlinx.coroutines.delay(350L*(attempt-1))
                    check(history.byId(previous.id)==previous&&MissingFileRecoveryPolicy.eligible(previous)){
                        "Запись изменилась. Открой восстановление заново"
                    }
                    beforeRetry()
                    if(error.refreshUrl){
                        onProgress(RecoveryTransferProgress(attempt,phase=RecoveryTransferPhase.REFRESH_URL))
                        val refreshed=refresh()?:throw MissingRecoveryFailure(
                            "Не удалось подтвердить тот же файл после обновления ссылки. Исходная запись сохранена.")
                        if(!MissingFileRecoveryPolicy.sameCandidate(item,refreshed))throw MissingRecoveryFailure(
                            "Найден другой файл. Исходная запись сохранена.")
                        candidate=refreshed
                    }
                    AppLog.i("MissingRecovery","Retry transfer · attempt=$attempt · refreshed=${error.refreshUrl}")
                    continue
                }
                // A published output may already exist. Commit/verification are
                // deliberately outside the retry catch.
                onProgress(RecoveryTransferProgress(attempt,result.sizeBytes?:0,result.sizeBytes,RecoveryTransferPhase.VERIFY_SAVE))
                commit(if (result.fileName == null) result.copy(fileName = name) else result)
                break
            }
        } finally {
            running.remove(previous.id, job)
        }
    }

    /** Retry in place: keeping the row ID also keeps its original carousel group. */
    suspend fun retry(previous: DownloadEntity) = enqueueLock.withLock {
        recoverInterruptedLocked()
        val current = history.byId(previous.id) ?: return@withLock
        if (current.state != DownloadState.FAILED && current.state != DownloadState.CANCELLED) return@withLock
        val url = current.sourceUrl ?: return@withLock
        val item = MediaItem(current.id, current.providerId, current.mediaType, current.originalName, url,
            previewUrl = current.previewUrl, sizeBytes = if (current.mediaType == MediaType.AUDIO) null else current.sizeBytes,
            formatSelector = current.formatSelector, audioArtist = current.sourceAudioArtist,
            audioTitle = current.sourceAudioTitle, audioArtworkUrl = current.sourceAudioArtworkUrl,
            sourcePageUrl = current.sourcePageUrl)
        val destination = destinationFor(item)
        val name = StorageNaming.availableName(current.originalName,
            withDestination(destination) { existingNames() } + history.reservedNames())
        TaskCenter.registry.clearDownloadFailure(taskId(TaskOwner.DOWNLOADS,"file:${current.id}"))
        val queued = current.copy(originalName = name, state = DownloadState.QUEUED,
            sizeBytes = if (current.mediaType == MediaType.AUDIO) null else current.sizeBytes,
            bytesDownloaded = 0, errorMessage = null, savedUri = null, hidden = false)
        history.save(queued)
        submitLocked(DownloadTask(queued.id, item.copy(originalName = name), name, destination = destination))
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
                    forgetQueuedLocked(id)
                    history.save(entity.copy(state = DownloadState.CANCELLED, errorMessage = null))
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
            forgetQueuedLocked(id)
            history.save(entity.copy(state = DownloadState.CANCELLED, errorMessage = null))
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
            forgetQueuedLocked(entity.id)
            history.save(entity.copy(state = DownloadState.CANCELLED, errorMessage = reason))
            refreshBatch(entity)
        }
        if (queued.isNotEmpty()) {
            AppLog.i("Download", "Queued tasks cancelled for source change · provider=$providerId · count=${queued.size}")
        }
        queued.size
    }

    /** Recreate only explicitly deferred, never-started rows. Ordinary interrupted
     * transfers retain the established retry path (no blind replay of published bytes). */
    private suspend fun recoverInterruptedLocked() {
        if (recovered) return
        recovered = true
        val interrupted = history.all()
            .filter { it.state == DownloadState.QUEUED || it.state == DownloadState.RUNNING }
            .sortedByDescending { it.createdAtEpochMillis }
        var restored = 0
        for (row in interrupted) {
            val url = row.sourceUrl
            val item = if (row.networkWait != null && !row.hidden && !row.isPrivate &&
                row.savedUri == null && row.bytesDownloaded == 0L && !url.isNullOrBlank()) {
                MediaItem(row.id, row.providerId, row.mediaType, row.originalName, url,
                    previewUrl = row.previewUrl, sizeBytes = row.sizeBytes, formatSelector = row.formatSelector,
                    audioArtist = row.sourceAudioArtist, audioTitle = row.sourceAudioTitle,
                    audioArtworkUrl = row.sourceAudioArtworkUrl, sourcePageUrl = row.sourcePageUrl)
            } else null
            if (item != null && restoreWaitingAllowed(item)) {
                submitLocked(DownloadTask(row.id, item, row.originalName, destination = destinationFor(item)))
                restored++
            } else {
                if(DownloadTaskFeed.eligible(row))TaskCenter.registry.noteDownloadFailure(taskId(TaskOwner.DOWNLOADS,"file:${row.id}"),TaskFailure.INTERRUPTED,null)
                history.save(row.copy(state = DownloadState.FAILED, errorMessage = INTERRUPTED))
            }
        }
        if (restored > 0) runCatching(onDeferredRestored)
            .onFailure { AppLog.w("Download", "Foreground restore notification unavailable", it) }
        if (interrupted.isNotEmpty()) AppLog.w("Download", "Recovered interrupted tasks · count=${interrupted.size} · deferred=$restored")
    }

    private suspend fun submitLocked(task: DownloadTask) {
        queuedTasks[task.id] = task
        pending.send(task)
    }

    private fun forgetQueuedLocked(id: String) {
        val task = queuedTasks.remove(id)
        waiting.remove(id)
        forceNetwork.remove(id)
        if (task != null) releasePublication(task)
    }

    private fun releasePublication(task: DownloadTask) {
        // A skipped middle item must not let later publication leapfrog an
        // earlier transfer that is still saving. Parked tasks rejoin detached.
        val done = task.publishDone ?: return
        val before = task.publishAfter
        if (before == null || before.isCompleted) done.complete(Unit)
        else before.invokeOnCompletion { done.complete(Unit) }
    }

    /** Called both before capacity/barriers and again immediately before RUNNING. */
    private suspend fun admitLocked(task: DownloadTask): Boolean {
        if (queuedTasks[task.id] !== task) return false
        val row = history.byId(task.id)
        if (row?.state != DownloadState.QUEUED || row.hidden || row.isPrivate) {
            forgetQueuedLocked(task.id)
            return false
        }
        val reason = networkWait(task.item, task.id in forceNetwork)
        if (reason == null) return true
        val detached = task.copy(publishAfter = null, publishDone = null)
        queuedTasks[task.id] = detached
        waiting[task.id] = detached
        if (row.networkWait != reason) {
            val deferred = row.copy(errorMessage = reason.marker)
            history.save(deferred)
            refreshBatch(deferred)
        }
        return false
    }

    private suspend fun resumeWaitingLocked() {
        for ((id, task) in waiting.toList()) {
            val row = history.byId(id)
            if (queuedTasks[id] !== task || row?.state != DownloadState.QUEUED || row.hidden || row.isPrivate) {
                forgetQueuedLocked(id)
                continue
            }
            val reason = networkWait(task.item, id in forceNetwork)
            if (reason == null) {
                waiting.remove(id)
                pending.send(task)
            } else if (row.networkWait != reason) {
                val deferred = row.copy(errorMessage = reason.marker)
                history.save(deferred)
                refreshBatch(deferred)
            }
        }
    }

    /** One file, one attempt; never overrides absence of an Internet connection. */
    suspend fun downloadNow(id: String): Boolean = enqueueLock.withLock {
        recoverInterruptedLocked()
        val row = history.byId(id)
        if (row == null || row.networkWait != DownloadNetworkWait.WIFI || row.hidden || row.isPrivate || id !in queuedTasks)
            return@withLock false
        forceNetwork.add(id)
        resumeWaitingLocked()
        true
    }

    private suspend fun process(task: DownloadTask) {
        try {
            // The job is registered before RUNNING becomes visible, so a cancel can always find it.
            val job = scope.launch(start = CoroutineStart.LAZY) { transfer(task) }
            val start = enqueueLock.withLock {
                if (!admitLocked(task)) false
                else {
                    val entity = requireNotNull(history.byId(task.id))
                    queuedTasks.remove(task.id)
                    waiting.remove(task.id)
                    forceNetwork.remove(task.id)
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
            releasePublication(task)
        }
    }

    private suspend fun <T> withDestination(path: String?, action: suspend () -> T): T =
        if (path == null) action() else withContext(com.domenota.medialoader.data.storage.DownloadFolderOverride(path)) { action() }

    private suspend fun transfer(task: DownloadTask) {
        var stage=TaskStage.TRANSFER
        try {
            val startedAt = clock()
            AppLog.verbose("Download", "Engine transfer begin · id=${task.id} · provider=${task.item.providerId}")
            val result = withDestination(task.destination) {
                engine.download(task) { bytes, total -> reportProgress(task.id, bytes, total) }
            }
            AppLog.verbose("Download", "Engine transfer finished · id=${task.id} · elapsedMs=${clock() - startedAt}")
            stage=TaskStage.SAVING
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
            val row=history.byId(task.id)
            if(row!=null&&DownloadTaskFeed.eligible(row))TaskCenter.registry.noteDownloadFailure(taskId(TaskOwner.DOWNLOADS,"file:${task.id}"),TaskFailureClassifier.from(error),stage)
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
            if (changed.state == DownloadState.COMPLETED && current.state != DownloadState.COMPLETED)
                history.completeDownload(changed,clock()) else history.save(changed)
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
        const val INTERRUPTED = "Загрузка прервана. Повтори попытку."
        const val GENERIC_FAILURE = "Не удалось сохранить файл. Повтори попытку."
    }
}
