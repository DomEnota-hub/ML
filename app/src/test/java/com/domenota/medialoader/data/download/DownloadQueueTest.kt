package com.domenota.medialoader.data.download

import com.domenota.medialoader.core.database.DownloadDao
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.DownloadTask
import com.domenota.medialoader.core.model.MediaItem
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.data.HistoryRepository
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadQueueTest {
    private class FakeDao : DownloadDao {
        private val rows = MutableStateFlow<List<DownloadEntity>>(emptyList())
        override fun observePrivate(): Flow<List<DownloadEntity>> = MutableStateFlow(emptyList())
        override suspend fun deletePrivate(id: String) { rows.update { list -> list.filterNot { it.id == id && it.isPrivate } } }
        override suspend fun setDuration(id: String, duration: Long) { rows.update { list -> list.map { if (it.id == id) it.copy(durationMs = duration) else it } } }
        override suspend fun fillMissingDurationWhenCurrent(id: String, uri: String, duration: Long): Int {
            if(duration<=0L)return 0
            var updated=0
            rows.update { list -> list.map { row ->
                if(row.id==id && row.savedUri==uri && row.state==DownloadState.COMPLETED &&
                    !row.isPrivate && !row.hidden &&
                    row.mediaType in setOf(MediaType.AUDIO,MediaType.VIDEO) &&
                    (row.durationMs==null || row.durationMs<=0)) {
                    updated++
                    row.copy(durationMs=duration)
                } else row
            } }
            return updated
        }
        override fun observeAll(): Flow<List<DownloadEntity>> = rows
        override fun observeHiddenCount(): Flow<Int> = MutableStateFlow(0)
        override fun observeHidden(): Flow<List<DownloadEntity>> = rows
        override suspend fun restoreHidden() { rows.update { list -> list.map { it.copy(hidden = false) } } }
        override suspend fun clearHidden() { rows.update { list -> list.filterNot { it.hidden } } }
        override suspend fun deleteHidden(id: String) { rows.update { list -> list.filterNot { it.id == id && it.hidden } } }
        override suspend fun deleteMissingHistoryRow(id: String) {
            rows.update { list -> list.filterNot {
                it.id == id && it.state == DownloadState.COMPLETED && !it.hidden
            } }
        }
        override suspend fun clearHistory() { rows.update { list ->
            list.filter { it.state == DownloadState.QUEUED || it.state == DownloadState.RUNNING }
        } }
        override suspend fun all(): List<DownloadEntity> = rows.value.sortedByDescending { it.createdAtEpochMillis }
        override suspend fun byId(id: String): DownloadEntity? = rows.value.firstOrNull { it.id == id }
        override suspend fun countSavedUriReferences(uri: String): Int =
            rows.value.count { it.savedUri == uri || it.privateSourceUri == uri }
        override suspend fun moveSavedUriWhenCurrent(
            id: String, fromUri: String, toUri: String, toName: String,
        ): Int {
            var changed = 0
            rows.update { list -> list.map { row ->
                if (row.id == id && row.savedUri == fromUri &&
                    !row.isPrivate && !row.hidden &&
                    row.state == DownloadState.COMPLETED) {
                    changed++
                    row.copy(savedUri = toUri, originalName = toName)
                } else row
            } }
            return changed
        }
        override suspend fun rebindVisibilityUri(id:String,fromUri:String,toUri:String,bytes:Long):Int {
            var changed=0
            rows.update { list ->
                changed=0
                list.map { row -> if(row.id==id && row.savedUri==fromUri && row.sizeBytes==bytes &&
                    row.state==DownloadState.COMPLETED && !row.isPrivate && !row.hidden) {
                    changed++; row.copy(savedUri=toUri)
                } else row }
            }
            return changed
        }
        override suspend fun rebindSavedFileWhenCurrent(id: String, fromUri: String, fromName: String,
            fromBytes: Long, toUri: String, toName: String, toBytes: Long): Int {
            var changed = 0
            rows.update { list -> list.map { row ->
                if (row.id == id && row.savedUri == fromUri && row.originalName == fromName &&
                    row.sizeBytes == fromBytes && !row.isPrivate && !row.hidden && row.state == DownloadState.COMPLETED) {
                    changed++
                    row.copy(savedUri = toUri, originalName = toName, sizeBytes = toBytes)
                } else row
            } }
            return changed
        }
        override suspend fun setMuzmoArtworkIfMissing(id: String, artworkUrl: String): Int {
            var updated = 0
            rows.update { list -> list.map { row ->
                if (row.id == id && row.providerId == "muzmo" &&
                    row.state == DownloadState.COMPLETED && row.previewUrl == null) {
                    updated++
                    row.copy(previewUrl = artworkUrl, sourceAudioArtworkUrl = artworkUrl)
                } else row
            } }
            return updated
        }
        override suspend fun updateEditedAudioWhenCurrent(
            id: String, uri: String, bytes: Long, duration: Long?,
            title: String, artist: String, album: String,
        ): Int {
            var changed = 0
            rows.update { list ->
                changed = 0
                list.map { row ->
                    if (row.id == id && row.savedUri == uri &&
                        row.state == DownloadState.COMPLETED && !row.isPrivate &&
                        !row.hidden && row.mediaType == MediaType.AUDIO) {
                        changed++
                        row.copy(sizeBytes = bytes, durationMs = duration,
                            recognizedTitle = title, recognizedArtist = artist, recognizedAlbum = album,
                            sourceAudioTitle = title, sourceAudioArtist = artist)
                    } else row
                }
            }
            return changed
        }
        override suspend fun updateRecognitionProvenanceWhenCurrent(id:String,uri:String,trackId:String?,source:String,artworkUrl:String?):Int{
            var changed=0
            rows.update{list->
                changed=0
                list.map{row->
                    if(row.id==id&&row.savedUri==uri&&!row.isPrivate&&!row.hidden){
                        changed++
                        row.copy(recognitionTrackId=trackId?:row.recognitionTrackId,recognitionSource=source,
                            recognitionArtworkUrl=artworkUrl?:row.recognitionArtworkUrl)
                    }else row
                }
            }
            return changed
        }
        override suspend fun insertRecovered(item: DownloadEntity) {
            rows.update { list ->
                check(list.none { it.id == item.id }) { "Duplicate recovered history ID" }
                list + item
            }
        }
        override suspend fun upsert(item: DownloadEntity) {
            rows.update { list -> list.filterNot { it.id == item.id } + item }
        }
    }

    private class FakeEngine(
        private val durationMs: Long = 50,
        private val failing: Set<String> = emptySet(),
    ) : DownloadEngine {
        val started = CopyOnWriteArrayList<String>()
        val maxActive = AtomicInteger(0)
        private val active = AtomicInteger(0)

        override suspend fun download(
            task: DownloadTask,
            onProgress: suspend (bytesDownloaded: Long, totalBytes: Long?) -> Unit,
        ): DownloadResult {
            val now = active.incrementAndGet()
            maxActive.updateAndGet { maxOf(it, now) }
            started.add(task.fileName)
            try {
                delay(durationMs)
                if (task.fileName in failing) throw DownloadFailure("boom")
                return DownloadResult("content://saved/${task.fileName}", 10L)
            } finally {
                active.decrementAndGet()
            }
        }
    }

    private fun item(
        name: String,
        type: MediaType = MediaType.PHOTO,
        size: Long? = null,
        sourcePageUrl: String? = null,
        providerId: String = "instagram",
    ) = MediaItem(
        id = name, providerId = providerId, type = type, originalName = name,
        downloadUrl = "https://scontent.fbcdn.net/$name", sizeBytes = size,
        sourcePageUrl = sourcePageUrl,
    )

    private suspend fun FakeDao.waitUntil(condition: (List<DownloadEntity>) -> Boolean) {
        withTimeout(5_000) { while (!condition(all())) delay(10) }
    }

    private fun withQueue(
        engine: DownloadEngine,
        existing: Set<String> = emptySet(),
        clock: () -> Long = System::currentTimeMillis,
        destinationFor: (MediaItem) -> String? = { null },
        networkWait: (MediaItem, Boolean) -> DownloadNetworkWait? = { _, _ -> null },
        networkUpdates: Flow<Unit> = kotlinx.coroutines.flow.emptyFlow(),
        initialRows: List<DownloadEntity> = emptyList(),
        block: suspend (FakeDao, DownloadQueue) -> Unit,
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            runBlocking {
                val dao = FakeDao()
                initialRows.forEach { dao.upsert(it) }
                val queue = DownloadQueue(
                    HistoryRepository(dao),
                    engine,
                    { existing },
                    scope,
                    clock = clock,
                    destinationFor = destinationFor,
                    networkWait = networkWait,
                    networkUpdates = networkUpdates,
                )
                block(dao, queue)
            }
        } finally {
            scope.cancel()
        }
    }

    @Test fun networkWaitDoesNotBlockAllowedFileInSamePublication() {
        val wifi = MutableStateFlow(false)
        val policy = DownloadNetworkPolicy(DownloadNetworkMode.LARGE_WIFI, 50)
        val started = CopyOnWriteArrayList<String>()
        val engine = object : DownloadEngine {
            override suspend fun download(task: DownloadTask, onProgress: suspend (Long, Long?) -> Unit): DownloadResult {
                task.publishAfter?.await()
                started.add(task.item.id)
                return DownloadResult("content://saved/" + task.fileName, 10)
            }
        }
        withQueue(engine, networkWait = { media, force -> policy.waiting(media.sizeBytes, true, wifi.value, force) },
            networkUpdates = wifi.map { Unit }) { dao, queue ->
            val rows = queue.enqueue(listOf(item("large.mp4", MediaType.VIDEO, 60L*1024*1024), item("small.jpg", size=100)))
            dao.waitUntil { list -> list.first { it.id==rows[0].id }.networkWait==DownloadNetworkWait.WIFI &&
                list.first { it.id==rows[1].id }.state==DownloadState.COMPLETED }
            assertEquals(listOf("small.jpg"), started.toList())
            wifi.value=true
            dao.waitUntil { list -> list.all { it.state==DownloadState.COMPLETED } }
            assertEquals(listOf("small.jpg","large.mp4"), started.toList())
            assertTrue(dao.all().all { it.errorMessage==null })
        }
    }

    @Test fun downloadNowOverridesOneFileButCancellationNeverRestarts() {
        val wifi = MutableStateFlow(false)
        val policy = DownloadNetworkPolicy(DownloadNetworkMode.WIFI)
        val engine = FakeEngine()
        withQueue(engine, networkWait={ media, force -> policy.waiting(media.sizeBytes, true, wifi.value, force) },
            networkUpdates=wifi.map { Unit }) { dao, queue ->
            val rows=queue.enqueue(listOf(item("one.jpg"),item("two.jpg"),item("three.jpg")))
            dao.waitUntil { list -> list.size==3 && list.all { it.networkWait==DownloadNetworkWait.WIFI } }
            assertTrue(queue.downloadNow(rows[1].id))
            dao.waitUntil { list -> list.first { it.id==rows[1].id }.state==DownloadState.COMPLETED }
            assertEquals(listOf("two.jpg"),engine.started.toList())
            queue.cancel(rows[0].id)
            assertTrue(!queue.downloadNow(rows[0].id))
            wifi.value=true
            dao.waitUntil { list -> list.first { it.id==rows[2].id }.state==DownloadState.COMPLETED }
            assertEquals(DownloadState.CANCELLED,dao.byId(rows[0].id)?.state)
            assertEquals(listOf("two.jpg","three.jpg"),engine.started.toList())
        }
    }

    @Test fun networkIsRecheckedAfterEarlierTransferCompletes() {
        val wifi = MutableStateFlow(true)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val started=CopyOnWriteArrayList<String>()
        val engine=object:DownloadEngine {
            override suspend fun download(task:DownloadTask,onProgress:suspend(Long,Long?)->Unit):DownloadResult {
                started.add(task.item.id)
                if (task.item.id=="first.mp4") { entered.complete(Unit); gate.await() }
                return DownloadResult("content://saved/"+task.fileName,10)
            }
        }
        val policy=DownloadNetworkPolicy(DownloadNetworkMode.WIFI)
        withQueue(engine,networkWait={ media,force -> policy.waiting(media.sizeBytes,true,wifi.value,force) },
            networkUpdates=wifi.map { Unit }) { dao,queue ->
            val rows=queue.enqueue(listOf(item("first.mp4",MediaType.VIDEO),item("second.mp4",MediaType.VIDEO)))
            withTimeout(5000){entered.await()}
            wifi.value=false
            gate.complete(Unit)
            dao.waitUntil { list -> list.first { it.id==rows[0].id }.state==DownloadState.COMPLETED &&
                list.first { it.id==rows[1].id }.networkWait==DownloadNetworkWait.WIFI }
            assertEquals(listOf("first.mp4"),started.toList())
            wifi.value=true
            dao.waitUntil { list -> list.all { it.state==DownloadState.COMPLETED } }
            assertEquals(listOf("first.mp4","second.mp4"),started.toList())
        }
    }

    @Test fun onlyExplicitDeferredRowRestartsAfterProcessDeath() {
        val wifi=MutableStateFlow(false)
        val policy=DownloadNetworkPolicy(DownloadNetworkMode.WIFI)
        val deferred=DownloadEntity("wait","instagram","wait.jpg",MediaType.PHOTO,DownloadState.QUEUED,
            null,100,1,errorMessage=DownloadNetworkWait.WIFI.marker,sourceUrl="https://scontent.fbcdn.net/wait.jpg")
        val interrupted=deferred.copy(id="interrupted",originalName="interrupted.jpg",errorMessage=null)
        val private=deferred.copy(id="private",originalName="private.jpg",isPrivate=true)
        val engine=FakeEngine()
        withQueue(engine,networkWait={ media,force -> policy.waiting(media.sizeBytes,true,wifi.value,force) },
            networkUpdates=wifi.map{Unit},initialRows=listOf(deferred,interrupted,private)){dao,_->
            dao.waitUntil { rows -> rows.filter { it.id!="wait" }.all { it.state==DownloadState.FAILED } }
            assertTrue(engine.started.isEmpty())
            wifi.value=true
            dao.waitUntil { rows -> rows.first { it.id=="wait" }.state==DownloadState.COMPLETED }
            assertEquals(listOf("wait.jpg"),engine.started.toList())
        }
    }

    @Test fun sourceDestinationIsSnapshottedAndIsolatedAcrossTransfers() {
        val seen = java.util.concurrent.ConcurrentHashMap<String, String>()
        val firstGate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val folder = java.util.concurrent.atomic.AtomicReference("Pictures/First/")
        val engine = object : DownloadEngine {
            override suspend fun download(task: DownloadTask,
                onProgress: suspend (Long, Long?) -> Unit): DownloadResult {
                val path = kotlinx.coroutines.currentCoroutineContext()[com.domenota.medialoader.data.storage.DownloadFolderOverride]?.path
                assertEquals(task.destination, path)
                seen[task.item.id] = path ?: "inherited"
                if (task.item.id == "first.jpg") {
                    entered.complete(Unit)
                    firstGate.await()
                }
                return DownloadResult("content://saved/" + task.fileName, 10L)
            }
        }
        withQueue(engine, destinationFor = { media ->
            if (media.providerId == "instagram") folder.get() else null
        }) { dao, queue ->
            queue.enqueue(listOf(item("first.jpg", size=10)))
            withTimeout(5000) { entered.await() }
            queue.enqueue(listOf(item("other.jpg", size=10, providerId="vk")))
            folder.set("Pictures/Changed/")
            queue.enqueue(listOf(item("third.jpg", size=10)))
            firstGate.complete(Unit)
            dao.waitUntil { rows -> rows.size == 3 && rows.all { it.state == DownloadState.COMPLETED } }
            assertEquals("Pictures/First/", seen["first.jpg"])
            assertEquals("inherited", seen["other.jpg"])
            assertEquals("Pictures/Changed/", seen["third.jpg"])
        }
    }

    @Test fun automaticShareRestartUsesTheSameQueueEntry() {
        val engine = FakeEngine(durationMs = 100)
        withQueue(engine) { dao, queue ->
            val media = item("photo.jpg")
            val stableId = com.domenota.medialoader.data.share.automaticShareDownloadId("request", media)
            val first = queue.enqueue(listOf(media), stableIds = listOf(stableId)).single()
            val restarted = queue.enqueue(listOf(media.copy(downloadUrl = "https://scontent.fbcdn.net/new-token")),
                stableIds = listOf(com.domenota.medialoader.data.share.automaticShareDownloadId("request", media))).single()
            assertEquals(first.id, restarted.id)
            dao.waitUntil { rows -> rows.all { it.state == DownloadState.COMPLETED } }
            assertEquals(1, dao.all().size)
            assertEquals(listOf("photo.jpg"), engine.started.toList())
            assertTrue(stableId != com.domenota.medialoader.data.share.automaticShareDownloadId("new-request", media))
        }
    }

    @Test fun videoAndItsAudioRunOneAfterAnotherInOrder() {
        val engine = FakeEngine()
        withQueue(engine) { dao, queue ->
            queue.enqueue(listOf(item("clip.mp4", MediaType.VIDEO), item("clip.mp3", MediaType.AUDIO)))
            dao.waitUntil { rows -> rows.size == 2 && rows.all { it.state == DownloadState.COMPLETED } }
            assertEquals(1, engine.maxActive.get())
            assertEquals(listOf("clip.mp4", "clip.mp3"), engine.started.toList())
            assertTrue(dao.all().all { it.savedUri != null })
        }
    }

    @Test fun manyTasksNeverOverlap() {
        val engine = FakeEngine(durationMs = 20)
        withQueue(engine) { dao, queue ->
            queue.enqueue((1..6).map { item("photo_$it.jpg") })
            dao.waitUntil { rows -> rows.size == 6 && rows.all { it.state == DownloadState.COMPLETED } }
            assertEquals(1, engine.maxActive.get())
        }
    }

    @Test fun onlyKnownSmallPhotosMayOverlapAndVideoWaits() {
        val engine = FakeEngine(durationMs = 150)
        withQueue(engine) { dao, queue ->
            val photos = (1..5).map { item("photo_$it.jpg", size = 350_000L) }
            queue.enqueue(photos + item("video.mp4", MediaType.VIDEO))
            dao.waitUntil { rows -> rows.size == 6 && rows.all { it.state == DownloadState.COMPLETED } }
            assertTrue(engine.maxActive.get() in 2..3)
            assertEquals("video.mp4", engine.started.last())
        }
    }

    @Test fun rutubeFilesDownloadTwoAtOnceAndOtherVideoWaits() {
        val engine = FakeEngine(durationMs = 150)
        withQueue(engine) { dao, queue ->
            val rutube = (1..4).map {
                item("rutube_$it.mp4", MediaType.VIDEO, providerId = "rutube")
            }
            queue.enqueue(rutube + item("ordinary.mp4", MediaType.VIDEO))
            dao.waitUntil { rows -> rows.size == 5 && rows.all { it.state == DownloadState.COMPLETED } }
            assertEquals(2, engine.maxActive.get())
            assertEquals("ordinary.mp4", engine.started.last())
        }
    }

    @Test fun rutubeBatchPublishesInSelectionOrderDespiteParallelTransfers() {
        val published = CopyOnWriteArrayList<String>()
        val engine = object : DownloadEngine {
            override suspend fun download(task: DownloadTask,
                onProgress: suspend (Long, Long?) -> Unit): DownloadResult {
                delay(if (task.fileName == "rutube_1.mp4") 170 else 20)
                task.publishAfter?.await()
                published.add(task.fileName)
                return DownloadResult("content://saved/${task.fileName}", 10L)
            }
        }
        withQueue(engine) { dao, queue ->
            queue.enqueue((1..3).map {
                item("rutube_$it.mp4", MediaType.VIDEO, providerId = "rutube")
            })
            dao.waitUntil { rows -> rows.size == 3 && rows.all { it.state == DownloadState.COMPLETED } }
            assertEquals(listOf("rutube_1.mp4", "rutube_2.mp4", "rutube_3.mp4"),
                published.toList())
        }
    }

    @Test fun cancellingOneRutubeTransferReleasesTheOther() {
        val started = CopyOnWriteArrayList<String>()
        val engine = object : DownloadEngine {
            override suspend fun download(task: DownloadTask,
                onProgress: suspend (Long, Long?) -> Unit): DownloadResult {
                started.add(task.fileName)
                if (task.fileName == "rutube_1.mp4") delay(10_000) else delay(20)
                task.publishAfter?.await()
                return DownloadResult("content://saved/${task.fileName}", 10L)
            }
        }
        withQueue(engine) { dao, queue ->
            val ids = queue.enqueue((1..2).map {
                item("rutube_$it.mp4", MediaType.VIDEO, providerId = "rutube")
            }).map { it.id }
            withTimeout(5_000) { while (started.size < 2) delay(10) }
            queue.cancel(ids[0])
            dao.waitUntil { rows ->
                rows.any { it.id == ids[0] && it.state == DownloadState.CANCELLED } &&
                    rows.any { it.id == ids[1] && it.state == DownloadState.COMPLETED }
            }
        }
    }

    @Test fun parallelPhotosPublishInSelectionOrder() {
        val started = CopyOnWriteArrayList<String>()
        val published = CopyOnWriteArrayList<String>()
        val engine = object : DownloadEngine {
            override suspend fun download(task: DownloadTask,
                onProgress: suspend (Long, Long?) -> Unit): DownloadResult {
                started.add(task.fileName)
                delay(if (task.fileName == "photo_1.jpg") 180 else 20)
                task.publishAfter?.await()
                published.add(task.fileName)
                return DownloadResult("content://saved/${task.fileName}", 10L)
            }
        }
        withQueue(engine) { dao, queue ->
            queue.enqueue((1..3).map { item("photo_$it.jpg", size = 350_000L) })
            dao.waitUntil { rows -> rows.size == 3 && rows.all { it.state == DownloadState.COMPLETED } }
            assertEquals(3, started.size)
            assertEquals(listOf("photo_1.jpg", "photo_2.jpg", "photo_3.jpg"), published.toList())
        }
    }

    @Test fun failedMiddlePhotoReleasesNextPublicationGate() {
        val published = CopyOnWriteArrayList<String>()
        val engine = object : DownloadEngine {
            override suspend fun download(
                task: DownloadTask,
                onProgress: suspend (Long, Long?) -> Unit,
            ): DownloadResult {
                delay(
                    when (task.fileName) {
                        "photo_1.jpg" -> 120
                        "photo_2.jpg" -> 20
                        else -> 10
                    },
                )
                task.publishAfter?.await()
                if (task.fileName == "photo_2.jpg") throw DownloadFailure("boom")
                published.add(task.fileName)
                return DownloadResult("content://saved/${task.fileName}", 10L)
            }
        }

        withQueue(engine) { dao, queue ->
            queue.enqueue((1..3).map { item("photo_$it.jpg", size = 350_000L) })
            dao.waitUntil { rows ->
                rows.size == 3 && rows.none {
                    it.state == DownloadState.QUEUED || it.state == DownloadState.RUNNING
                }
            }
            assertEquals(listOf("photo_1.jpg", "photo_3.jpg"), published.toList())
            assertEquals(DownloadState.FAILED, dao.all().first { it.originalName == "photo_2.jpg" }.state)
        }
    }

    @Test fun batchHistoryOrderIsStableWhenClockDoesNotAdvance() {
        withQueue(FakeEngine(), clock = { 123_456L }) { dao, queue ->
            val selected = listOf(
                item("photo_1.jpg"),
                item("photo_2.jpg"),
                item("photo_3.jpg"),
            )
            queue.enqueue(selected)
            dao.waitUntil { rows -> rows.size == 3 && rows.all { it.state == DownloadState.COMPLETED } }

            assertEquals(
                listOf("photo_1.jpg", "photo_2.jpg", "photo_3.jpg"),
                dao.all().map { it.originalName },
            )
        }
    }

    @Test fun sourcePageUrlIsStoredSeparatelyFromDirectMediaUrl() {
        withQueue(FakeEngine()) { dao, queue ->
            val pageUrl = "https://www.instagram.com/p/example/"
            val saved = queue.enqueue(listOf(item("photo.jpg", sourcePageUrl = pageUrl))).single()

            assertEquals("https://scontent.fbcdn.net/photo.jpg", saved.sourceUrl)
            assertEquals(pageUrl, saved.sourcePageUrl)

            dao.waitUntil { rows -> rows.any { it.id == saved.id && it.state == DownloadState.COMPLETED } }
            assertEquals(pageUrl, dao.byId(saved.id)?.sourcePageUrl)
        }
    }

    @Test fun existingFileGetsNextFreeSuffix() {
        withQueue(FakeEngine(), existing = setOf("clip.mp4", "clip_1.mp4")) { dao, queue ->
            val saved = queue.enqueue(listOf(item("clip.mp4", MediaType.VIDEO))).single()
            assertEquals("clip_2.mp4", saved.originalName)
            dao.waitUntil { rows -> rows.all { it.state == DownloadState.COMPLETED } }
        }
    }

    @Test fun generatedCdnNameIsReplacedBeforeItIsSaved() {
        withQueue(FakeEngine()) { dao, queue ->
            val saved = queue.enqueue(listOf(item("803925799_181200858_n.jpg"))).single()
            assertEquals("Instagram.jpg", saved.originalName)
            dao.waitUntil { rows -> rows.any { it.id == saved.id && it.state == DownloadState.COMPLETED } }
            assertEquals("Instagram.jpg", dao.byId(saved.id)?.originalName)
        }
    }

    @Test fun failedTaskShowsItsMessageAndDoesNotStopTheQueue() {
        withQueue(FakeEngine(failing = setOf("bad.jpg"))) { dao, queue ->
            val ids = queue.enqueue(listOf(item("bad.jpg"), item("good.jpg"))).map { it.id }
            dao.waitUntil { rows -> rows.size == 2 && rows.none { it.state == DownloadState.QUEUED || it.state == DownloadState.RUNNING } }
            val bad = dao.byId(ids[0])!!
            assertEquals(DownloadState.FAILED, bad.state)
            assertEquals("boom", bad.errorMessage)
            assertEquals(DownloadState.COMPLETED, dao.byId(ids[1])!!.state)
        }
    }

    @Test fun retryKeepsPhotoInOriginalGroup() {
        val attempts = AtomicInteger(0)
        val engine = object : DownloadEngine {
            override suspend fun download(task: DownloadTask,
                onProgress: suspend (Long, Long?) -> Unit): DownloadResult {
                if (task.fileName == "photo_2.jpg" && attempts.getAndIncrement() == 0)
                    throw DownloadFailure("temporary")
                return DownloadResult("content://saved/${task.fileName}", 10L)
            }
        }
        withQueue(engine) { dao, queue ->
            val first = queue.enqueue(listOf(item("photo_1.jpg"), item("photo_2.jpg"), item("photo_3.jpg")))
            dao.waitUntil { rows -> rows.size == 3 && rows.none {
                it.state == DownloadState.QUEUED || it.state == DownloadState.RUNNING
            } }
            val failed = dao.byId(first[1].id)!!
            assertEquals(DownloadState.FAILED, failed.state)
            queue.retry(failed)
            dao.waitUntil { rows -> rows.size == 3 && rows.all { it.state == DownloadState.COMPLETED } }
            assertEquals(first[0].groupId, dao.byId(failed.id)?.groupId)
            assertEquals(3, dao.all().size)
        }
    }

    @Test fun waitingTaskCanBeCancelledAndIsNeverStarted() {
        val engine = FakeEngine(durationMs = 300)
        withQueue(engine) { dao, queue ->
            val ids = queue.enqueue(listOf(item("first.jpg"), item("second.jpg"))).map { it.id }
            dao.waitUntil { rows -> rows.any { it.id == ids[0] && it.state == DownloadState.RUNNING } }
            queue.cancel(ids[1])
            dao.waitUntil { rows -> rows.any { it.id == ids[0] && it.state == DownloadState.COMPLETED } }
            delay(100)
            assertEquals(DownloadState.CANCELLED, dao.byId(ids[1])!!.state)
            assertEquals(listOf("first.jpg"), engine.started.toList())
        }
    }

    @Test fun sourceChangeCancelsOnlyWaitingTasksForPreviousSource() {
        val engine = FakeEngine(durationMs = 10_000)
        withQueue(engine) { dao, queue ->
            val queued = queue.enqueue(
                listOf(
                    item("active-hitmoz.mp4", MediaType.VIDEO, providerId = "hitmoz"),
                    item("waiting-hitmoz.mp3", MediaType.AUDIO, providerId = "hitmoz"),
                    item("waiting-muzmo.mp3", MediaType.AUDIO, providerId = "muzmo"),
                ),
            )
            dao.waitUntil { rows -> rows.any { it.id == queued[0].id && it.state == DownloadState.RUNNING } }

            assertEquals(1, queue.cancelQueuedForProvider("hitmoz", "source changed"))

            // The active transfer must be left alone by a preference change.
            assertEquals(DownloadState.RUNNING, dao.byId(queued[0].id)!!.state)
            assertEquals(DownloadState.CANCELLED, dao.byId(queued[1].id)!!.state)
            assertEquals(DownloadState.QUEUED, dao.byId(queued[2].id)!!.state)
        }
    }

    @Test fun runningTaskCanBeCancelledAndNextOneStarts() {
        val engine = FakeEngine(durationMs = 10_000)
        withQueue(engine) { dao, queue ->
            val ids = queue.enqueue(listOf(item("long.jpg"), item("next.jpg"))).map { it.id }
            dao.waitUntil { rows -> rows.any { it.id == ids[0] && it.state == DownloadState.RUNNING } }
            // RUNNING is saved before the engine starts; cancel an actual transfer.
            withTimeout(5_000) { while (engine.started.isEmpty()) delay(10) }
            queue.cancel(ids[0])
            dao.waitUntil { rows -> rows.any { it.id == ids[0] && it.state == DownloadState.CANCELLED } }
            dao.waitUntil { rows -> rows.any { it.id == ids[1] && it.state == DownloadState.RUNNING } }
            // RUNNING is persisted before the worker enters the engine; wait for that handoff.
            withTimeout(5_000) { while (engine.started.size < 2) delay(10) }
            assertEquals(listOf("long.jpg", "next.jpg"), engine.started.toList())
        }
    }
    @Test fun restoreUsesOriginalIdentityWithoutPublishingANewHistoryRow() {
        withQueue(FakeEngine(durationMs = 0)) { dao, queue ->
            val original = DownloadEntity("stable", "instagram", "photo.jpg", MediaType.PHOTO,
                DownloadState.COMPLETED, "content://missing", 5, 123, groupId = "carousel")
            dao.upsert(original)
            queue.restoreMissing(original, item("photo.jpg")) { result ->
                assertEquals(listOf(original), dao.all())
                assertEquals("content://saved/photo.jpg", result.savedUri)
                HistoryRepository(dao).restoreMissingFile(original, result.savedUri, "photo.jpg", 10)
            }
            assertEquals(listOf(original.copy(savedUri = "content://saved/photo.jpg", sizeBytes = 10)), dao.all())
        }
    }

    @Test fun failedRestoreKeepsOriginalCompletedMissingRowForAnotherAttempt() {
        withQueue(FakeEngine(durationMs = 0, failing = setOf("photo.jpg"))) { dao, queue ->
            val original = DownloadEntity("stable", "instagram", "photo.jpg", MediaType.PHOTO,
                DownloadState.COMPLETED, "content://missing", 5, 123, groupId = "carousel")
            dao.upsert(original)
            repeat(2) {
                val failure = runCatching {
                    queue.restoreMissing(original, item("photo.jpg")) { error("No failed file may commit") }
                }.exceptionOrNull()
                assertTrue(failure is DownloadFailure)
                assertEquals(listOf(original), dao.all())
            }
        }
    }

    @Test fun cancelledRestoreLeavesOriginalAndReleasesItsRunningSlot() {
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val engine = object : DownloadEngine {
            override suspend fun download(task: DownloadTask, onProgress: suspend (Long, Long?) -> Unit): DownloadResult {
                started.complete(Unit)
                delay(60_000)
                error("Cancelled transfer cannot complete")
            }
        }
        withQueue(engine) { dao, queue ->
            val original = DownloadEntity("stable", "instagram", "photo.jpg", MediaType.PHOTO,
                DownloadState.COMPLETED, "content://missing", 5, 123)
            dao.upsert(original)
            kotlinx.coroutines.coroutineScope {
                val recovery = launch {
                    queue.restoreMissing(original, item("photo.jpg")) { error("Cancelled file cannot commit") }
                }
                started.await()
                queue.cancel(original.id)
                recovery.join()
                assertTrue(recovery.isCancelled)
            }
            assertEquals(listOf(original), dao.all())
        }
    }

    @Test fun recoveryRetriesInterruptedTransferAndCommitsExactlyOnceWithRealProgress(){
        val calls=AtomicInteger()
        val engine=object:DownloadEngine{
            override suspend fun download(task:DownloadTask,onProgress:suspend(Long,Long?)->Unit):DownloadResult{
                onProgress(4,10)
                if(calls.incrementAndGet()==1)throw RecoveryTransferFailure("interrupted",java.net.ProtocolException("unexpected end of stream"))
                onProgress(10,10)
                return DownloadResult("content://saved/restored",10)
            }
        }
        withQueue(engine){dao,queue->
            val original=DownloadEntity("stable","instagram","photo.jpg",MediaType.PHOTO,DownloadState.COMPLETED,"content://missing",5,123)
            dao.upsert(original)
            val progress=mutableListOf<RecoveryTransferProgress>();var commits=0
            queue.restoreMissing(original,item("photo.jpg"),onProgress={progress+=it}){result->
                commits++
                assertEquals(listOf(original),dao.all())
                assertEquals("content://saved/restored",result.savedUri)
            }
            assertEquals(2,calls.get());assertEquals(1,commits)
            assertTrue(progress.any{it.attempt==1&&it.bytes==4L&&it.total==10L})
            assertTrue(progress.any{it.phase==RecoveryTransferPhase.RETRY_WAIT&&it.attempt==2})
            assertEquals(RecoveryTransferPhase.VERIFY_SAVE,progress.last().phase)
        }
    }
    @Test fun recoveryRetriesAtMostThreeAndNeverRetriesCommitOrPermanentFailure(){
        for(mode in listOf("network","permanent","commit")){
            var calls=0;var commits=0
            val engine=object:DownloadEngine{
                override suspend fun download(task:DownloadTask,onProgress:suspend(Long,Long?)->Unit):DownloadResult{
                    calls++
                    if(mode=="network")throw RecoveryTransferFailure("interrupted")
                    if(mode=="permanent")throw DownloadFailure("disk full")
                    return DownloadResult("content://saved/restored",10)
                }
            }
            withQueue(engine){dao,queue->
                val original=DownloadEntity("stable","instagram","photo.jpg",MediaType.PHOTO,DownloadState.COMPLETED,"content://missing",5,123)
                dao.upsert(original)
                val failure=runCatching{queue.restoreMissing(original,item("photo.jpg")){
                    commits++;throw RecoveryTransferFailure("commit failed")
                }}.exceptionOrNull()
                assertTrue(failure is DownloadFailure)
                assertEquals(if(mode=="network")3 else 1,calls)
                assertEquals(if(mode=="commit")1 else 0,commits)
                assertEquals(listOf(original),dao.all())
            }
        }
    }
    @Test fun recoveryRefreshUsesOnlyTheSameComponentAndRejectsAChangedCandidate(){
        for(same in listOf(true,false)){
            val seen=mutableListOf<MediaItem>()
            val engine=object:DownloadEngine{
                override suspend fun download(task:DownloadTask,onProgress:suspend(Long,Long?)->Unit):DownloadResult{
                    seen+=task.item
                    if(seen.size==1)throw RecoveryTransferFailure("expired",refreshUrl=true)
                    return DownloadResult("content://saved/restored",10)
                }
            }
            withQueue(engine){dao,queue->
                val original=DownloadEntity("stable","instagram","photo.jpg",MediaType.PHOTO,DownloadState.COMPLETED,"content://missing",5,123)
                dao.upsert(original)
                val first=item("photo.jpg",sourcePageUrl="https://www.instagram.com/p/ABCdef123/")
                val updated=first.copy(downloadUrl="https://scontent.fbcdn.net/new.jpg",id=if(same)first.id else "different")
                var commits=0
                val failure=runCatching{queue.restoreMissing(original,first,refresh={updated}){commits++}}.exceptionOrNull()
                assertEquals(if(same)2 else 1,seen.size)
                assertEquals(if(same)1 else 0,commits)
                if(same){assertEquals(null,failure);assertEquals(updated,seen.last())}
                else assertTrue(failure is MissingRecoveryFailure)
                assertEquals(listOf(original),dao.all())
            }
        }
    }
    @Test fun recoveryCancellationDuringRetryDoesNotStartAnotherTransfer(){
        var calls=0
        val engine=object:DownloadEngine{
            override suspend fun download(task:DownloadTask,onProgress:suspend(Long,Long?)->Unit):DownloadResult{
                calls++;throw RecoveryTransferFailure("interrupted")
            }
        }
        withQueue(engine){dao,queue->
            val original=DownloadEntity("stable","instagram","photo.jpg",MediaType.PHOTO,DownloadState.COMPLETED,"content://missing",5,123)
            dao.upsert(original)
            kotlinx.coroutines.coroutineScope{
                val waiting=kotlinx.coroutines.CompletableDeferred<Unit>()
                val job=launch{queue.restoreMissing(original,item("photo.jpg"),onProgress={
                    if(it.phase==RecoveryTransferPhase.RETRY_WAIT)waiting.complete(Unit)
                }){error("must not commit")}}
                waiting.await();queue.cancel(original.id);job.join()
            }
            assertEquals(1,calls);assertEquals(listOf(original),dao.all())
        }
    }
}
