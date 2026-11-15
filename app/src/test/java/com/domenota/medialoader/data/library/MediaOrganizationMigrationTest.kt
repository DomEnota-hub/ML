package com.domenota.medialoader.data.library

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import com.domenota.medialoader.core.database.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class MediaOrganizationMigrationTest {
    @Test fun v13MembersKeepTheirExistingOrderAndDatesWhenMigrated()=runBlocking{
        val context=RuntimeEnvironment.getApplication()
        val reference=Room.inMemoryDatabaseBuilder(context,HistoryDatabase::class.java).allowMainThreadQueries().build()
        val schema=try{reference.openHelper.writableDatabase.query(
            "SELECT name,sql FROM sqlite_master WHERE sql IS NOT NULL AND name NOT LIKE 'sqlite_%' AND name NOT IN ('room_master_table','android_metadata') ORDER BY CASE WHEN type='table' THEN 0 ELSE 1 END"
        ).use{c->buildList{while(c.moveToNext())add(c.getString(0) to c.getString(1))}}}finally{reference.close()}
        val name="collection-order-migration-${UUID.randomUUID()}.db"
        val path=context.getDatabasePath(name).also{it.parentFile?.mkdirs()}
        var opened:HistoryDatabase?=null
        try{
            SQLiteDatabase.openOrCreateDatabase(path,null).use{old->
                schema.forEach{(table,sql)->
                    old.execSQL(if(table=="media_collection_members")
                        "CREATE TABLE media_collection_members (id TEXT NOT NULL PRIMARY KEY, collectionId TEXT NOT NULL, identityJson TEXT, link TEXT, addedAt INTEGER NOT NULL, FOREIGN KEY(collectionId) REFERENCES media_collections(id) ON UPDATE NO ACTION ON DELETE CASCADE)"
                    else sql)
                }
                old.execSQL("INSERT INTO media_collections VALUES('c','Collection',100)")
                old.execSQL("INSERT INTO media_collection_members VALUES('b','c','[]',NULL,20)")
                old.execSQL("INSERT INTO media_collection_members VALUES('l','c',NULL,'https://example.com',15)")
                old.execSQL("INSERT INTO media_collection_members VALUES('a','c','[]',NULL,10)")
                old.version=13
            }
            fun open()=Room.databaseBuilder(context,HistoryDatabase::class.java,name).addMigrations(COLLECTION_ORDER_MIGRATION,COLLECTION_METADATA_MIGRATION).allowMainThreadQueries().build()
            val db=open();opened=db
            val original=db.organizationDao().members("c")
            assertEquals(listOf("a","l","b"),original.map{it.id})
            assertTrue(original.all{it.sortOrder==null && it.metadataJson==null})
            MediaOrganizationRepository(db).reorder("c",listOf("a","b"),listOf("b","a"))
            db.close();opened=null
            val restored=open();opened=restored
            val after=restored.organizationDao().members("c")
            assertEquals(listOf("b","l","a"),after.map{it.id})
            assertEquals(original.associate{it.id to it.addedAt},after.associate{it.id to it.addedAt})
            assertEquals("https://example.com",after[1].link)
            assertEquals(15,restored.openHelper.writableDatabase.version)
        }finally{opened?.close();context.deleteDatabase(name)}
    }
    @Test fun v14MemberMetadataMigrationPreservesExplicitOrderIdentityAndLinks()=runBlocking {
        val context=RuntimeEnvironment.getApplication()
        val reference=Room.inMemoryDatabaseBuilder(context,HistoryDatabase::class.java).allowMainThreadQueries().build()
        val schema=try{reference.openHelper.writableDatabase.query(
            "SELECT name,sql FROM sqlite_master WHERE sql IS NOT NULL AND name NOT LIKE 'sqlite_%' AND name NOT IN ('room_master_table','android_metadata') ORDER BY CASE WHEN type='table' THEN 0 ELSE 1 END"
        ).use{c->buildList{while(c.moveToNext())add(c.getString(0) to c.getString(1))}}}finally{reference.close()}
        val name="collection-metadata-migration-${UUID.randomUUID()}.db"
        val path=context.getDatabasePath(name).also{it.parentFile?.mkdirs()}
        var opened:HistoryDatabase?=null
        try {
            SQLiteDatabase.openOrCreateDatabase(path,null).use{old->
                schema.forEach{(table,sql)->old.execSQL(if(table=="media_collection_members")
                    "CREATE TABLE media_collection_members (id TEXT NOT NULL PRIMARY KEY, collectionId TEXT NOT NULL, identityJson TEXT, link TEXT, addedAt INTEGER NOT NULL, sortOrder INTEGER, FOREIGN KEY(collectionId) REFERENCES media_collections(id) ON UPDATE NO ACTION ON DELETE CASCADE)" else sql)}
                old.execSQL("INSERT INTO media_collections VALUES('c','Collection',100)")
                old.execSQL("INSERT INTO media_collection_members VALUES('a','c','[\"file-stat:1\"]',NULL,10,2)")
                old.execSQL("INSERT INTO media_collection_members VALUES('l','c',NULL,'https://example.com',20,1)")
                old.version=14
            }
            val db=Room.databaseBuilder(context,HistoryDatabase::class.java,name).addMigrations(COLLECTION_METADATA_MIGRATION).allowMainThreadQueries().build();opened=db
            val rows=db.organizationDao().members("c")
            assertEquals(listOf("l","a"),rows.map{it.id});assertEquals(listOf(20L,10L),rows.map{it.addedAt})
            assertEquals(setOf("file-stat:1"),MediaOrganizationPolicy.decode(rows[1].identityJson))
            assertEquals("https://example.com",rows[0].link);assertTrue(rows.all{it.metadataJson==null})
            assertEquals(15,db.openHelper.writableDatabase.version)
        } finally {opened?.close();context.deleteDatabase(name)}
    }
    @Test fun v12MediaAndCatalogSurviveWithoutInventingReviewHistory()=runBlocking{
        val context=RuntimeEnvironment.getApplication()
        val reference=Room.inMemoryDatabaseBuilder(context,HistoryDatabase::class.java).allowMainThreadQueries().build()
        val schema=try{reference.openHelper.writableDatabase.query(
            "SELECT sql FROM sqlite_master WHERE sql IS NOT NULL AND name IN ('downloads','settings','catalog_folders','catalog_assignments','index_catalog_folders_nameKey','index_catalog_assignments_folderId')"
        ).use{c->buildList{while(c.moveToNext())add(c.getString(0))}}}finally{reference.close()}
        val name="organization-migration-${UUID.randomUUID()}.db"
        val path=context.getDatabasePath(name).also{it.parentFile?.mkdirs()}
        var opened:HistoryDatabase?=null
        try{
            SQLiteDatabase.openOrCreateDatabase(path,null).use{old->
                schema.forEach{old.execSQL(it)}
                old.execSQL("INSERT INTO downloads(id,providerId,originalName,mediaType,state,savedUri,sizeBytes,createdAtEpochMillis,bytesDownloaded,hidden,isPrivate) VALUES('old','instagram','old.jpg','PHOTO','COMPLETED','content://media/old',100,100,100,0,0)")
                old.execSQL("INSERT INTO catalog_folders(id,name,nameKey,createdAtEpochMillis) VALUES('folder','Work','work',100)")
                old.execSQL("INSERT INTO catalog_assignments(downloadId,folderId,isFavorite) VALUES('old','folder',1)")
                old.version=12
            }
            val db=Room.databaseBuilder(context,HistoryDatabase::class.java,name).addMigrations(MEDIA_ORGANIZATION_MIGRATION,COLLECTION_ORDER_MIGRATION,COLLECTION_METADATA_MIGRATION).allowMainThreadQueries().build()
            opened=db
            assertEquals("old.jpg",db.downloadDao().byId("old")?.originalName)
            assertEquals("folder",db.catalogDao().assignmentById("old")?.folderId)
            assertTrue(db.catalogDao().assignmentById("old")!!.isFavorite)
            assertEquals(15,db.openHelper.writableDatabase.version)
            assertTrue(db.organizationDao().observeCollections().first().isEmpty())
            assertTrue(db.organizationDao().observeReview().first().isEmpty())
        }finally{opened?.close();context.deleteDatabase(name)}
    }
}
