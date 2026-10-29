package com.domenota.medialoader.data.privatevault

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import com.domenota.medialoader.core.database.HistoryDatabase
import com.domenota.medialoader.core.database.PRIVATE_HISTORY_MIGRATION
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
@Config(sdk = [28])
class PrivateHistoryMigrationTest {
    @Test fun version11RowsSurviveAsPublicAndNewPrivateColumnsAreWritable() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        // Reconstruct v11 from Room's complete schema, retaining all unrelated table/index details.
        val reference = Room.inMemoryDatabaseBuilder(context, HistoryDatabase::class.java)
            .allowMainThreadQueries().build()
        val schema = try {
            reference.openHelper.writableDatabase.query(
                "SELECT sql FROM sqlite_master WHERE sql IS NOT NULL AND " +
                    "name IN ('downloads', 'settings', 'catalog_folders', 'catalog_assignments', " +
                    "'index_catalog_folders_nameKey', 'index_catalog_assignments_folderId')",
            ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        } finally { reference.close() }
        assertEquals(6, schema.size)
        val name = "private-migration-${UUID.randomUUID()}.db"
        val path = context.getDatabasePath(name).also { it.parentFile?.mkdirs() }
        var migrated: HistoryDatabase? = null
        try {
            SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
                schema.forEach { sql ->
                    val oldSql = sql
                        .replace(Regex("`isPrivate` INTEGER NOT NULL DEFAULT (?:\\(0\\)|0),\\s*"), "")
                        .replace(Regex("`privateSourceUri` TEXT,\\s*"), "")
                    assertFalse(oldSql.contains("`isPrivate`"))
                    assertFalse(oldSql.contains("`privateSourceUri`"))
                    old.execSQL(oldSql)
                }
                val values = ContentValues().apply {
                    put("id", "existing-download")
                    put("providerId", "instagram")
                    put("originalName", "retained.mp4")
                    put("mediaType", "VIDEO")
                    put("state", "COMPLETED")
                    put("savedUri", "content://media/existing")
                    put("sizeBytes", 1234L)
                    put("createdAtEpochMillis", 42L)
                    put("bytesDownloaded", 1234L)
                }
                assertTrue(old.insertOrThrow("downloads", null, values) > 0)
                old.version = 11
            }
            val opened = Room.databaseBuilder(context, HistoryDatabase::class.java, name)
                .addMigrations(PRIVATE_HISTORY_MIGRATION).allowMainThreadQueries().build()
            migrated = opened
            val dao = opened.downloadDao()
            val retained = requireNotNull(dao.byId("existing-download"))
            assertEquals("retained.mp4", retained.originalName)
            assertEquals("content://media/existing", retained.savedUri)
            assertEquals(1234L, retained.sizeBytes)
            assertFalse(retained.isPrivate)
            assertNull(retained.privateSourceUri)
            assertEquals(listOf(retained), dao.observeAll().first())
            assertTrue(dao.observePrivate().first().isEmpty())
            dao.upsert(retained.copy(isPrivate = true, savedUri = "file:///private/new.mp4",
                privateSourceUri = retained.savedUri))
            val privateRow = requireNotNull(dao.byId(retained.id))
            assertTrue(privateRow.isPrivate)
            assertEquals("content://media/existing", privateRow.privateSourceUri)
            assertTrue(dao.observeAll().first().isEmpty())
            assertEquals(listOf(privateRow), dao.observePrivate().first())
            assertEquals(12, opened.openHelper.writableDatabase.version)
        } finally {
            migrated?.close()
            context.deleteDatabase(name)
        }
    }
}
