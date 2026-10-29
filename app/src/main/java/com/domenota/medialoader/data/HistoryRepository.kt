package com.domenota.medialoader.data

import com.domenota.medialoader.core.database.DownloadDao
import com.domenota.medialoader.core.database.CatalogDao
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import kotlinx.coroutines.flow.Flow

/** The only place that talks to the download table. */
class HistoryRepository(private val dao: DownloadDao, private val catalogDao: CatalogDao? = null) {
    fun observePrivate(): Flow<List<DownloadEntity>> = dao.observePrivate()
    suspend fun deletePrivate(id: String) { dao.deletePrivate(id); catalogDao?.pruneOrphans() }
    fun observeAll(): Flow<List<DownloadEntity>> = dao.observeAll()
    fun observeHiddenCount(): Flow<Int> = dao.observeHiddenCount()
    fun observeHidden(): Flow<List<DownloadEntity>> = dao.observeHidden()

    suspend fun all(): List<DownloadEntity> = dao.all()

    suspend fun byId(id: String): DownloadEntity? = dao.byId(id)

    suspend fun setMuzmoArtworkIfMissing(id: String, artworkUrl: String): Int =
        dao.setMuzmoArtworkIfMissing(id, artworkUrl)

    suspend fun setDuration(id: String, duration: Long) = dao.setDuration(id, duration)
    suspend fun save(entity: DownloadEntity) = dao.upsert(entity)
    suspend fun hide(id: String) { dao.byId(id)?.let { dao.upsert(it.copy(hidden = true)) } }
    suspend fun restoreHidden() = dao.restoreHidden()
    suspend fun clearHidden() { dao.clearHidden(); catalogDao?.pruneOrphans() }
    suspend fun deleteHidden(id: String) { dao.deleteHidden(id); catalogDao?.pruneOrphans() }
    suspend fun deleteMissingHistoryRow(id: String) {
        dao.deleteMissingHistoryRow(id); catalogDao?.pruneOrphans()
    }
    suspend fun clearHistory() { dao.clearHistory(); catalogDao?.pruneOrphans() }

    /** Names of files that are queued or downloading, so they are not on disk yet but are already taken. */
    suspend fun reservedNames(): Set<String> = dao.all()
        .filter { it.state == DownloadState.QUEUED || it.state == DownloadState.RUNNING }
        .map { it.originalName }
        .toSet()
}
