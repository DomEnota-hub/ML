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
            val db=Room.databaseBuilder(context,HistoryDatabase::class.java,name).addMigrations(MEDIA_ORGANIZATION_MIGRATION).allowMainThreadQueries().build()
            opened=db
            assertEquals("old.jpg",db.downloadDao().byId("old")?.originalName)
            assertEquals("folder",db.catalogDao().assignmentById("old")?.folderId)
            assertTrue(db.catalogDao().assignmentById("old")!!.isFavorite)
            assertEquals(13,db.openHelper.writableDatabase.version)
            assertTrue(db.organizationDao().observeCollections().first().isEmpty())
            assertTrue(db.organizationDao().observeReview().first().isEmpty())
        }finally{opened?.close();context.deleteDatabase(name)}
    }
}
