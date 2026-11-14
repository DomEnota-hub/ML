package com.domenota.medialoader.data

import com.domenota.medialoader.core.database.DownloadDao
import com.domenota.medialoader.core.database.CatalogDao
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import kotlinx.coroutines.flow.Flow
import androidx.room.withTransaction
import com.domenota.medialoader.core.database.HistoryDatabase
import com.domenota.medialoader.core.database.MediaReviewEntity
import com.domenota.medialoader.core.database.MediaOperationEntity

/** The only place that talks to the download table. */
class HistoryRepository(private val dao: DownloadDao, private val catalogDao: CatalogDao? = null,
    private val db: HistoryDatabase? = null) {
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
    /** Only the real download completion path enqueues review. Existing history,
     * metadata rewrites, imports and editor exports cannot create new inbox entries. */
    suspend fun completeDownload(entity:DownloadEntity,completedAt:Long) {
        require(entity.state==DownloadState.COMPLETED)
        if(db==null){dao.upsert(entity);return}
        db.withTransaction {
            val previous=dao.byId(entity.id)
            dao.upsert(entity)
            if(previous?.state==DownloadState.RUNNING && !entity.isPrivate && !entity.hidden &&
                !entity.savedUri.isNullOrBlank()) db.organizationDao().insertReview(MediaReviewEntity(entity.id,completedAt))
        }
    }
    /** Alternate download queue publishes a finished file without a RUNNING history row.
     * Only a genuinely new history ID creates a review entry. */
    suspend fun recordPublishedDownload(entity:DownloadEntity,completedAt:Long) {
        require(entity.state==DownloadState.COMPLETED)
        if(db==null){dao.upsert(entity);return}
        db.withTransaction {
            val previous=dao.byId(entity.id)
            dao.upsert(entity)
            if(previous==null&&!entity.isPrivate&&!entity.hidden&&!entity.savedUri.isNullOrBlank())
                db.organizationDao().insertReview(MediaReviewEntity(entity.id,completedAt))
        }
    }
    suspend fun saveDerived(entity:DownloadEntity,operation:String?) {
        if(db==null){dao.upsert(entity);return}
        db.withTransaction {
            dao.upsert(entity)
            if(!entity.isPrivate && !operation.isNullOrBlank())
                db.organizationDao().insertOperation(MediaOperationEntity(java.util.UUID.randomUUID().toString(),
                    entity.id,operation,entity.createdAtEpochMillis))
        }
    }
    /** Compare the complete snapshot in a Room transaction: concurrent privacy/rename/delete wins. */
    suspend fun restoreMissingFile(previous: DownloadEntity, uri: String, name: String, bytes: Long): Boolean {
        require(com.domenota.medialoader.data.download.MissingFileRecoveryPolicy.eligible(previous))
        suspend fun replace(): Boolean {
            if (dao.byId(previous.id) != previous) return false
            // Keep source URLs, time, group, recognition, folder/collection links and stable ID intact.
            if (previous.savedUri != null && previous.sizeBytes != null) {
                return dao.rebindSavedFileWhenCurrent(previous.id, previous.savedUri, previous.originalName,
                    previous.sizeBytes, uri, name, bytes) == 1
            }
            dao.upsert(previous.copy(savedUri = uri, originalName = name, sizeBytes = bytes))
            return true
        }
        return if (db != null) db.withTransaction { replace() } else replace()
    }

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
