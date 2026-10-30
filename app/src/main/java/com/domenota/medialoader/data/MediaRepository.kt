package com.domenota.medialoader.data

import com.domenota.medialoader.core.provider.PreviewPlayback
import com.domenota.medialoader.core.provider.PreviewTrack
import com.domenota.medialoader.core.provider.PreviewStreams
import com.domenota.medialoader.core.provider.PreviewStreamExtractor

import com.domenota.medialoader.data.privatevault.PrivateAccessManager
import com.domenota.medialoader.data.privatevault.PrivateFileStore
import com.domenota.medialoader.data.privatevault.PrivateMediaOperations
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.combine
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.database.HistoryDatabase
import com.domenota.medialoader.core.model.MediaItem
import com.domenota.medialoader.core.recognition.MusicRecognitionResult
import com.domenota.medialoader.core.recognition.MusicLookupPolicy
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.recognition.MusicRecognitionMode
import com.domenota.medialoader.core.provider.DefaultMediaResolver
import com.domenota.medialoader.core.provider.InstagramProvider
import com.domenota.medialoader.core.provider.MediaResolver
import com.domenota.medialoader.core.provider.ProviderException
import com.domenota.medialoader.core.provider.RutubeProvider
import com.domenota.medialoader.core.provider.VkProvider
import com.domenota.medialoader.core.provider.YouTubeProvider
import com.domenota.medialoader.data.download.AudioDownloadEngine
import com.domenota.medialoader.data.bulk.BulkCoordinator
import com.domenota.medialoader.data.bulk.BulkIdentity
import com.domenota.medialoader.data.download.AudioExtractor
import com.domenota.medialoader.data.download.DownloadQueue
import com.domenota.medialoader.data.download.DownloadNotifier
import com.domenota.medialoader.data.download.RoutingDownloadEngine
import com.domenota.medialoader.data.download.RutubeDownloadEngine
import com.domenota.medialoader.data.download.HttpDownloadEngine
import com.domenota.medialoader.data.download.HitMozDownloadEngine
import com.domenota.medialoader.data.download.HitMozDownloadUrls
import com.domenota.medialoader.data.download.MuzmoDownloadUrls
import com.domenota.medialoader.data.download.MuzmoDownloadEngine
import com.domenota.medialoader.data.search.HitMozSearchClient
import com.domenota.medialoader.data.search.FullSongSource
import com.domenota.medialoader.data.search.MusicSourcePolicy
import com.domenota.medialoader.data.search.MusicArtworkLookup
import java.util.UUID
import com.domenota.medialoader.data.download.mediaHeaders
import com.domenota.medialoader.data.download.ActiveDownloadService
import com.domenota.medialoader.data.download.VkDownloadEngine
import com.domenota.medialoader.data.download.YouTubeDownloadEngine
import com.domenota.medialoader.data.catalog.DownloadCatalogRepository
import com.domenota.medialoader.data.recognition.InstagramMusicRecognizer
import com.domenota.medialoader.data.recognition.Mp3MetadataWriter
import com.domenota.medialoader.data.recognition.MusicPostProcessor
import com.domenota.medialoader.data.recognition.MusicRecognitionSettings
import com.domenota.medialoader.data.recognition.MicrophoneCapture
import com.domenota.medialoader.data.recognition.ShazamRecognitionProvider
import com.domenota.medialoader.data.recognition.RecognitionAudioPreprocessor
import com.domenota.medialoader.data.rutube.RutubeYtDlpRuntime
import com.domenota.medialoader.data.vk.VkYtDlpRuntime
import com.domenota.medialoader.data.youtube.YoutubeDlAndroid
import com.domenota.medialoader.core.storage.StorageNaming
import com.domenota.medialoader.core.logging.AppLog
import com.domenota.medialoader.data.storage.SavedFilePresence
import com.domenota.medialoader.data.storage.SavedFileStatus
import com.domenota.medialoader.data.storage.StorageManager
import com.domenota.medialoader.data.storage.StorageSettings
import com.domenota.medialoader.ui.components.loadAudioWaveform
import com.domenota.medialoader.data.playback.BackgroundMediaWork
import com.domenota.medialoader.data.playback.PublicMediaIdentity
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.domenota.medialoader.core.model.PreferredVideoQuality
import com.domenota.medialoader.data.preferences.VideoPreferences
import com.domenota.medialoader.data.preview.RemoteImageLoader
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * The single top-level coordinator. It owns no logic of its own: it wires MediaResolver,
 * DownloadQueue (with its DownloadEngine), StorageManager, HistoryRepository and SessionStore.
 * One instance per process, so the queue keeps running when the screen is recreated.
 */
class MediaRepository private constructor(context: Context) {
    private val appContext = context.applicationContext

    val privateAccess = PrivateAccessManager.get(appContext)
    private val privateFiles = PrivateFileStore(appContext)
    val sessions = SessionStore(appContext)
    private val storage = StorageManager(appContext, StorageSettings(appContext))
    private val youtubeDl = YoutubeDlAndroid(appContext)
    private val vkDl = VkYtDlpRuntime(appContext)
    private val rutubeDl = RutubeYtDlpRuntime(appContext)
    private val youtubePreviewStreams = PreviewStreamExtractor(youtubeDl)
    private val vkPreviewStreams = PreviewStreamExtractor(vkDl)
    private val rutubePreviewStreams = PreviewStreamExtractor(rutubeDl)
    private val historyDb =
        Room.databaseBuilder(appContext, HistoryDatabase::class.java, "media-loader.db")
            .addMigrations(object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE downloads ADD COLUMN sourceUrl TEXT")
                    db.execSQL("ALTER TABLE downloads ADD COLUMN hidden INTEGER NOT NULL DEFAULT 0")
                }
            }, object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE downloads ADD COLUMN previewUrl TEXT")
                }
            }, object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE downloads ADD COLUMN groupId TEXT")
                }
            }, object : Migration(4, 5) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE downloads ADD COLUMN formatSelector TEXT")
                }
            }, object : Migration(5, 6) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE downloads ADD COLUMN sourceAudioArtist TEXT")
                    db.execSQL("ALTER TABLE downloads ADD COLUMN sourceAudioTitle TEXT")
                    db.execSQL("ALTER TABLE downloads ADD COLUMN sourceAudioArtworkUrl TEXT")
                    db.execSQL("ALTER TABLE downloads ADD COLUMN recognizedArtist TEXT")
                    db.execSQL("ALTER TABLE downloads ADD COLUMN recognizedTitle TEXT")
                    db.execSQL("ALTER TABLE downloads ADD COLUMN recognizedAlbum TEXT")
                    db.execSQL("ALTER TABLE downloads ADD COLUMN recognitionTrackId TEXT")
                    db.execSQL("ALTER TABLE downloads ADD COLUMN recognitionSource TEXT")
                    db.execSQL("ALTER TABLE downloads ADD COLUMN recognitionArtworkUrl TEXT")
                }
            }, object : Migration(6, 7) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE downloads ADD COLUMN sourcePageUrl TEXT")
                    // Older YouTube/VK/RUTUBE rows already stored their canonical page URL in sourceUrl.
                    // Instagram sourceUrl is a CDN media URL, so it must not be guessed/backfilled.
                    db.execSQL(
                        "UPDATE downloads SET sourcePageUrl = sourceUrl " +
                            "WHERE providerId IN ('youtube', 'vk', 'rutube') AND sourceUrl IS NOT NULL",
                    )
                }
            }, object : Migration(7, 8) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE downloads ADD COLUMN bulkMediaKey TEXT")
                }
            }, object : Migration(8, 9) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE downloads ADD COLUMN derivedFromId TEXT")
                }
            }, object : Migration(9, 10) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE downloads ADD COLUMN durationMs INTEGER")
                }
            }, object : Migration(10, 11) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE IF NOT EXISTS `catalog_folders` (" +
                        "`id` TEXT NOT NULL, `name` TEXT NOT NULL, `nameKey` TEXT NOT NULL, " +
                        "`createdAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_catalog_folders_nameKey` " +
                        "ON `catalog_folders` (`nameKey`)")
                    db.execSQL("CREATE TABLE IF NOT EXISTS `catalog_assignments` (" +
                        "`downloadId` TEXT NOT NULL, `folderId` TEXT, `isFavorite` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`downloadId`), " +
                        "FOREIGN KEY(`folderId`) REFERENCES `catalog_folders`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE SET NULL)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_catalog_assignments_folderId` " +
                        "ON `catalog_assignments` (`folderId`)")
                }
            }, com.domenota.medialoader.core.database.PRIVATE_HISTORY_MIGRATION).build()
    private val history = HistoryRepository(historyDb.downloadDao(), historyDb.catalogDao())
    private val catalog = DownloadCatalogRepository(historyDb)
    private val resolver: MediaResolver = DefaultMediaResolver(
        listOf(
            InstagramProvider(cookies = { sessions.cookies() }),
            YouTubeProvider(youtubePreviewStreams),
            VkProvider(vkPreviewStreams),
            RutubeProvider(rutubePreviewStreams),
        ),
    )
    private val notifier = DownloadNotifier(appContext)
    private val recognitionSettings = MusicRecognitionSettings(appContext)
    private val instagramMusicRecognizer = InstagramMusicRecognizer()
    private val liveMusicRecognizer = ShazamRecognitionProvider()
    private val previewAudioExtractor = AudioExtractor(appContext, storage, sessions::cookies)
    private val recognitionAudioPreprocessor = RecognitionAudioPreprocessor(appContext)
    private val sourceCoverGate = Semaphore(2)
    private val videoPreferences = VideoPreferences(appContext)
    private val recognitionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val waveformGate = Semaphore(1)
    private val musicPostProcessGate = Semaphore(1)
    private val recognitionRetries = ConcurrentHashMap<String, Int>()
    private val _recognitionActive = MutableStateFlow(0)
    val recognitionActive: StateFlow<Int> = _recognitionActive.asStateFlow()
    private val musicPostProcessor = MusicPostProcessor(
        storage = storage,
        history = history,
        settings = recognitionSettings,
        recognizer = instagramMusicRecognizer,
        preprocessor = recognitionAudioPreprocessor,
        metadataWriter = Mp3MetadataWriter(appContext),
    )
    private val queue = DownloadQueue(
        history = history,
        engine = RoutingDownloadEngine(
            media = HttpDownloadEngine(appContext, storage, sessions::cookies),
            audio = AudioDownloadEngine(AudioExtractor(appContext, storage, sessions::cookies)),
            youtube = YouTubeDownloadEngine(appContext, storage, youtubeDl),
            vk = VkDownloadEngine(appContext, storage, vkDl),
            rutube = RutubeDownloadEngine(appContext, storage, rutubeDl),
            hitmoz = HitMozDownloadEngine(appContext, storage),
            muzmo = MuzmoDownloadEngine(appContext, storage),
        ),
        existingNames = storage::existingNames,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        onBatchChanged = { id, items -> notifier.update(id, items) },
        onCompleted = { task, result ->
            if (task.item.type == MediaType.AUDIO) {
                recognitionScope.launch {
                    waveformGate.withPermit {
                        BackgroundMediaWork.awaitIdle()
                        loadAudioWaveform(appContext, Uri.parse(result.savedUri))
                    }
                }
            }
            if (task.item.providerId == "muzmo") {
                recognitionScope.launch { resolveMuzmoArtwork(task.id) }
            }
            if (task.item.type == MediaType.VIDEO) {
                recognitionScope.launch {
                    persistDuration(task.id, result.savedUri)
                }
            }
            if (task.item.type == MediaType.AUDIO &&
                recognitionSettings.mode == MusicRecognitionMode.AUTO
            ) {
                beginRecognition()
                recognitionScope.launch {
                    try {
                        musicPostProcessGate.withPermit {
                            if (history.byId(task.id)?.isPrivate != true) musicPostProcessor.processDownloaded(task.id, task.item)
                        }
                    } finally {
                        endRecognition()
                    }
                }
            }
        },
    )

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
            BackgroundMediaWork.awaitIdle()
            // Older builds saved CDN IDs. Rename only owned completed photos whose real file can
            // be renamed and verified, then update the matching history row.
            val rows = history.all()
            val used = rows.map { it.originalName }.toMutableSet()
            rows.filter { !it.isPrivate && it.state == DownloadState.COMPLETED && it.mediaType == MediaType.PHOTO &&
                StorageNaming.meaningfulStem(it.originalName.substringBeforeLast('.')) == null }
                .groupBy { it.groupId ?: it.id }
                .values.forEach { group ->
                    group.sortedWith(compareBy({ it.createdAtEpochMillis }, { it.originalName }))
                        .forEachIndexed { index, entry ->
                            val uri = entry.savedUri ?: return@forEachIndexed
                            val preferred = StorageNaming.mediaFileName(null,
                                if (group.size > 1) index + 1 else null, "jpg")
                            val newName = StorageNaming.availableName(preferred, used)
                            musicPostProcessGate.withPermit {
                                val latest = history.byId(entry.id)
                                if (latest != null && !latest.isPrivate && latest.savedUri == uri &&
                                    storage.renameOwnedDownload(uri, newName)) {
                                    history.save(latest.copy(originalName = newName))
                                    used.add(newName)
                                }
                            }
                        }
                }

            // Duration belongs to history metadata. Backfill old completed videos once so
            // scrolling the UI never has to open every media file repeatedly.
            rows.filter {
                !it.isPrivate && it.state == DownloadState.COMPLETED &&
                    it.mediaType == MediaType.VIDEO &&
                    it.durationMs == null &&
                    !it.savedUri.isNullOrBlank()
            }.forEach { entry ->
                BackgroundMediaWork.awaitIdle()
                val duration = mediaDurationMs(entry.savedUri)
                if (duration != null) history.setDuration(entry.id, duration)
            }

            // Prepare every retained audio track once. The player can then paint its waveform
            // immediately, including the first time a previously downloaded track is opened.
            rows.filter {
                !it.isPrivate && it.state == DownloadState.COMPLETED &&
                    it.mediaType == MediaType.AUDIO &&
                    !it.savedUri.isNullOrBlank()
            }.forEach { entry ->
                waveformGate.withPermit {
                    BackgroundMediaWork.awaitIdle()
                    loadAudioWaveform(appContext, Uri.parse(requireNotNull(entry.savedUri)))
                }
            }
            } catch (_: Exception) {
                // An inaccessible old file must not prevent normal downloads from starting.
            }
        }
    }

    val downloads: Flow<List<DownloadEntity>> = history.observeAll()
    // Buffer identities survive successful public deletion for the lifetime of this process.
    // Restrict them to rows still private so a later public restore is playable normally.
    private val movedPublicAliases = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    private val movingPublicAliases = ConcurrentHashMap<String, Set<String>>()
    val privateMediaIds: Flow<Set<String>> = combine(history.observePrivate(), movedPublicAliases) { rows, captured ->
        buildSet {
            rows.forEach { row ->
                add(row.id)
                addAll(captured[row.id].orEmpty())
                // Pending source cleanup is durable in Room and must also protect a restarted session.
                row.privateSourceUri?.let { addAll(PublicMediaIdentity.capture(appContext, it)) }
            }
        }
    }
    val privateDownloads: Flow<List<DownloadEntity>> = combine(history.observePrivate(), privateAccess.state) { rows, access ->
        if (access.unlocked) rows else emptyList()
    }
    private val privateOperations by lazy { PrivateMediaOperations(
        privateAccess::requireUnlocked, history::byId, { row ->
            history.save(row)
            if (row.isPrivate) movingPublicAliases[row.id]?.let { aliases ->
                movedPublicAliases.update { existing -> existing + (row.id to (existing[row.id].orEmpty() + aliases)) }
            }
        }, history::deletePrivate,
        privateFiles::importUri, privateFiles::remove, privateFiles::removePublicSource,
        { row ->
            val published = storage.publish(privateFiles.file(requireNotNull(row.savedUri)), row.originalName, mimeType(row))
            published.fileName to published.uri.toString()
        },
        requireConfigured = { check(privateAccess.state.value.mode != com.domenota.medialoader.data.privatevault.PrivateAccessMode.UNCONFIGURED) { "Сначала настройте личное" } },
    ) }
    suspend fun moveToPrivate(id: String) = musicPostProcessGate.withPermit {
        val original = history.byId(id)
        val source = when {
            original == null -> null
            original.isPrivate -> original.privateSourceUri
            else -> original.savedUri
        }
        val aliases = source?.let { PublicMediaIdentity.capture(appContext, it) }.orEmpty()
        movingPublicAliases[id] = aliases
        try {
            privateOperations.move(id)
        } finally {
            // A copy may have committed before cancellation or public-source cleanup failure.
            // Publish only after checking the actual committed row; failed copies stay public.
            withContext(NonCancellable) {
                if (history.byId(id)?.isPrivate == true && aliases.isNotEmpty()) {
                    movedPublicAliases.update { existing -> existing + (id to (existing[id].orEmpty() + aliases)) }
                }
                movingPublicAliases.remove(id)
            }
        }
    }
    suspend fun finishPrivateMove(id: String) = musicPostProcessGate.withPermit {
        val row = history.byId(id)
        if (row?.isPrivate == true) row.privateSourceUri?.let { source ->
            val aliases = PublicMediaIdentity.capture(appContext, source)
            movedPublicAliases.update { existing -> existing + (id to (existing[id].orEmpty() + aliases)) }
        }
        privateOperations.finishMove(id)
    }
    suspend fun restorePrivate(id: String) = musicPostProcessGate.withPermit { privateOperations.restore(id) }
    suspend fun deletePrivate(id: String) = musicPostProcessGate.withPermit { privateOperations.delete(id) }
    fun requireMediaAccess(row: DownloadEntity) {
        if (row.isPrivate || row.savedUri?.let(privateFiles::owns) == true) privateAccess.requireUnlocked()
    }
    private fun mimeType(row: DownloadEntity): String = when (row.mediaType) {
        MediaType.PHOTO -> if (row.originalName.endsWith(".png", true)) "image/png" else "image/jpeg"
        MediaType.VIDEO -> "video/mp4"
        MediaType.AUDIO -> if (row.originalName.endsWith(".m4a", true)) "audio/mp4" else "audio/mpeg"
    }
    val catalogFolders = catalog.folders
    val catalogAssignments = catalog.assignments
    suspend fun createCatalogFolder(name: String) = catalog.createFolder(name)
    suspend fun renameCatalogFolder(id: String, name: String) = catalog.renameFolder(id, name)
    suspend fun deleteCatalogFolder(id: String) = catalog.deleteFolder(id)
    suspend fun setDownloadFolder(id: String, folderId: String?) { history.byId(id)?.let(::requireMediaAccess); catalog.setFolder(id, folderId) }
    suspend fun setDownloadFavorite(id: String, favorite: Boolean) { history.byId(id)?.let(::requireMediaAccess); catalog.setFavorite(id, favorite) }
    suspend fun toggleDownloadFavorite(id: String) { history.byId(id)?.let(::requireMediaAccess); catalog.toggleFavorite(id) }
    val bulk by lazy { BulkCoordinator(appContext, this, CoroutineScope(SupervisorJob() + Dispatchers.IO)) }
    val hiddenCount: Flow<Int> = history.observeHiddenCount()
    val hiddenItems: Flow<List<DownloadEntity>> = history.observeHidden()
    fun trashedUri(id: String): String? = storage.trashedUri(id)

    /** Folder shown in Settings, for example "Download/MediaLoader". */
    val storageDisplayPath: String get() = storage.displayPath
    fun setDownloadFolder(name: String) {
        val folder = name.trim().trim('/')
        require(folder.matches(Regex("[\\p{L}\\p{N}_ -]{1,50}")))
        storage.relativeDirectory = "Download/$folder/"
    }
    fun selectDownloadFolder(uri: Uri) = storage.selectDirectory(uri)
    val usesSelectedFolder: Boolean get() = storage.usesSelectedFolder

    val musicRecognitionMode: MusicRecognitionMode get() = recognitionSettings.mode
    fun setMusicRecognitionMode(mode: MusicRecognitionMode) { recognitionSettings.mode = mode }
    val renameRecognizedTracks: Boolean get() = recognitionSettings.renameRecognized
    fun setRenameRecognizedTracks(enabled: Boolean) { recognitionSettings.renameRecognized = enabled }
    suspend fun recognizeAudio(id: String) =
        if (recognitionSettings.mode == MusicRecognitionMode.OFF) null
        else {
            beginRecognition()
            try {
                val previouslyMatched = !history.byId(id)?.recognizedTitle.isNullOrBlank()
                val attempt = if (previouslyMatched) {
                    val nextAttempt = (recognitionRetries[id] ?: 0) + 1
                    recognitionRetries[id] = nextAttempt
                    1 + (nextAttempt - 1) % 4
                } else 0
                musicPostProcessGate.withPermit {
                    val row = history.byId(id)
                    if (row != null) requireMediaAccess(row)
                    check(row?.isPrivate != true) { "Распознавание приватного файла пока недоступно" }
                    musicPostProcessor.processExisting(id, attempt)
                }
            } finally {
                endRecognition()
            }
        }

    /** Recognize music in a saved video without creating an extracted audio download. */
    /** Resolves playback only; does not enqueue, publish a file or add a history row. */
    suspend fun previewPlayback(item: MediaItem): PreviewPlayback {
        val extractor = when (item.providerId) {
            YouTubeProvider.ID -> youtubePreviewStreams
            VkProvider.ID -> vkPreviewStreams
            RutubeProvider.ID -> rutubePreviewStreams
            else -> null
        }
        if (extractor != null) {
            val info = extractor.forPreview(item.downloadUrl)
            val height = item.qualityLabel?.substringBefore('p')?.toIntOrNull()
            return PreviewStreams.select(info, item.type, height)
        }
        require(PreviewStreams.httpUrl(item.downloadUrl)) {
            "Источник не предоставил адрес предпросмотра"
        }
        val referer = when (item.providerId) {
            InstagramProvider.ID -> "https://www.instagram.com/"
            "hitmoz" -> "https://ru.hitmoz.org/"
            "muzmo" -> "https://muzmo.cc/"
            else -> item.sourcePageUrl
        }
        return PreviewPlayback(
            PreviewTrack(item.downloadUrl,
                referer?.let { mapOf("Referer" to it) }.orEmpty()))
    }

    suspend fun recognizeSavedVideo(savedUri: String): MusicRecognitionResult? {
        if (privateFiles.owns(savedUri)) privateAccess.requireUnlocked()
        beginRecognition()
        return try {
            val pcm = recognitionAudioPreprocessor.decodeVideo(savedUri)
            if (pcm.isEmpty()) null else liveMusicRecognizer.recognize(pcm)
        } finally {
            endRecognition()
        }
    }

    suspend fun applyRecognizedAudio(id: String, result: com.domenota.medialoader.core.recognition.MusicRecognitionResult): Boolean {
        beginRecognition()
        return try {
            musicPostProcessGate.withPermit {
                val row = history.byId(id)
                if (row != null) requireMediaAccess(row)
                check(row?.isPrivate != true) { "Изменение тегов приватного файла пока недоступно" }
                musicPostProcessor.applyProposed(id, result) != null
            }
        } finally {
            endRecognition()
        }
    }

    /**
     * Find a full song for an already analyzed publication from any supported provider.
     * Never publish the recognition sample or create a download-history entry.
     * Instagram uses its safe media URL; yt-dlp platforms require their own runtime.
     */
    suspend fun recognizePreviewMusic(items: List<MediaItem>): MusicRecognitionResult? {
        val candidate = MusicLookupPolicy.preferredCandidate(items) ?: return null
        val provider = candidate.providerId
        if (candidate.type == MediaType.AUDIO) {
            instagramMusicRecognizer.sourceMetadata(candidate)?.let { return it }
        }

        beginRecognition()
        var sample: java.io.File? = null
        var workDir: java.io.File? = null
        try {
            sample = if (provider == InstagramProvider.ID) {
                previewAudioExtractor.recognitionSample(candidate.downloadUrl)
            } else {
                // YouTube/VK/RUTUBE use page URLs and yt-dlp, not Instagram's media HTTP client.
                val downloader = when (provider) {
                    YouTubeProvider.ID -> youtubeDl
                    VkProvider.ID -> vkDl
                    RutubeProvider.ID -> rutubeDl
                    else -> return null
                }
                workDir = java.io.File(
                    appContext.cacheDir, "recognition-$provider-${UUID.randomUUID()}",
                ).apply { mkdirs() }
                downloader.download(
                    candidate.downloadUrl,
                    selector = null,
                    audioOnly = true,
                    targetDir = requireNotNull(workDir),
                    onProgress = {},
                )
            }
            val file = requireNotNull(sample)
            // Avoid decoding an unexpectedly large media file to the recognition pipeline.
            if (file.length() == 0L || file.length() > MAX_RECOGNITION_SAMPLE_BYTES) {
                throw java.io.IOException("Аудиофрагмент для распознавания недоступен или слишком большой")
            }
            val pcm = recognitionAudioPreprocessor.decode(file)
            if (pcm.isEmpty()) return null
            return instagramMusicRecognizer.recognize(candidate.copy(type = MediaType.AUDIO)) { pcm }
        } finally {
            sample?.delete()
            workDir?.deleteRecursively()
            endRecognition()
        }
    }

    /** Records only in the foreground and sends Shazam a signature, not a saved audio file. */
    suspend fun recognizeLiveMusic(onProgress: suspend (Int) -> Unit,
        shouldFinish: () -> Boolean = { false }, onLevel: suspend (Float) -> Unit = {}): MusicRecognitionResult? =
        liveMusicRecognizer.recognize(MicrophoneCapture().capture(onProgress, shouldFinish, onLevel))

    private fun beginRecognition() {
        _recognitionActive.update { it + 1 }
        ActiveDownloadService.start(appContext)
    }

    private fun endRecognition() {
        _recognitionActive.update { (it - 1).coerceAtLeast(0) }
    }

    fun isLoggedIn(): Boolean = sessions.isLoggedIn()
    suspend fun updateYtDlp(): String = youtubeDl.update()
    val hasYouTubeCookies: Boolean get() = youtubeDl.hasCookies
    fun importYouTubeCookies(uri: Uri) = youtubeDl.importCookies(uri)
    fun clearYouTubeCookies() = youtubeDl.clearCookies()
    val hasVkCookies: Boolean get() = vkDl.hasCookies
    fun clearVkCookies() = vkDl.clearCookies()
    val hasRutubeSession: Boolean get() = rutubeDl.hasSession
    fun clearRutubeSession() = rutubeDl.clearSession()

    fun importSessionId(value: String) = sessions.saveManualSessionId(value)

    fun logout() = sessions.clear()

    /** Uses the same provider extractor without changing saved sessions on a diagnostic failure. */
    suspend fun diagnosticResolve(url: String): List<MediaItem> = resolver.resolve(url)

    /** Public access first; the saved session is used inside the provider only as a fallback. */
    suspend fun resolve(url: String): List<MediaItem> = try {
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val sourceHost = runCatching { android.net.Uri.parse(url).host.orEmpty() }.getOrDefault("")
        AppLog.verbose("Resolve", "Begin · host=$sourceHost")
        coroutineScope {
            resolver.resolve(url).map { item -> async {
                if (item.providerId == YouTubeProvider.ID || item.providerId == VkProvider.ID ||
                    item.providerId == RutubeProvider.ID) item
                else if (item.type == MediaType.AUDIO) item.copy(
                    originalName = StorageNaming.normalizedMediaName(item.originalName, item.position))
                else {
                    val headers = mediaHeaders(item.downloadUrl)
                    val stem = headers?.fileStem
                    val extension = if (item.type == MediaType.VIDEO) "mp4" else "jpg"
                    item.copy(
                        sizeBytes = item.sizeBytes ?: headers?.sizeBytes,
                        originalName = if (stem != null && item.originalName.startsWith(StorageNaming.FALLBACK_STEM))
                            StorageNaming.mediaFileName(stem, item.position, extension)
                            else item.originalName,
                    ).let { it.copy(originalName = StorageNaming.normalizedMediaName(it.originalName, it.position)) }
                }
            } }.awaitAll().map { item ->
                item.copy(sourcePageUrl = if (item.providerId == VkProvider.ID) item.downloadUrl else url)
            }
        }.let { PreferredVideoQuality.apply(it, videoPreferences.preferredHeight) }.also { results ->
            if (results.any { it.providerId == InstagramProvider.ID })
                sessions.markWorkingMediaSession()
            AppLog.verbose(
                "Resolve",
                "Completed · items=${results.size} · sources=${results.map { it.providerId }.distinct().joinToString(",")} · elapsedMs=${android.os.SystemClock.elapsedRealtime() - startedAt}",
            )
        }
    } catch (error: ProviderException) {
        AppLog.verbose("Resolve", "Provider error · provider=${error.providerId} · reason=${error.reason}")
        // A session that still gets ACCESS_REQUIRED is expired: drop it so the UI offers sign-in again.
        if (error.providerId == InstagramProvider.ID && error.reason == ProviderException.Reason.ACCESS_REQUIRED && sessions.isLoggedIn()) {
            sessions.clear()
        }
        if (error.providerId == VkProvider.ID && error.reason == ProviderException.Reason.ACCESS_REQUIRED && vkDl.hasCookies) {
            vkDl.clearCookies()
        }
        if (error.providerId == RutubeProvider.ID && error.reason == ProviderException.Reason.ACCESS_REQUIRED && rutubeDl.hasSession) {
            rutubeDl.clearSession()
        }
        throw error
    }

    /** User-confirmed MP3 download, outside the Instagram extraction/transcoding pipeline. */
    suspend fun enqueueHitMozMp3(
        url: String,
        artist: String,
        title: String,
        artworkUrl: String? = null,
        sourcePageUrl: String? = null,
    ): DownloadEntity {
        require(HitMozDownloadUrls.isMp3(url)) { "Это не ссылка на MP3-файл" }
        val name = StorageNaming.sanitizeStem(
            listOf(artist.trim(), title.trim()).filter { it.isNotBlank() }.joinToString(" - "),
            maxLength = 100,
        ) ?: "Музыка"
        val item = MediaItem(
            id = UUID.randomUUID().toString(),
            providerId = "hitmoz",
            type = MediaType.AUDIO,
            originalName = "$name.mp3",
            downloadUrl = url,
            sourcePageUrl = sourcePageUrl?.takeIf(HitMozDownloadUrls::isTrustedPage)
                ?: HitMozSearchClient.searchUrl("$artist $title"),
            previewUrl = artworkUrl,
            audioArtist = artist.takeIf { it.isNotBlank() },
            audioTitle = title.takeIf { it.isNotBlank() },
            audioArtworkUrl = artworkUrl,
            customName = true,
        )
        return queue.enqueue(listOf(item)).single()
    }

    /** User-selected Muzmo original MP3. Never accepts an unverified domain or fabricated URL. */
    suspend fun enqueueMuzmoMp3(
        url: String,
        artist: String,
        title: String,
        sourcePageUrl: String,
        artworkUrl: String? = null,
    ): DownloadEntity {
        require(MuzmoDownloadUrls.isMp3(url)) { "Это не ссылка Muzmo на MP3-файл" }
        require(MuzmoDownloadUrls.isInfoPage(sourcePageUrl)) { "Недопустимая страница композиции Muzmo" }
        val name = StorageNaming.sanitizeStem(
            listOf(artist.trim(), title.trim()).filter { it.isNotBlank() }.joinToString(" - "),
            maxLength = 100,
        ) ?: "Музыка"
        return queue.enqueue(listOf(MediaItem(
            id = UUID.randomUUID().toString(),
            providerId = "muzmo",
            type = MediaType.AUDIO,
            originalName = "$name.mp3",
            downloadUrl = url,
            sourcePageUrl = sourcePageUrl,
            previewUrl = artworkUrl,
            audioArtist = artist.takeIf { it.isNotBlank() },
            audioTitle = title.takeIf { it.isNotBlank() },
            audioArtworkUrl = artworkUrl,
            customName = true,
        ))).single()
    }

    /** Optional exact-match cover for new and older Muzmo downloads; never delays the MP3. */
    suspend fun resolveMuzmoArtwork(id: String) {
        val row = history.byId(id) ?: return
        if (row.providerId != "muzmo" || row.state != DownloadState.COMPLETED || row.previewUrl != null) return
        val artist = row.sourceAudioArtist?.takeIf(String::isNotBlank) ?: return
        val title = row.sourceAudioTitle?.takeIf(String::isNotBlank) ?: return
        val artwork = withContext(Dispatchers.IO) { MusicArtworkLookup.lookup(artist, title) } ?: return
        if (history.setMuzmoArtworkIfMissing(id, artwork) > 0) {
            AppLog.verbose("MusicArtwork", "Muzmo cover saved · id=$id")
        }
    }

    suspend fun enqueue(items: List<MediaItem>): List<DownloadEntity> {
        require(items.all(resolver::canDownload)) { "Недопустимый адрес файла" }
        // Store the stable media identity for ordinary downloads too. This makes duplicate
        // detection independent from expiring Instagram CDN URLs.
        prefetchVideoCovers(items)
        return queue.enqueue(items, bulkMediaKeys = items.map(BulkIdentity::key))
    }

    /** Stable IDs make restoring a confirmed bulk job safe after a process interruption. */
    suspend fun enqueueBulk(items: List<MediaItem>, stableIds: List<String>, groupIds: List<String>): List<DownloadEntity> {
        require(items.all(resolver::canDownload)) { "Недопустимый адрес файла" }
        require(groupIds.size == items.size)
        prefetchVideoCovers(items)
        return queue.enqueue(items, stableIds, items.map(BulkIdentity::key), groupIds)
    }

    /** Start before transfer so even headless downloads retain their source cover in the local image cache. */
    private fun prefetchVideoCovers(items: List<MediaItem>) {
        items.filter { it.type == MediaType.VIDEO && !it.previewUrl.isNullOrBlank() }
            .distinctBy { it.previewUrl }.forEach { item ->
                recognitionScope.launch {
                    sourceCoverGate.withPermit {
                        runCatching { RemoteImageLoader.fetch(appContext, item.previewUrl!!, item.providerId, 512) }
                    }
                }
            }
    }

    suspend fun publishEditedFile(
        source: DownloadEntity,
        file: java.io.File,
        preferredName: String,
        mediaType: MediaType,
        mimeType: String,
    ): DownloadEntity {
        return musicPostProcessGate.withPermit {
            publishDerived(source, file, preferredName, mediaType, mimeType)
        }
    }

    private suspend fun publishDerived(
        source: DownloadEntity, file: java.io.File, preferredName: String,
        mediaType: MediaType, mimeType: String,
        title: String? = null, artist: String? = null, album: String? = null,
    ): DownloadEntity {
        val latest = history.byId(source.id)
        val isPrivate = source.isPrivate || latest?.isPrivate == true || source.savedUri?.let(privateFiles::owns) == true
        if (isPrivate) privateAccess.requireUnlocked()
        val id = UUID.randomUUID().toString()
        return withContext(NonCancellable) {
            val (name, uri) = if (isPrivate) {
                val ext = preferredName.substringAfterLast('.', "bin").takeIf { it.matches(Regex("[A-Za-z0-9]{1,10}")) } ?: "bin"
                preferredName to privateFiles.importFile("$id.$ext", file)
            } else {
                val published = storage.publish(file, preferredName, mimeType)
                published.fileName to published.uri.toString()
            }
            val row = DownloadEntity(
                id = id, providerId = source.providerId, originalName = name,
                mediaType = mediaType, state = DownloadState.COMPLETED, savedUri = uri,
                sizeBytes = file.length(), createdAtEpochMillis = System.currentTimeMillis(),
                sourcePageUrl = source.sourcePageUrl, previewUrl = source.previewUrl,
                groupId = source.groupId, derivedFromId = source.id,
                durationMs = if (mediaType != MediaType.PHOTO) mediaDurationMs(uri) else null,
                recognizedTitle = title, recognizedArtist = artist, recognizedAlbum = album,
                isPrivate = isPrivate,
            )
            try { history.save(row) }
            catch (error: Throwable) {
                runCatching { if (isPrivate) privateFiles.remove(uri) else privateFiles.removePublicSource(uri) }
                throw error
            }
            row
        }
    }

    /** Save a frame from a resolved video without creating a permanent video download. */
    suspend fun savePreviewFrame(item: MediaItem): DownloadEntity = withContext(Dispatchers.IO) {
        require(item.providerId == InstagramProvider.ID && item.type == MediaType.VIDEO)
        val video = java.io.File.createTempFile("preview-video-", ".mp4", appContext.cacheDir)
        var frame: java.io.File? = null
        try {
            val connection = java.net.URL(item.downloadUrl).openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", "Mozilla/5.0")
            connection.setRequestProperty("Referer", "https://www.instagram.com/")
            try {
                if (connection.responseCode !in 200..299) throw java.io.IOException("Видео недоступно")
                if (connection.contentLengthLong > 150L * 1024 * 1024)
                    throw java.io.IOException("Видео слишком большое для извлечения кадра")
                connection.inputStream.use { input -> video.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > 150L * 1024 * 1024)
                            throw java.io.IOException("Видео слишком большое для извлечения кадра")
                        output.write(buffer, 0, count)
                    }
                } }
            } finally { connection.disconnect() }
            frame = com.domenota.medialoader.data.editor.VideoFrameExporter(appContext)
                .export(android.net.Uri.fromFile(video), 250L,
                    com.domenota.medialoader.data.editor.VideoEditSpec())
            val published = storage.publish(requireNotNull(frame),
                "${StorageNaming.editableStem(item.originalName).ifBlank { "Instagram" }.take(70)}_frame.jpg",
                "image/jpeg")
            DownloadEntity(
                id = UUID.randomUUID().toString(), providerId = item.providerId,
                originalName = published.fileName, mediaType = MediaType.PHOTO,
                state = DownloadState.COMPLETED, savedUri = published.uri.toString(),
                sizeBytes = requireNotNull(frame).length(),
                createdAtEpochMillis = System.currentTimeMillis(),
                sourcePageUrl = item.sourcePageUrl, previewUrl = item.previewUrl,
            ).also { history.save(it) }
        } finally {
            frame?.delete()
            video.delete()
        }
    }

    suspend fun publishEditedAudioFile(
        source: DownloadEntity,
        file: java.io.File,
        preferredName: String,
        title: String,
        artist: String,
        album: String,
    ): DownloadEntity {
        return musicPostProcessGate.withPermit {
            publishDerived(source, file, preferredName, MediaType.AUDIO, "audio/mpeg", title, artist, album)
        }
    }

    suspend fun downloadById(id: String): DownloadEntity? = history.byId(id)

    /** Index a file already published by the separate VK community queue in normal history. */
    suspend fun recordVkCommunityImage(
        jobId: String, photoKey: String, postKey: String,
        fileName: String, savedUri: String, sizeBytes: Long,
    ) {
        history.save(DownloadEntity(
            id = "vk-community-$jobId-$photoKey",
            providerId = "vk",
            originalName = fileName,
            mediaType = MediaType.PHOTO,
            state = DownloadState.COMPLETED,
            savedUri = savedUri,
            sizeBytes = sizeBytes,
            createdAtEpochMillis = System.currentTimeMillis(),
            sourcePageUrl = "https://vk.com/wall${postKey.replace(':', '_')}",
            groupId = "vk-community-$jobId",
        ))
    }
    suspend fun cancelQueuedDownload(id: String) {
        history.byId(id)?.let(::requireMediaAccess)
        queue.cancelQueued(id)
    }

    /** A source switch only clears waiting searches/downloads from the old source. */
    suspend fun onMusicSourceChanged(previous: FullSongSource, current: FullSongSource): Int {
        val providerId = MusicSourcePolicy.queuedProviderToCancel(previous, current) ?: return 0
        return queue.cancelQueuedForProvider(
            providerId,
            "Источник поиска изменён до начала загрузки.",
        )
    }

    suspend fun findDownloaded(item: MediaItem): DownloadEntity? {
        val stableKey = BulkIdentity.key(item)
        val candidates = history.all().filter { row ->
            row.state == DownloadState.COMPLETED && !row.hidden && !row.isPrivate &&
                row.providerId == item.providerId &&
                row.mediaType == item.type &&
                row.savedUri != null &&
                if (item.providerId == InstagramProvider.ID) {
                    row.bulkMediaKey == stableKey ||
                        (row.bulkMediaKey == null && row.sourceUrl == item.downloadUrl)
                } else {
                    row.bulkMediaKey == stableKey ||
                        (row.bulkMediaKey == null &&
                            row.sourcePageUrl == item.sourcePageUrl &&
                            row.formatSelector == item.formatSelector)
                }
        }
        for (row in candidates) {
            val present = withContext(Dispatchers.IO) {
                savedFileStatus(row.savedUri) == SavedFileStatus.PRESENT
            }
            if (present) return row
        }
        return null
    }

    suspend fun alreadyDownloaded(item: MediaItem): Boolean = findDownloaded(item) != null

    suspend fun cancel(id: String) { history.byId(id)?.let(::requireMediaAccess); queue.cancel(id) }

    /** Read-only availability check. Call from an IO dispatcher for many history rows. */
    fun savedFileStatus(savedUri: String?): SavedFileStatus =
        SavedFilePresence.check(appContext, savedUri)

    suspend fun rename(id: String, input: String) = musicPostProcessGate.withPermit {
        AppLog.verbose("History", "Rename requested · id=$id")
        val item = history.byId(id) ?: return@withPermit
        requireMediaAccess(item)
        if (item.isPrivate) {
            history.save(item.copy(originalName = StorageNaming.customName(input, item.originalName)))
            return@withPermit
        }
        require(item.state == DownloadState.COMPLETED && !item.hidden)
        val uri = item.savedUri ?: throw IllegalStateException("Файл отсутствует")
        when (withContext(Dispatchers.IO) { savedFileStatus(uri) }) {
            SavedFileStatus.MISSING -> throw IllegalStateException("Файл отсутствует на устройстве")
            SavedFileStatus.INACCESSIBLE, SavedFileStatus.UNCHECKED ->
                throw IllegalStateException("Нет доступа к файлу")
            SavedFileStatus.PRESENT -> Unit
        }
        val desired = StorageNaming.customName(input, item.originalName)
        if (desired == item.originalName) return@withPermit
        val taken = storage.existingNames() + history.reservedNames()
        require(desired !in taken) { "Имя занято" }
        val newUri = storage.renameDownload(uri, desired)
            ?: throw IllegalStateException("Переименование не удалось")
        history.save(item.copy(originalName = desired, savedUri = newUri))
        AppLog.i("History", "Rename completed · id=$id")
    }
    suspend fun hide(id: String, completedOnly: Boolean = false) = musicPostProcessGate.withPermit {
        AppLog.verbose("Trash", "Move requested · id=$id")
        val item = history.byId(id) ?: return@withPermit
        if (item.hidden) return@withPermit // Idempotent retry after interruption of a batch.
        requireMediaAccess(item)
        require(!item.isPrivate) { "Удалите файл через приватный раздел" }
        require(!completedOnly || item.state == DownloadState.COMPLETED) { "Действие доступно только для завершённых файлов" }
        if (item.state == DownloadState.QUEUED || item.state == DownloadState.RUNNING) return@withPermit
        if (item.state == DownloadState.COMPLETED) {
            when (withContext(Dispatchers.IO) { savedFileStatus(item.savedUri) }) {
                SavedFileStatus.MISSING -> {
                    // No bytes to move. Remove only this stale history record.
                    // Do not put an unrestorable phantom into the trash.
                    history.deleteMissingHistoryRow(id)
                    AppLog.i("History", "Deleted missing-file history record · id=$id · provider=${item.providerId}")
                    return@withPermit
                }
                SavedFileStatus.INACCESSIBLE, SavedFileStatus.UNCHECKED ->
                    throw IllegalStateException("Нет доступа к файлу; запись сохранена")
                SavedFileStatus.PRESENT -> storage.moveToTrash(id, item.savedUri!!)
            }
        }
        history.save(item.copy(hidden = true, savedUri = null))
        AppLog.i("Trash", "Move completed · id=$id")
    }

    suspend fun restoreHidden(ids: Set<String>) {
        AppLog.i("Trash", "Restore requested · count=${ids.size}")
        history.all().filter { !it.isPrivate && it.hidden && it.id in ids }.forEach { item ->
            if (item.state == DownloadState.COMPLETED) {
                if (storage.hasTrashed(item.id)) {
                    val mime = when (item.mediaType) {
                        MediaType.PHOTO -> "image/jpeg"
                        MediaType.VIDEO -> "video/mp4"
                        MediaType.AUDIO -> if (item.originalName.endsWith(".m4a", true)) "audio/mp4" else "audio/mpeg"
                    }
                    val (name, uri) = storage.restoreFromTrash(item.id, item.originalName, mime)
                    history.save(item.copy(originalName = name, savedUri = uri, hidden = false))
                    AppLog.i("Trash", "Restored · id=${item.id}")
                    if (!storage.discardTrashed(item.id)) {
                        AppLog.w("Trash", "Restored file, but old private copy cleanup failed · id=${item.id}")
                    }
                }
            } else history.save(item.copy(hidden = false))
        }
    }

    /**
     * A trash item must remain recoverable when its private file cannot be deleted.
     * Delete the database row only AFTER successfully removing the private bytes.
     * Failed rows stay visible, so retries and diagnosis remain possible.
     */
    suspend fun removeHidden(ids: Set<String>): Int {
        var removed = 0
        var failed = 0
        val candidates = history.all().filter { !it.isPrivate && it.hidden && it.id in ids }
        AppLog.i("Trash", "Permanent deletion started · items=${candidates.size}")
        candidates.forEach { row ->
            try {
                val hasBytes = storage.hasTrashed(row.id)
                if (hasBytes && !storage.discardTrashed(row.id)) {
                    failed++
                    AppLog.w("Trash", "Physical delete failed; entry preserved · id=${row.id}")
                    return@forEach
                }
                if (!hasBytes && row.state == DownloadState.COMPLETED) {
                    failed++
                    AppLog.w("Trash", "Completed entry has no private file; entry preserved · id=${row.id}")
                    return@forEach
                }
                history.deleteHidden(row.id)
                removed++
                AppLog.i("Trash", "Permanently removed · id=${row.id}")
            } catch (error: Exception) {
                failed++
                AppLog.w("Trash", "Deletion failed; metadata retained · id=${row.id}", error)
            }
        }
        AppLog.i("Trash", "Permanent deletion ended · removed=$removed · failed=$failed")
        if (failed > 0) throw java.io.IOException("Не удалось удалить $failed файлов. Остальные записи сохранены.")
        return removed
    }

    suspend fun clearHidden() {
        removeHidden(history.all().filter { !it.isPrivate && it.hidden }.mapTo(mutableSetOf()) { it.id })
    }

    /** History cleanup must never remove trash records or physically stored files. */
    suspend fun clearHistory() {
        val affected = history.all().count {
            !it.isPrivate && !it.hidden && (it.state == DownloadState.COMPLETED ||
                it.state == DownloadState.FAILED || it.state == DownloadState.CANCELLED)
        }
        AppLog.i("History", "History-only cleanup started · entries=$affected")
        history.clearHistory()
        AppLog.ok("History", "History-only cleanup completed · entries=$affected; trashUntouched=true")
    }
    suspend fun retry(id: String) {
        val previous = history.byId(id) ?: return
        requireMediaAccess(previous)
        require(!previous.isPrivate)
        if (previous.sourceUrl == null) return
        if (previous.state != com.domenota.medialoader.core.model.DownloadState.FAILED &&
            previous.state != com.domenota.medialoader.core.model.DownloadState.CANCELLED) return
        ActiveDownloadService.start(appContext)
        queue.retry(previous)
    }

    private suspend fun persistDuration(id: String, savedUri: String?) {
        if (savedUri.isNullOrBlank()) return
        val duration = mediaDurationMs(savedUri) ?: return
        val row = history.byId(id) ?: return
        if (row.durationMs != duration) history.setDuration(id, duration)
    }

    private fun mediaDurationMs(savedUri: String?): Long? {
        if (savedUri.isNullOrBlank()) return null
        return runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(appContext, Uri.parse(savedUri))
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
                    ?.takeIf { it > 0L }
            } finally {
                retriever.release()
            }
        }.getOrNull()
    }

    companion object {
        private const val MAX_RECOGNITION_SAMPLE_BYTES = 200L * 1024 * 1024

        @Volatile private var instance: MediaRepository? = null

        fun get(context: Context): MediaRepository = instance ?: synchronized(this) {
            instance ?: MediaRepository(context).also { instance = it }
        }
    }
}
