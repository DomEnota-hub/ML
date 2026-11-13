package com.domenota.medialoader.core.database

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName="media_collections")
data class MediaCollectionEntity(@PrimaryKey val id:String,val name:String,val createdAt:Long)

@Entity(tableName="media_collection_members",foreignKeys=[ForeignKey(entity=MediaCollectionEntity::class,
    parentColumns=["id"],childColumns=["collectionId"],onDelete=ForeignKey.CASCADE)],indices=[Index("collectionId")])
data class MediaCollectionMemberEntity(@PrimaryKey val id:String,val collectionId:String,val identityJson:String?,
    val link:String?,val addedAt:Long)

@Entity(tableName="media_review")
data class MediaReviewEntity(@PrimaryKey val downloadId:String,val completedAt:Long,val reviewed:Boolean=false)

@Entity(tableName="media_operations",indices=[Index("mediaId")])
data class MediaOperationEntity(@PrimaryKey val id:String,val mediaId:String,val operation:String,val occurredAt:Long)

@Dao
interface MediaOrganizationDao {
    @Query("SELECT * FROM media_collections ORDER BY createdAt DESC,id")
    fun observeCollections():Flow<List<MediaCollectionEntity>>
    @Query("SELECT * FROM media_collection_members ORDER BY addedAt,id")
    fun observeMembers():Flow<List<MediaCollectionMemberEntity>>
    @Query("SELECT * FROM media_collection_members WHERE collectionId=:id ORDER BY addedAt,id")
    suspend fun members(id:String):List<MediaCollectionMemberEntity>
    @Query("SELECT * FROM media_collections WHERE id=:id")
    suspend fun collection(id:String):MediaCollectionEntity?
    @Insert suspend fun insertCollection(row:MediaCollectionEntity)
    @Query("UPDATE media_collections SET name=:name WHERE id=:id")
    suspend fun rename(id:String,name:String):Int
    @Query("DELETE FROM media_collections WHERE id=:id")
    suspend fun deleteCollection(id:String)
    @Insert suspend fun insertMember(row:MediaCollectionMemberEntity)
    @Query("DELETE FROM media_collection_members WHERE collectionId=:collectionId AND id=:id")
    suspend fun removeMember(collectionId:String,id:String)
    @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun insertReview(row:MediaReviewEntity)
    @Query("SELECT * FROM media_review WHERE reviewed=0 ORDER BY completedAt DESC,downloadId")
    fun observeReview():Flow<List<MediaReviewEntity>>
    @Query("UPDATE media_review SET reviewed=:reviewed WHERE downloadId IN (:ids)")
    suspend fun setReviewed(ids:List<String>,reviewed:Boolean)
    @Insert suspend fun insertOperation(row:MediaOperationEntity)
    @Query("SELECT * FROM media_operations WHERE mediaId=:id ORDER BY occurredAt DESC")
    fun observeOperations(id:String):Flow<List<MediaOperationEntity>>
}

internal val MEDIA_ORGANIZATION_MIGRATION=object:Migration(12,13){
    override fun migrate(db:SupportSQLiteDatabase){
        db.execSQL("CREATE TABLE IF NOT EXISTS media_collections (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, createdAt INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS media_collection_members (id TEXT NOT NULL PRIMARY KEY, collectionId TEXT NOT NULL, identityJson TEXT, link TEXT, addedAt INTEGER NOT NULL, FOREIGN KEY(collectionId) REFERENCES media_collections(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_media_collection_members_collectionId ON media_collection_members(collectionId)")
        db.execSQL("CREATE TABLE IF NOT EXISTS media_review (downloadId TEXT NOT NULL PRIMARY KEY, completedAt INTEGER NOT NULL, reviewed INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS media_operations (id TEXT NOT NULL PRIMARY KEY, mediaId TEXT NOT NULL, operation TEXT NOT NULL, occurredAt INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_media_operations_mediaId ON media_operations(mediaId)")
    }
}
