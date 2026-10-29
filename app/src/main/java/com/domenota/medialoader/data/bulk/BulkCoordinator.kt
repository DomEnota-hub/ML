package com.domenota.medialoader.data.bulk

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.domenota.medialoader.core.model.MediaItem
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.provider.ProviderException
import com.domenota.medialoader.data.MediaRepository
import com.domenota.medialoader.data.download.ActiveDownloadService
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

enum class BulkPhase { EMPTY, ANALYZING, PREVIEW, QUEUING, PAUSED, FINISHED, STOPPED }

data class BulkCandidate(
    val key: String,
    val item: MediaItem,
    val selected: Boolean = true,
    val downloadId: String? = null,
)

data class BulkState(
    val jobId: String = "",
    val createdAtEpochMillis: Long = 0L,
    val phase: BulkPhase = BulkPhase.EMPTY,
    val urls: List<String> = emptyList(),
    val cursor: Int = 0,
    val candidates: List<BulkCandidate> = emptyList(),
    val errors: List<String> = emptyList(),
    val unsupported: Int = 0,
    val unsupportedUrls: List<String> = emptyList(),
    val duplicates: Int = 0,
    val lastInstagramAttemptAt: Long = 0L,
    val rules: BulkRules = BulkRules(),
) {
    val selectedCount: Int get() = candidates.count { it.selected }
    val queuedCount: Int get() = candidates.count { it.downloadId != null }
}

/** SQLite stores each state transition before the next network or queue operation. */
class BulkCoordinator(
    private val context: Context,
    private val repository: MediaRepository,
    private val scope: CoroutineScope,
) {
    private val store = BulkStore(context)
    private val _state = MutableStateFlow(store.load())
    val state: StateFlow<BulkState> = _state.asStateFlow()
    private val _history = MutableStateFlow(store.history())
    val history: StateFlow<List<BulkState>> = _history.asStateFlow()
    private var worker: Job? = null

    init {
        scope.launch {
            combine(_state, repository.downloads) { current, rows -> current to rows }.collect { (current, rows) ->
                if (current.phase == BulkPhase.QUEUING && current.selectedCount == current.queuedCount) {
                    val linked = rows.filter { row -> current.candidates.any { it.downloadId == row.id } }
                    if (linked.size == current.queuedCount && linked.all {
                        it.state == DownloadState.COMPLETED || it.state == DownloadState.FAILED ||
                            it.state == DownloadState.CANCELLED
                    }) set(current.copy(phase = BulkPhase.FINISHED))
                }
            }
        }
        when (_state.value.phase) {
            BulkPhase.ANALYZING -> launchAnalysis()
            BulkPhase.QUEUING -> launchQueue()
            else -> Unit
        }
    }

    fun start(text: String, rules: BulkRules): String? {
        if (_state.value.phase in setOf(BulkPhase.ANALYZING, BulkPhase.QUEUING, BulkPhase.PAUSED))
            return "Сначала завершите текущее задание"
        if (!rules.hasSelectedSources()) return "Выберите хотя бы один источник"
        if (!rules.hasSelectedTypes()) return "Выберите хотя бы один тип файлов"
        val parsed = runCatching { BulkLinks.parse(text) }.getOrElse { return it.message ?: "Не удалось прочитать ссылки" }
        if (parsed.urls.isEmpty()) return "Поддерживаемые ссылки не найдены"
        worker?.cancel()
        set(BulkState(jobId = UUID.randomUUID().toString(),
            createdAtEpochMillis = System.currentTimeMillis(), phase = BulkPhase.ANALYZING,
            urls = parsed.urls, rules = rules, unsupported = parsed.unsupported,
            unsupportedUrls = parsed.unsupportedUrls,
            duplicates = parsed.duplicates))
        launchAnalysis()
        return null
    }

    /** May be changed even while the analysis is waiting between Instagram requests. */
    fun setDisableAnalysisPause(disabled: Boolean) {
        val current = _state.value
        if (current.phase !in setOf(BulkPhase.ANALYZING, BulkPhase.PREVIEW)) return
        if (current.rules.disableAnalysisPause == disabled) return
        set(current.copy(rules = current.rules.copy(disableAnalysisPause = disabled)))
    }

    fun stopAnalysis() {
        if (_state.value.phase != BulkPhase.ANALYZING) return
        worker?.cancel()
        set(_state.value.copy(phase = BulkPhase.PREVIEW))
    }

    fun toggle(key: String) {
        if (_state.value.phase != BulkPhase.PREVIEW) return
        set(_state.value.copy(candidates = _state.value.candidates.map {
            if (it.key == key) it.copy(selected = !it.selected) else it
        }))
    }

    fun setAllSelected(selected: Boolean) {
        if (_state.value.phase != BulkPhase.PREVIEW) return
        set(_state.value.copy(candidates = BulkCandidateSelection.setAll(_state.value.candidates, selected)))
    }

    fun discardPreview() {
        if (_state.value.phase == BulkPhase.PREVIEW) set(BulkState())
    }

    fun openJob(jobId: String) {
        if (_state.value.phase in setOf(BulkPhase.ANALYZING, BulkPhase.QUEUING, BulkPhase.PAUSED)) return
        store.load(jobId)?.let(::set)
    }

    fun clearHistory() {
        if (_state.value.phase in setOf(BulkPhase.ANALYZING, BulkPhase.QUEUING, BulkPhase.PAUSED)) return
        store.clear()
        _state.value = BulkState()
        _history.value = emptyList()
    }

    fun retryFailed() {
        if (_state.value.phase != BulkPhase.FINISHED) return
        val ids = _state.value.candidates.mapNotNull { it.downloadId }
        scope.launch {
            for (id in ids) {
                if (repository.downloadById(id)?.state == DownloadState.FAILED) repository.retry(id)
            }
        }
    }

    fun confirm() {
        val current = _state.value
        if (current.phase != BulkPhase.PREVIEW || current.selectedCount == 0) return
        set(current.copy(phase = BulkPhase.QUEUING))
        launchQueue()
    }

    fun pause() {
        if (_state.value.phase != BulkPhase.QUEUING) return
        worker?.cancel()
        val paused = _state.value.copy(phase = BulkPhase.PAUSED)
        set(paused)
        scope.launch {
            for (candidate in paused.candidates.filter { it.selected })
                repository.cancelQueuedDownload(stableDownloadId(paused.jobId, candidate))
        }
    }

    fun resume() {
        if (_state.value.phase != BulkPhase.PAUSED) return
        set(_state.value.copy(phase = BulkPhase.QUEUING))
        scope.launch {
            _state.value.candidates.mapNotNull { it.downloadId }.forEach { id ->
                if (repository.downloadById(id)?.state == DownloadState.CANCELLED) repository.retry(id)
            }
            launchQueue()
        }
    }

    fun stop() {
        if (_state.value.phase !in setOf(BulkPhase.QUEUING, BulkPhase.PAUSED)) return
        worker?.cancel()
        val stopped = _state.value.copy(phase = BulkPhase.STOPPED)
        set(stopped)
        scope.launch {
            for (candidate in stopped.candidates.filter { it.selected })
                repository.cancelQueuedDownload(stableDownloadId(stopped.jobId, candidate))
        }
    }

    private fun launchAnalysis() {
        worker?.cancel()
        worker = scope.launch {
            ActiveDownloadService.start(context)
            while (_state.value.phase == BulkPhase.ANALYZING && _state.value.cursor < _state.value.urls.size) {
                val current = _state.value
                val url = current.urls[current.cursor]
                val instagramWithSession = BulkLinks.provider(url) == "instagram" &&
                    repository.sessions.isLoggedIn()
                if (!current.rules.accepts(BulkLinks.provider(url).orEmpty())) {
                    set(current.copy(cursor = current.cursor + 1,
                        errors = current.errors + "$url — источник выключен в настройках"))
                    continue
                }
                try {
                    if (BulkLinks.provider(url) == "instagram") {
                        // Check the persisted switch while waiting so turning off the pause takes
                        // effect immediately (at most 250 ms), not after the original 20 seconds.
                        while (_state.value.phase == BulkPhase.ANALYZING) {
                            val live = _state.value
                            val remaining = BulkPacing.waitMillis(
                                live.lastInstagramAttemptAt,
                                System.currentTimeMillis(),
                                live.rules.disableAnalysisPause,
                            )
                            if (remaining == 0L) break
                            delay(minOf(remaining, 250L))
                        }
                        if (_state.value.phase != BulkPhase.ANALYZING) break
                        set(_state.value.copy(lastInstagramAttemptAt = System.currentTimeMillis()))
                    }
                    val found = retryOnce(url) { repository.resolve(url) }
                        .map { if (it.sourcePageUrl == null) it.copy(sourcePageUrl = url) else it }
                    val report = BulkSelection.withReport(found, current.rules)
                    val fresh = mutableListOf<MediaItem>()
                    for (item in report.items) if (!repository.alreadyDownloaded(item)) fresh.add(item)
                    val candidates = fresh.mapIndexed { index, item ->
                        BulkCandidate("${current.cursor}:$index:${item.id}", item)
                    }
                    val qualityMessage = if (report.videosWithoutSuitableQuality > 0)
                        listOf("$url — видео без подтверждённого качества не выше " +
                            "${current.rules.maxHeight}p: ${report.videosWithoutSuitableQuality}" +
                            " (с неизвестным разрешением: ${report.videosWithoutKnownQuality})")
                        else emptyList()
                    val emptyMessage = if (candidates.isEmpty() && qualityMessage.isEmpty())
                        listOf("$url — нет новых файлов выбранных типов") else emptyList()
                    val errors = current.errors + qualityMessage + emptyMessage
                    set(_state.value.copy(cursor = current.cursor + 1,
                        candidates = _state.value.candidates + candidates, errors = errors))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: ProviderException) {
                    if (error.providerId == "instagram" &&
                        (error.reason == ProviderException.Reason.RATE_LIMITED ||
                            (error.reason == ProviderException.Reason.ACCESS_REQUIRED && instagramWithSession))) {
                        set(_state.value.copy(phase = BulkPhase.PREVIEW,
                            errors = _state.value.errors +
                                "$url — Instagram ограничил запросы или требует проверку аккаунта. Анализ остановлен; повторите позже."))
                        break
                    }
                    set(_state.value.copy(cursor = current.cursor + 1,
                        errors = _state.value.errors + "$url — ${error.message ?: "ошибка анализа"}"))
                } catch (error: Exception) {
                    set(_state.value.copy(cursor = current.cursor + 1,
                        errors = _state.value.errors + "$url — ${error.message ?: "ошибка анализа"}"))
                }
            }
            if (_state.value.phase == BulkPhase.ANALYZING) set(_state.value.copy(phase = BulkPhase.PREVIEW))
        }
    }

    private suspend fun <T> retryOnce(url: String, block: suspend () -> T): T = try { block() }
        catch (error: Exception) {
            if (error is CancellationException) throw error
            if (BulkLinks.provider(url) == "instagram") throw error
            if (error is ProviderException && error.reason != ProviderException.Reason.TEMPORARY_FAILURE)
                throw error
            delay(1000)
            block()
        }

    private fun launchQueue() {
        worker?.cancel()
        worker = scope.launch {
            ActiveDownloadService.start(context)
            for (candidate in _state.value.candidates.filter { it.selected && it.downloadId == null }) {
                if (_state.value.phase != BulkPhase.QUEUING) break
                val id = stableDownloadId(_state.value.jobId, candidate)
                try {
                    if (repository.alreadyDownloaded(candidate.item)) {
                        set(_state.value.copy(candidates = _state.value.candidates.map {
                            if (it.key == candidate.key) it.copy(selected = false) else it
                        }))
                        continue
                    }
                    repository.enqueueBulk(listOf(candidate.item), listOf(id),
                        listOf(BulkDownloadGroup.id(_state.value.jobId, candidate.item)))
                    set(_state.value.copy(candidates = _state.value.candidates.map {
                        if (it.key == candidate.key) it.copy(downloadId = id) else it
                    }))
                    if (_state.value.phase == BulkPhase.QUEUING &&
                        repository.downloadById(id)?.state == DownloadState.CANCELLED) repository.retry(id)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    set(_state.value.copy(candidates = _state.value.candidates.map {
                        if (it.key == candidate.key) it.copy(selected = false) else it
                    }, errors = _state.value.errors +
                        "${candidate.item.originalName} — ${error.message ?: "ошибка очереди"}"))
                }
            }
            // Download rows determine when the confirmed job is actually finished.
        }
    }

    private fun set(value: BulkState) {
        store.save(value)
        _state.value = value
        _history.value = store.history()
    }

    private fun stableDownloadId(jobId: String, candidate: BulkCandidate): String =
        UUID.nameUUIDFromBytes("$jobId:${candidate.key}".toByteArray()).toString()
}

private class BulkStore(context: Context) : SQLiteOpenHelper(context, "bulk-jobs.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE bulk_state (id INTEGER PRIMARY KEY AUTOINCREMENT, jobId TEXT NOT NULL UNIQUE, value TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE bulk_state RENAME TO bulk_state_old")
            onCreate(db)
            db.rawQuery("SELECT value FROM bulk_state_old WHERE id=1", null).use { cursor ->
                if (cursor.moveToFirst()) {
                    val value = cursor.getString(0)
                    val jobId = runCatching { JSONObject(value).getString("jobId") }.getOrDefault("")
                    db.execSQL("INSERT INTO bulk_state(jobId,value) VALUES(?,?)", arrayOf(jobId, value))
                }
            }
            db.execSQL("DROP TABLE bulk_state_old")
        }
    }

    fun save(state: BulkState) {
        val value = JSONObject().apply {
            put("jobId", state.jobId); put("phase", state.phase.name)
            put("createdAtEpochMillis", state.createdAtEpochMillis)
            put("urls", JSONArray(state.urls)); put("cursor", state.cursor)
            put("unsupported", state.unsupported); put("duplicates", state.duplicates)
            put("lastInstagramAttemptAt", state.lastInstagramAttemptAt)
            put("unsupportedUrls", JSONArray(state.unsupportedUrls))
            put("errors", JSONArray(state.errors))
            put("rules", JSONObject().apply {
                put("instagram", state.rules.instagram); put("youtube", state.rules.youtube)
                put("vk", state.rules.vk); put("rutube", state.rules.rutube)
                put("photo", state.rules.photo); put("video", state.rules.video)
                put("audio", state.rules.audio); put("maxHeight", state.rules.maxHeight ?: -1)
                put("disableAnalysisPause", state.rules.disableAnalysisPause)
            })
            put("candidates", JSONArray(state.candidates.map { candidate -> JSONObject().apply {
                put("key", candidate.key); put("selected", candidate.selected)
                put("downloadId", candidate.downloadId)
                put("item", JSONObject().apply {
                    val item = candidate.item
                    put("id", item.id); put("providerId", item.providerId)
                    put("type", item.type.name); put("originalName", item.originalName)
                    put("downloadUrl", item.downloadUrl); put("previewUrl", item.previewUrl)
                    put("sizeBytes", item.sizeBytes); put("audioAvailable", item.audioAvailable)
                    put("audioArtist", item.audioArtist); put("audioTitle", item.audioTitle)
                    put("audioArtworkUrl", item.audioArtworkUrl); put("sourceGroupId", item.sourceGroupId)
                    put("position", item.position); put("customName", item.customName)
                    put("qualityLabel", item.qualityLabel); put("formatSelector", item.formatSelector)
                    put("group", item.group); put("sourcePageUrl", item.sourcePageUrl)
                    put("preselected", item.preselected)
                })
            } }))
        }
        writableDatabase.execSQL("INSERT OR REPLACE INTO bulk_state(jobId,value) VALUES(?,?)",
            arrayOf(state.jobId, value.toString()))
    }

    fun load(): BulkState = runCatching {
        readableDatabase.rawQuery("SELECT value FROM bulk_state ORDER BY id DESC LIMIT 1", null).use { cursor ->
            if (!cursor.moveToFirst()) return BulkState()
            parse(cursor.getString(0))
        }
    }.getOrDefault(BulkState())

    fun load(jobId: String): BulkState? = runCatching {
        readableDatabase.rawQuery("SELECT value FROM bulk_state WHERE jobId=? LIMIT 1", arrayOf(jobId)).use { cursor ->
            if (!cursor.moveToFirst()) null else parse(cursor.getString(0))
        }
    }.getOrNull()

    fun history(): List<BulkState> = runCatching {
        readableDatabase.rawQuery("SELECT value FROM bulk_state WHERE jobId != '' ORDER BY id DESC LIMIT 30", null)
            .use { cursor -> buildList { while (cursor.moveToNext()) add(parse(cursor.getString(0))) } }
    }.getOrDefault(emptyList())

    fun clear() {
        writableDatabase.delete("bulk_state", null, null)
    }

    private fun parse(value: String): BulkState {
            val root = JSONObject(value)
            val rules = root.getJSONObject("rules")
            return BulkState(
                jobId = root.getString("jobId"),
                createdAtEpochMillis = root.optLong("createdAtEpochMillis", 0L),
                phase = BulkPhase.valueOf(root.getString("phase")),
                urls = root.getJSONArray("urls").strings(), cursor = root.getInt("cursor"),
                unsupported = root.getInt("unsupported"), duplicates = root.getInt("duplicates"),
                lastInstagramAttemptAt = root.optLong("lastInstagramAttemptAt", 0L),
                unsupportedUrls = root.optJSONArray("unsupportedUrls")?.strings().orEmpty(),
                errors = root.getJSONArray("errors").strings(),
                rules = BulkRules(rules.getBoolean("instagram"), rules.getBoolean("youtube"),
                    rules.getBoolean("vk"), rules.getBoolean("rutube"), rules.getBoolean("photo"),
                    rules.getBoolean("video"), rules.getBoolean("audio"),
                    rules.getInt("maxHeight").takeIf { it >= 0 },
                    rules.optBoolean("disableAnalysisPause", false)),
                candidates = root.getJSONArray("candidates").objects().map { row ->
                    val item = row.getJSONObject("item")
                    BulkCandidate(row.getString("key"), MediaItem(
                        id = item.getString("id"), providerId = item.getString("providerId"),
                        type = MediaType.valueOf(item.getString("type")),
                        originalName = item.getString("originalName"), downloadUrl = item.getString("downloadUrl"),
                        previewUrl = item.nullableString("previewUrl"), sizeBytes = item.nullableLong("sizeBytes"),
                        audioAvailable = if (item.isNull("audioAvailable")) null else item.getBoolean("audioAvailable"),
                        audioArtist = item.nullableString("audioArtist"), audioTitle = item.nullableString("audioTitle"),
                        audioArtworkUrl = item.nullableString("audioArtworkUrl"),
                        sourceGroupId = item.nullableString("sourceGroupId"),
                        position = if (item.isNull("position")) null else item.getInt("position"),
                        customName = item.getBoolean("customName"), qualityLabel = item.nullableString("qualityLabel"),
                        formatSelector = item.nullableString("formatSelector"), group = item.nullableString("group"),
                        sourcePageUrl = item.nullableString("sourcePageUrl"), preselected = item.getBoolean("preselected"),
                    ), row.getBoolean("selected"), row.nullableString("downloadId"))
                },
            )
    }

    private fun JSONObject.nullableString(key: String): String? = if (isNull(key)) null else getString(key)
    private fun JSONObject.nullableLong(key: String): Long? = if (isNull(key)) null else getLong(key)
    private fun JSONArray.strings(): List<String> = (0 until length()).map(::getString)
    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map(::getJSONObject)
}
