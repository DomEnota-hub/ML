package com.domenota.medialoader.ui

import android.content.Context
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.data.archive.*
import com.domenota.medialoader.data.library.*
import com.domenota.medialoader.data.playback.AudioPlaybackService
import com.domenota.medialoader.data.playback.PublicMediaIdentity
import com.domenota.medialoader.data.storage.SavedFileStatus
import com.domenota.medialoader.data.storage.SavedFileStatusCache
import com.domenota.medialoader.ui.components.AppNotice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Keep archive state and actions outside the large root Compose method. */
internal class SimpleArchiveSession(
    internal val store: SimpleArchiveStore,
    private val recordState: State<List<SimpleArchiveRecord>>,
) {
    val records: List<SimpleArchiveRecord> get() = recordState.value
    var loaded by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private val byUri by derivedStateOf { records.groupBy { it.savedUri } }

    suspend fun refresh() {
        try { store.refresh(); loaded = true }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = "Не удалось открыть Архив" }
    }

    fun isArchived(row: DownloadEntity): Boolean {
        val uri = row.savedUri ?: return false
        return SimpleArchivePolicy.isArchived(byUri[uri].orEmpty(),
            SimpleArchiveCandidate(row.id, uri, row.mediaType, row.sizeBytes))
    }

    fun begin(): Boolean {
        if (!loaded || busy) return false
        busy = true
        error = null
        return true
    }

    fun fail(message: String) { error = message }
    fun finish() { busy = false }
}

@Composable
internal fun rememberSimpleArchiveSession(context: Context): SimpleArchiveSession {
    val store = remember(context) { SimpleArchiveStore(context) }
    val records = store.records.collectAsStateWithLifecycle()
    val session = remember(store, records) { SimpleArchiveSession(store, records) }
    LaunchedEffect(session) { session.refresh() }
    return session
}

internal data class SimpleArchiveProjection(
    val blocked: Set<String>,
    val publicRefs: List<LibraryMediaRef>,
)

@Composable
internal fun rememberSimpleArchiveProjection(
    session: SimpleArchiveSession,
    guardedRefs: List<LibraryMediaRef>?,
    downloads: List<DownloadEntity>,
    queueGeneration: Any?,
): SimpleArchiveProjection {
    val records = session.records
    val loaded = session.loaded
    val blocked = remember(records, guardedRefs, downloads) {
        buildSet {
            guardedRefs.orEmpty().filter { session.isArchived(it.item) }.forEach { ref ->
                add(ref.key); addAll(ref.aliases)
                ref.item.savedUri?.let { addAll(PublicMediaIdentity.uriKeys(it)) }
            }
            downloads.filter { session.isArchived(it) }.forEach { row ->
                add(row.id); add("app:${row.id}")
                row.savedUri?.let { addAll(PublicMediaIdentity.uriKeys(it)) }
            }
        }
    }
    LaunchedEffect(loaded, records, queueGeneration) {
        if (loaded && records.isNotEmpty()) {
            AudioPlaybackService.removeArchivedFromQueue(
                records.filter { it.mediaType == MediaType.AUDIO }.map { it.savedUri })
        }
    }
    val publicRefs = remember(guardedRefs, records, loaded) {
        if (!loaded) emptyList() else guardedRefs.orEmpty().filterNot { session.isArchived(it.item) }
    }
    return SimpleArchiveProjection(blocked, publicRefs)
}

internal data class SimpleArchiveInputs(
    val publicRefs: List<LibraryMediaRef>,
    val guardedRefs: List<LibraryMediaRef>,
    val publicDownloads: List<DownloadEntity>,
    val visibilityFolders: Set<String>,
    val folderReady: Boolean,
    val cancelPreview: () -> Unit,
)

@Composable
internal fun rememberSimpleArchiveActions(
    context: Context,
    session: SimpleArchiveSession,
    viewModel: MediaLoaderViewModel,
    libraryModel: LibraryViewModel,
    scope: CoroutineScope,
    availabilityCache: SavedFileStatusCache,
    inputs: SimpleArchiveInputs,
): SimpleArchiveActions {
    val latest = rememberUpdatedState(inputs)
    return remember(context, session, viewModel, libraryModel, scope, availabilityCache) {
        SimpleArchiveActions(context, session, viewModel, libraryModel, scope, availabilityCache, latest)
    }
}

internal class SimpleArchiveActions(
    private val context: Context,
    private val session: SimpleArchiveSession,
    private val viewModel: MediaLoaderViewModel,
    private val libraryModel: LibraryViewModel,
    private val scope: CoroutineScope,
    private val availabilityCache: SavedFileStatusCache,
    private val latest: State<SimpleArchiveInputs>,
) {
    private fun resolveRow(id: String): DownloadEntity? {
        val input = latest.value
        val matches = input.publicRefs.filter { it.key == id || id in it.aliases }.map { it.item } +
            input.publicDownloads.filter { it.id == id }
        val first = matches.firstOrNull() ?: return null
        if (first.savedUri.isNullOrBlank() || matches.any {
                it.savedUri != first.savedUri || it.mediaType != first.mediaType
            } || matches.mapNotNull { it.sizeBytes }.distinct().size > 1) return null
        return matches.firstOrNull { it.sizeBytes != null } ?: first
    }

    private fun allowed(row: DownloadEntity): Boolean {
        val uri = row.savedUri ?: return false
        val privateKeys = libraryModel.privateKeys.value ?: return false
        val blocked = privateKeys + DuplicatePrivacyGuard.currentBlockedKeys()
        return !row.isPrivate && !row.hidden && row.state == DownloadState.COMPLETED &&
            row.id !in blocked && "app:${row.id}" !in blocked &&
            PublicMediaIdentity.uriKeys(uri).none { it in blocked } &&
            libraryModel.visibilityGuard.value?.canOpen(row, null, emptyList()) == true
    }

    fun archiveSelection(
        ids: List<String>,
        source: ArchiveEntrySource,
        group: SimpleArchiveGroup? = null,
        validateScope: () -> Boolean = { true },
    ) {
        if (!session.loaded || session.busy || ids.isEmpty()) return
        val requested = ids.distinct()
        val rows = requested.mapNotNull(::resolveRow)
        if (rows.size != requested.size) {
            AppNotice.post(context, "Некоторые файлы недоступны")
            return
        }
        if (!session.begin()) return
        latest.value.cancelPreview()
        scope.launch {
            try {
                check(validateScope() && rows.all(::allowed)) { "Выбранные файлы недоступны" }
                for (row in rows.distinctBy { it.savedUri }) {
                    check(libraryModel.verifyVisibilityOpen(row, null) &&
                        withContext(Dispatchers.IO) {
                            availabilityCache.get(row.savedUri, fresh = true)
                        } == SavedFileStatus.PRESENT) { "Выбранные файлы недоступны" }
                }
                check(validateScope() && requested.zip(rows).all { (id, row) ->
                    val current = resolveRow(id)
                    current != null && current.savedUri == row.savedUri &&
                        current.mediaType == row.mediaType && current.sizeBytes == row.sizeBytes &&
                        allowed(current)
                }) { "Состав или доступ к выбранным файлам изменился" }
                val next = session.store.archive(rows, source, group)
                AudioPlaybackService.removeArchivedFromQueue(
                    next.filter { it.mediaType == MediaType.AUDIO }.map { it.savedUri })
                AppNotice.post(context, "В архиве: ${rows.mapNotNull { it.savedUri }.distinct().size}")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val message = e.message ?: "Не удалось добавить в Архив"
                session.fail(message)
                AppNotice.post(context, message)
            } finally { session.finish() }
        }
    }

    fun archiveVirtualFolder(folderId: String, source: ArchiveEntrySource) {
        val input = latest.value
        val folder = viewModel.catalogFolders.value.firstOrNull { it.id == folderId }
        if (folder == null || folderId in input.visibilityFolders || !input.folderReady) {
            AppNotice.post(context, "Состав папки недоступен")
            return
        }
        fun members(): Set<String> =
            viewModel.catalogAssignments.value.filter { it.folderId == folderId }.map { it.downloadId }.toSet() +
                libraryModel.externalCatalog.entries.value.filter { it.folderId == folderId }.map { it.ref.key }
        val memberIds = members()
        val available = memberIds.filter { resolveRow(it) != null }
        val unavailable = memberIds - available.toSet()
        fun knownArchived(): Boolean = unavailable.all { id ->
            val candidates = latest.value.guardedRefs.filter { it.key == id || id in it.aliases }.map { it.item } +
                viewModel.downloads.value.filter { it.id == id }
            candidates.isNotEmpty() && candidates.all { row -> allowed(row) && session.isArchived(row) }
        }
        if (!knownArchived()) {
            AppNotice.post(context, "Состав папки не подтверждён полностью")
            return
        }
        if (available.isEmpty()) {
            AppNotice.post(context, "Нет доступных файлов для архива")
            return
        }
        archiveSelection(available, source, SimpleArchiveGroup(
            SimpleArchiveGroupKind.VIRTUAL_FOLDER, folder.id, folder.name)) {
            val current = latest.value
            current.folderReady && folderId !in current.visibilityFolders && members() == memberIds &&
                viewModel.catalogFolders.value.any { it.id == folderId && it.name == folder.name } && knownArchived()
        }
    }

    fun restore(ids: Set<String>) {
        if (ids.isEmpty() || !session.begin()) return
        scope.launch {
            try {
                session.store.restore(ids)
                AppNotice.post(context, "Возвращено: ${ids.size}")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { session.fail(e.message ?: "Не удалось вернуть файлы") }
            finally { session.finish() }
        }
    }
}
