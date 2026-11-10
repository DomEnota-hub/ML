package com.domenota.medialoader.data.storage

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import androidx.room.Room
import com.domenota.medialoader.core.database.*
import com.domenota.medialoader.core.model.*
import com.domenota.medialoader.data.playback.VideoWatchProgress
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowStatFs

/** Exercises the real journal/Room/reference coordinator; only Android's provider is simulated.
 * Content is synthetic: codec validation before PUBLICATION_INTENT is outside this test's scope. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HistoricalRecoveryPublicationFlowTest {
    @Test fun androidPendingNameCanBePublishedAndAllLaterStagesCanResume() = runBlocking {
        for (mime in listOf("image/jpeg", "video/mp4")) {
            for (stage in listOf("NEW_RESERVED", "NEW_WRITTEN", "PUBLICATION_INTENT", "PUBLISHED", "MAIN_INTENT", "MAIN_REBOUND",
                "LINKS_REBOUND", "DELETE_INTENT", "OLD_DELETED", "DONE")) {
                fixture(mime) { f ->
                    f.journalFile.writeText(f.journal().apply {
                        getJSONObject("operation").put("stage", "NEW_RESERVED")
                    }.toString())
                    var interrupted = false
                    f.engine.resume {
                        if (!interrupted && f.journal().getJSONObject("operation").getString("stage") == stage) {
                            interrupted = true
                            throw IllegalStateException("QA interruption before $stage")
                        }
                    }
                    assertTrue("Did not reach $mime/$stage: ${f.journal().optString("message")}", interrupted)
                    val result = f.engine.resume { }
                    f.assertComplete("$mime/$stage: $result")
                }
            }
        }
    }

    @Test fun publicationAndDeletionCanCommitBeforeProviderReportsFailure() = runBlocking {
        for (fault in listOf("publish", "delete")) fixture("image/jpeg") { f ->
            f.provider.failAfter = fault
            f.engine.resume { }
            assertFalse(f.journal().getBoolean("done"))
            assertTrue(f.backup.exists())
            assertEquals(if (fault == "publish") "PUBLICATION_INTENT" else "DELETE_INTENT",
                f.journal().getJSONObject("operation").getString("stage"))
            f.assertComplete(f.engine.resume { })
            assertEquals(1, f.provider.deletes)
        }
    }

    private suspend fun pause(f: Fixture, stage: String) {
        var stopped = false
        f.engine.resume {
            if (!stopped && f.journal().getJSONObject("operation").getString("stage") == stage) {
                stopped = true
                error("QA pause at $stage")
            }
        }
        assertTrue("Stage not reached: $stage", stopped)
    }

    @Test fun historyCasCanCommitBeforeItsCheckpoint() = runBlocking {
        fixture("image/jpeg") { f ->
            pause(f, "MAIN_INTENT")
            assertEquals(1, f.db.downloadDao().rebindSavedFileWhenCurrent(f.row.id, f.old.toString(),
                f.row.originalName, f.row.sizeBytes!!, f.new.toString(), f.row.originalName,
                f.provider.entries.getValue(43).file.length()))
            f.assertComplete(f.engine.resume { })
        }
    }

    @Test fun allReferenceStoresCanCommitBeforeTheirCheckpoint() = runBlocking {
        fixture("video/mp4") { f ->
            pause(f, "MAIN_REBOUND")
            val op = f.journal().getJSONObject("operation")
            HistoricalReferenceLinks(f.context, f.db).rebind(op.getJSONObject("links"),
                f.old.toString(), f.new.toString(), op.getString("oldHash"), op.getString("newHash"),
                f.row.originalName, op.getLong("newBytes"))
            f.assertComplete(f.engine.resume { })
        }
    }

    @Test fun ambiguousCatalogKeepsOriginalAndHistoryBeforePublication() = runBlocking {
        fixture("image/jpeg") { f ->
            val prefs = f.context.getSharedPreferences("external_media_catalog", 0)
            val changed = JSONObject(prefs.getString("catalog", null)!!).apply {
                val entries = getJSONArray("entries")
                entries.put(JSONObject(entries.getJSONObject(0).toString()).put("key", "duplicate"))
            }.toString()
            prefs.edit().putString("catalog", changed).commit()
            f.engine.resume { }
            assertFalse(f.journal().getBoolean("done"))
            assertEquals(f.old.toString(), f.db.downloadDao().byId("stable-id")!!.savedUri)
            assertTrue(f.provider.entries.containsKey(42L))
            assertTrue(f.provider.entries.getValue(43L).pending)
            assertEquals(changed, prefs.getString("catalog", null))
        }
    }

    @Test fun legitimateEditsAndAliasMaintenanceWhilePausedArePreserved() = runBlocking {
        fixture("video/mp4") { f ->
            val catalog = f.context.getSharedPreferences("external_media_catalog", 0)
            val changed = JSONObject(catalog.getString("catalog", null)!!).apply {
                getJSONArray("entries").getJSONObject(0).put("favorite", false)
            }
            catalog.edit().putString("catalog", changed.toString()).commit()
            val playlists = f.context.getSharedPreferences("media_playlists", 0)
            val updated = JSONArray(playlists.getString("playlists", null)!!).apply {
                getJSONObject(0).put("trackIds", JSONArray(listOf("stable-id")))
                    .put("trackReferences", JSONObject().put("stable-id", JSONArray(listOf("phone:video:42", "stable-id"))))
            }
            playlists.edit().putString("playlists", updated.toString()).commit()
            assertTrue(f.engine.resume { }, f.journal().getBoolean("done"))
            val after = JSONObject(catalog.getString("catalog", null)!!).getJSONArray("entries").getJSONObject(0)
            assertFalse(after.getBoolean("favorite"))
            assertEquals(f.new.toString(), after.getString("uri"))
            assertEquals("stable-id", JSONArray(playlists.getString("playlists", null)!!)
                .getJSONObject(0).getJSONArray("trackIds").getString(0))
        }
    }

    @Test fun delayedUnconfirmedIndexCannotDeleteOriginalAndCanResumeLater() = runBlocking {
        fixture("image/jpeg") { f ->
            f.provider.stallIndex = true
            f.engine.resume { }
            assertFalse(f.journal().getBoolean("done"))
            assertEquals("PUBLICATION_INTENT", f.journal().getJSONObject("operation").getString("stage"))
            assertEquals(f.old.toString(), f.db.downloadDao().byId("stable-id")!!.savedUri)
            assertTrue(f.provider.entries.containsKey(42))
            assertEquals(0, f.provider.deletes)
            f.provider.stallIndex = false
            f.assertComplete(f.engine.resume { })
        }
    }

    @Test fun automaticMaintenanceStaysPausedForInterruptedOrUnreadableJournal() = runBlocking {
        fixture("image/jpeg") { f ->
            assertTrue(HistoricalRecoverySignals.hasPendingJournal(f.context))
            f.journalFile.writeText("incomplete synthetic journal")
            assertTrue(HistoricalRecoverySignals.hasPendingJournal(f.context))
            f.journalFile.writeText(JSONObject().put("done", true).toString())
            assertFalse(HistoricalRecoverySignals.hasPendingJournal(f.context))
        }
    }

    @Test fun insufficientStorageStopsBeforeWritingNewCopy() = runBlocking {
        fixture("image/jpeg") { f ->
            f.journalFile.writeText(f.journal().apply {
                getJSONObject("operation").put("stage", "NEW_RESERVED")
            }.toString())
            ShadowStatFs.registerStats(f.journalFile.parentFile!!.path, 1, 0, 0)
            f.engine.resume { }
            assertFalse(f.journal().getBoolean("done"))
            assertEquals("NEW_RESERVED", f.journal().getJSONObject("operation").getString("stage"))
            assertTrue(f.journal().getString("message").contains("Недостаточно места"))
            assertEquals(f.old.toString(), f.db.downloadDao().byId("stable-id")!!.savedUri)
            assertTrue(f.provider.entries.getValue(43).pending)
            assertEquals(0, f.provider.deletes)
            assertTrue(f.backup.exists())
        }
    }

    private suspend fun fixture(mime: String, block: suspend (Fixture) -> Unit) {
        val context = RuntimeEnvironment.getApplication()
        val root = File(context.noBackupFilesDir, "historical-media-recovery-v1").apply { mkdirs() }
        ShadowStatFs.registerStats(root.path, 1_000_000, 1_000_000, 1_000_000)
        ShadowStatFs.registerStats(Environment.getExternalStorageDirectory().path, 1_000_000, 1_000_000, 1_000_000)
        val db = Room.inMemoryDatabaseBuilder(context, HistoryDatabase::class.java).build()
        val ext = if (mime == "image/jpeg") "jpg" else "mp4"
        val type = if (ext == "jpg") MediaType.PHOTO else MediaType.VIDEO
        val kind = if (ext == "jpg") "image" else "video"
        val collection = "content://media/external/${if (ext == "jpg") "images" else "video"}/media"
        val old = Uri.parse("$collection/42"); val new = Uri.parse("$collection/43")
        val directory = File(Environment.getExternalStorageDirectory(), "Pictures/MediaLoader").apply { mkdirs() }
        val name = "QA_${UUID.randomUUID()}.$ext"
        val oldFile = File(directory, name).apply { writeText("synthetic original media") }
        val pendingFile = File(directory, ".pending-1999999999-$name").apply { writeText("synthetic dated media") }
        val provider = Provider(context.packageName, mime)
        provider.entries[42] = Entry(oldFile, name, false)
        provider.entries[43] = Entry(pendingFile, name, true)
        provider.attachInfo(context, ProviderInfo().apply { authority = "media"; exported = true })
        ShadowContentResolver.registerProviderInternal("media", provider)
        val row = DownloadEntity("stable-id", "https://example.invalid/artificial", name, type,
            DownloadState.COMPLETED, old.toString(), oldFile.length(), 1700000000000L,
            previewUrl = old.toString(), groupId = "group", bulkMediaKey = "bulk", derivedFromId = "parent")
        db.downloadDao().upsert(row)
        db.catalogDao().insertFolder(CatalogFolderEntity("album", "Album", "album", 1))
        db.catalogDao().saveAssignment(CatalogAssignmentEntity(row.id, "album", true))
        val catalog = JSONObject().put("version", 1).put("entries", JSONArray().put(JSONObject()
            .put("key", "phone:$kind:42").put("aliases", JSONArray(listOf("phone:$kind:42", "uri:$old")))
            .put("uri", old.toString()).put("name", name).put("type", type.name).put("date", row.createdAtEpochMillis)
            .put("size", row.sizeBytes).put("duration", 10000L).put("album", "album")
            .put("artist", JSONObject.NULL).put("folder", "album").put("favorite", true)))
        context.getSharedPreferences("external_media_catalog", 0).edit().clear().putString("catalog", catalog.toString()).commit()
        context.getSharedPreferences("media_playlists", 0).edit().clear().putString("playlists", JSONArray().put(JSONObject()
            .put("id", "playlist").put("name", "QA").put("trackIds", JSONArray(listOf("phone:$kind:42")))).toString()).commit()
        val watch = context.getSharedPreferences("video_playback_positions", 0)
        watch.edit().clear().putLong("phone:$kind:42", 5000L).commit()
        val links = HistoricalReferenceLinks(context, db)
        val snapshot = links.snapshot(row)
        val id = UUID.randomUUID().toString()
        val quarantine = "ML_recovery_${id}_original.$ext"
        val backup = File(root, "$id-backup.$ext").apply { writeBytes(oldFile.readBytes()) }
        val dated = File(root, "$id-dated.$ext").apply { writeBytes(pendingFile.readBytes()) }
        provider.rename(42, quarantine)
        val engine = HistoricalMediaRecovery(context, db)
        val rowJson = HistoricalMediaRecovery::class.java.getDeclaredMethod("rowJson", DownloadEntity::class.java)
            .apply { isAccessible = true }.invoke(engine, row) as JSONObject
        val op = JSONObject().put("version", 1).put("op", id).put("downloadId", row.id)
            .put("name", name).put("mime", mime).put("path", "Pictures/MediaLoader/")
            .put("historical", row.createdAtEpochMillis).put("oldUri", old.toString()).put("oldBytes", backup.length())
            .put("oldHash", sha(backup)).put("quarantine", quarantine).put("extension", ext).put("row", rowJson)
            .put("links", snapshot).put("stage", "PUBLICATION_INTENT").put("newUri", new.toString())
            .put("newHash", sha(dated)).put("newBytes", dated.length()).put("contentVerified", true)
        val journal = File(root, "operation.json")
        journal.writeText(JSONObject().put("version", 1).put("batch", UUID.randomUUID().toString())
            .put("ids", JSONArray(listOf(row.id))).put("cursor", 0).put("trial", true).put("done", false)
            .put("operation", op).toString())
        val f = Fixture(context, db, engine, provider, old, new, backup, journal, row, kind)
        try { block(f) } finally {
            db.close()
            provider.entries.values.forEach { it.file.delete() }
            oldFile.delete(); pendingFile.delete(); backup.delete(); dated.delete()
            // Only Robolectric's artificial app-private journals are present here.
            root.deleteRecursively()
            File(context.noBackupFilesDir, "historical-reference-links-v1").deleteRecursively()
        }
    }

    private class Fixture(val context: Context, val db: HistoryDatabase, val engine: HistoricalMediaRecovery,
        val provider: Provider, val old: Uri, val new: Uri, val backup: File, val journalFile: File,
        val row: DownloadEntity, val kind: String) {
        fun journal() = JSONObject(journalFile.readText())
        suspend fun assertComplete(message: String) {
            assertTrue(message, journal().getBoolean("done"))
            val expected = row.copy(savedUri = new.toString(), sizeBytes = provider.entries.getValue(43).file.length(), previewUrl = new.toString())
            assertEquals(message, expected, db.downloadDao().byId(row.id))
            assertEquals(1, db.downloadDao().all().size)
            assertEquals(CatalogAssignmentEntity(row.id, "album", true), db.catalogDao().assignmentById(row.id))
            assertFalse(provider.entries.containsKey(42))
            val fresh = provider.entries.getValue(43)
            assertFalse(fresh.pending)
            assertEquals(row.originalName, fresh.file.name)
            assertEquals(row.createdAtEpochMillis, fresh.file.lastModified())
            assertTrue(backup.exists())
            val catalog = JSONObject(context.getSharedPreferences("external_media_catalog", 0).getString("catalog", null)!!)
                .getJSONArray("entries").getJSONObject(0)
            assertEquals(new.toString(), catalog.getString("uri"))
            assertTrue(catalog.getBoolean("favorite"))
            assertEquals("album", catalog.getString("folder"))
            assertEquals(5000L, VideoWatchProgress.resumePosition(context.getSharedPreferences("video_playback_positions", 0), "phone:$kind:43"))
        }
    }

    private data class Entry(var file: File, var name: String, var pending: Boolean)
    private class Provider(val owner: String, val mime: String) : ContentProvider() {
        val entries = mutableMapOf<Long, Entry>()
        var failAfter: String? = null
        var deletes = 0
        var stallIndex = false
        override fun onCreate() = true
        fun rename(id: Long, name: String) {
            val entry = entries.getValue(id)
            val destination = File(entry.file.parentFile, name)
            check(entry.file.renameTo(destination))
            entry.file = destination; entry.name = name
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor =
            MatrixCursor(projection!!).apply {
                val id = uri.lastPathSegment?.toLongOrNull()
                val matches = if (id != null) entries.filterKeys { it == id } else
                    entries.filterValues { args == null || it.name == args.last() }
                for ((key, row) in matches) addRow(projection.map { when (it) {
                    "_id" -> key
                    "_data" -> row.file.path
                    "owner_package_name" -> owner
                    "relative_path" -> "Pictures/MediaLoader/"
                    "_display_name" -> row.name
                    "mime_type" -> mime
                    "_size" -> if (row.pending) 0L else row.file.length()
                    "is_pending" -> if (row.pending) 1 else 0
                    "datetaken" -> if (row.pending || key == 43L && stallIndex) 0L else row.file.lastModified()
                    else -> 0L
                } })
            }
        override fun openFile(uri: Uri, mode: String) = ParcelFileDescriptor.open(
            entries.getValue(uri.lastPathSegment!!.toLong()).file, ParcelFileDescriptor.parseMode(mode))
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int {
            val id = uri.lastPathSegment!!.toLong()
            if (values!!.containsKey("is_pending")) {
                val row = entries.getValue(id)
                rename(id, row.name); row.pending = false
                // Publication may refresh filesystem time; the proven recipe repairs it AFTER publication.
                row.file.setLastModified(System.currentTimeMillis())
                if (failAfter == "publish") { failAfter = null; error("QA: published, acknowledgement lost") }
            }
            return 1
        }
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int {
            val removed = entries.remove(uri.lastPathSegment!!.toLong()) ?: return 0
            check(removed.file.delete()); deletes++
            if (failAfter == "delete") { failAfter = null; error("QA: deleted, acknowledgement lost") }
            return 1
        }
        override fun getType(uri: Uri) = mime
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Resume must not insert another copy")
    }

    companion object {
        private fun sha(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }
    }
}
