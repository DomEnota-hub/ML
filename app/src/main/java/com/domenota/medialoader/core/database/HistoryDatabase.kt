package com.domenota.medialoader.core.database

import androidx.room.Dao
import androidx.room.Database
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import kotlinx.coroutines.flow.Flow

/** One row of download history. State and type use the shared enums, never raw strings. */
@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val id: String,
    val providerId: String,
    val originalName: String,
    val mediaType: MediaType,
    val state: DownloadState,
    val savedUri: String?,
    val sizeBytes: Long?,
    val createdAtEpochMillis: Long,
    val bytesDownloaded: Long = 0,
    val errorMessage: String? = null,
    val sourceUrl: String? = null,
    val sourcePageUrl: String? = null,
    val bulkMediaKey: String? = null,
    @ColumnInfo(defaultValue = "0") val hidden: Boolean = false,
    val previewUrl: String? = null,
    val groupId: String? = null,
    val formatSelector: String? = null,
    val sourceAudioArtist: String? = null,
    val sourceAudioTitle: String? = null,
    val sourceAudioArtworkUrl: String? = null,
    val recognizedArtist: String? = null,
    val recognizedTitle: String? = null,
    val recognizedAlbum: String? = null,
    val recognitionTrackId: String? = null,
    val recognitionSource: String? = null,
    val recognitionArtworkUrl: String? = null,
    val derivedFromId: String? = null,
    val durationMs: Long? = null,
    @ColumnInfo(defaultValue = "0") val isPrivate: Boolean = false,
    val privateSourceUri: String? = null,
)

@Entity(tableName = "settings")
data class SettingsEntity(
    @PrimaryKey val key: String,
    val value: String,
)

@Entity(tableName = "catalog_folders", indices = [Index(value = ["nameKey"], unique = true)])
data class CatalogFolderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val nameKey: String,
    val createdAtEpochMillis: Long,
)

@Entity(
    tableName = "catalog_assignments",
    foreignKeys = [
        ForeignKey(entity = CatalogFolderEntity::class, parentColumns = ["id"],
            childColumns = ["folderId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index(value = ["folderId"])],
)
data class CatalogAssignmentEntity(
    @PrimaryKey val downloadId: String,
    val folderId: String?,
    val isFavorite: Boolean,
)

class DatabaseConverters {
    @TypeConverter
    fun stateToString(value: DownloadState): String = value.name

    /** Unknown values (for example from a newer app version) are shown as failed instead of crashing. */
    @TypeConverter
    fun stringToState(value: String): DownloadState =
        DownloadState.values().firstOrNull { it.name == value } ?: DownloadState.FAILED

    @TypeConverter
    fun typeToString(value: MediaType): String = value.name

    @TypeConverter
    fun stringToType(value: String): MediaType =
        MediaType.values().firstOrNull { it.name == value } ?: MediaType.PHOTO
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads WHERE hidden = 0 AND isPrivate = 0 ORDER BY createdAtEpochMillis DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE hidden = 0 AND isPrivate = 1 ORDER BY createdAtEpochMillis DESC")
    fun observePrivate(): Flow<List<DownloadEntity>>

    @Query("DELETE FROM downloads WHERE id = :id AND isPrivate = 1")
    suspend fun deletePrivate(id: String)

    @Query("SELECT * FROM downloads WHERE hidden = 1 AND isPrivate = 0 ORDER BY createdAtEpochMillis DESC")
    fun observeHidden(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads ORDER BY createdAtEpochMillis DESC")
    suspend fun all(): List<DownloadEntity>

    @Query("UPDATE downloads SET durationMs = :duration WHERE id = :id")
    suspend fun setDuration(id: String, duration: Long)

    @Query("SELECT * FROM downloads WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): DownloadEntity?

    @Query("UPDATE downloads SET previewUrl = :artworkUrl, sourceAudioArtworkUrl = :artworkUrl " +
        "WHERE id = :id AND providerId = 'muzmo' AND state = 'COMPLETED' AND previewUrl IS NULL")
    suspend fun setMuzmoArtworkIfMissing(id: String, artworkUrl: String): Int

    @Query("SELECT COUNT(*) FROM downloads WHERE hidden = 1 AND isPrivate = 0")
    fun observeHiddenCount(): Flow<Int>

    @Query("UPDATE downloads SET hidden = 0 WHERE hidden = 1 AND isPrivate = 0")
    suspend fun restoreHidden()

    @Query("DELETE FROM downloads WHERE id = :id AND hidden = 1 AND isPrivate = 0")
    suspend fun deleteHidden(id: String)

    /** Delete a stale history row only. Never touches the physical file or system trash. */
    @Query("DELETE FROM downloads WHERE id = :id AND state = 'COMPLETED' AND hidden = 0 AND isPrivate = 0")
    suspend fun deleteMissingHistoryRow(id: String)

    @Query("DELETE FROM downloads WHERE hidden = 1 AND isPrivate = 0")
    suspend fun clearHidden()

    @Query("DELETE FROM downloads WHERE hidden = 0 AND isPrivate = 0 AND state IN ('COMPLETED', 'FAILED', 'CANCELLED')")
    suspend fun clearHistory()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: DownloadEntity)
}

@Dao
interface SettingsDao {
    @Query("SELECT * FROM settings WHERE `key` = :key LIMIT 1")
    suspend fun get(key: String): SettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: SettingsEntity)
}

@Dao
interface CatalogDao {
    @Query("SELECT * FROM catalog_folders ORDER BY createdAtEpochMillis ASC")
    fun observeFolders(): Flow<List<CatalogFolderEntity>>

    @Query("SELECT * FROM catalog_assignments")
    fun observeAssignments(): Flow<List<CatalogAssignmentEntity>>

    @Query("SELECT * FROM catalog_folders WHERE id = :id LIMIT 1")
    suspend fun folderById(id: String): CatalogFolderEntity?

    @Query("SELECT * FROM catalog_folders WHERE nameKey = :key LIMIT 1")
    suspend fun folderByKey(key: String): CatalogFolderEntity?

    @Query("SELECT * FROM catalog_assignments WHERE downloadId = :id LIMIT 1")
    suspend fun assignmentById(id: String): CatalogAssignmentEntity?

    @Insert
    suspend fun insertFolder(folder: CatalogFolderEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveAssignment(assignment: CatalogAssignmentEntity)

    @Query("UPDATE catalog_folders SET name = :name, nameKey = :key WHERE id = :id")
    suspend fun renameFolder(id: String, name: String, key: String)

    @Query("DELETE FROM catalog_folders WHERE id = :id")
    suspend fun deleteFolder(id: String)

    @Query("DELETE FROM catalog_assignments WHERE downloadId = :id")
    suspend fun removeAssignment(id: String)

    @Query("DELETE FROM catalog_assignments WHERE downloadId NOT IN (SELECT id FROM downloads)")
    suspend fun pruneOrphans()
}

@Database(entities = [DownloadEntity::class, SettingsEntity::class,
    CatalogFolderEntity::class, CatalogAssignmentEntity::class], version = 12, exportSchema = false)
@TypeConverters(DatabaseConverters::class)
abstract class HistoryDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao
    abstract fun settingsDao(): SettingsDao
    abstract fun catalogDao(): CatalogDao
}
