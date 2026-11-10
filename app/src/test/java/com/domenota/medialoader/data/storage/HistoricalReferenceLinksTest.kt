package com.domenota.medialoader.data.storage

import androidx.room.Room
import com.domenota.medialoader.core.database.*
import com.domenota.medialoader.core.model.*
import com.domenota.medialoader.data.playback.VideoWatchProgress
import com.domenota.medialoader.data.vkcommunity.VkCommunityDatabase
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HistoricalReferenceLinksTest {
    private val old = "content://media/external/video/media/42"
    private val new = "content://media/external/video/media/43"
    private fun catalog(): String = JSONObject().put("version", 1).put("entries", JSONArray().put(JSONObject()
        .put("key", "phone:video:42").put("aliases", JSONArray(listOf("phone:video:42", "uri:$old")))
        .put("uri", old).put("name", "video.mp4").put("type", "VIDEO").put("date", 123456L)
        .put("size", 100L).put("duration", 10000L).put("album", "group").put("artist", JSONObject.NULL)
        .put("title", JSONObject.NULL).put("favorite", true).put("folder", "album")
        .put("futureField", "keep"))).toString()
    private fun lists(): String = JSONArray().put(JSONObject().put("id", "playlist").put("name", "List")
        .put("trackIds", JSONArray(listOf("phone:video:42"))).put("trackReferences", JSONObject()
            .put("phone:video:42", JSONArray(listOf("uri:$old"))))).toString()

    @Test fun replayPreservesHistoryPhoneIdentityFavoritesAlbumsPlaylistAndWatchPosition() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, HistoryDatabase::class.java).build()
        try {
            val before = DownloadEntity("download-stable", "qa", "video.mp4", MediaType.VIDEO,
                DownloadState.COMPLETED, old, 100L, 123456L, groupId = "group", bulkMediaKey = "bulk", derivedFromId = "parent")
            db.downloadDao().upsert(before)
            db.catalogDao().insertFolder(CatalogFolderEntity("album", "Album", "album", 1L))
            db.catalogDao().saveAssignment(CatalogAssignmentEntity(before.id, "album", true))
            val catalogPrefs = context.getSharedPreferences("external_media_catalog", 0)
            catalogPrefs.edit().clear().putString("catalog", catalog()).commit()
            val playlistPrefs = context.getSharedPreferences("media_playlists", 0)
            playlistPrefs.edit().clear().putString("playlists", lists()).commit()
            val watch = context.getSharedPreferences("video_playback_positions", 0)
            watch.edit().clear().putLong("phone:video:42", 5000).putLong("updated_phone:video:42", 123).commit()
            val links = HistoricalReferenceLinks(context, db)
            val snapshot = links.snapshot(before)
            assertEquals(1, db.downloadDao().rebindSavedFileWhenCurrent(before.id, old, before.originalName, 100L, new, before.originalName, 120L))
            repeat(2) { links.rebind(snapshot, old, new, "oldhash", "newhash", "video.mp4", 120L) }
            links.verify(snapshot, new, "newhash")
            assertEquals(before.copy(savedUri = new, sizeBytes = 120L), db.downloadDao().byId(before.id))
            val entry = JSONObject(catalogPrefs.getString("catalog", null)!!).getJSONArray("entries").getJSONObject(0)
            assertEquals("phone:video:42", entry.getString("key"))
            assertEquals(new, entry.getString("uri")); assertTrue(entry.getBoolean("favorite"))
            assertEquals("album", entry.getString("folder")); assertEquals("keep", entry.getString("futureField"))
            val playlist = JSONArray(playlistPrefs.getString("playlists", null)!!).getJSONObject(0)
            assertEquals("playlist", playlist.getString("id"))
            assertEquals("phone:video:42", playlist.getJSONArray("trackIds").getString(0))
            assertEquals(5000L, VideoWatchProgress.resumePosition(watch, before.id))
            assertEquals(5000L, VideoWatchProgress.resumePosition(watch, "phone:video:43"))
        } finally { db.close() }
    }

    @Test fun concurrentCatalogChangeStopsRebindInsteadOfOverwritingUserEdit() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, HistoryDatabase::class.java).build()
        try {
            val row = DownloadEntity("stable", "qa", "video.mp4", MediaType.VIDEO, DownloadState.COMPLETED, old, 100L, 123456L)
            db.downloadDao().upsert(row)
            val prefs = context.getSharedPreferences("external_media_catalog", 0)
            prefs.edit().clear().putString("catalog", catalog()).commit()
            context.getSharedPreferences("media_playlists", 0).edit().clear().commit()
            val links = HistoricalReferenceLinks(context, db)
            val snapshot = links.snapshot(row)
            val changed = JSONObject(catalog()).apply { getJSONArray("entries").getJSONObject(0).put("favorite", false) }.toString()
            prefs.edit().putString("catalog", changed).commit()
            try { links.rebind(snapshot, old, new, "oldhash", "newhash", "video.mp4", 120L); fail("Expected CAS refusal") }
            catch (_: IllegalStateException) { }
            assertEquals(changed, prefs.getString("catalog", null))
            assertEquals(old, db.downloadDao().byId(row.id)?.savedUri)
        } finally { db.close() }
    }

    @Test fun vkSourceHashRemainsDedupAliasAndNewHashLinksWithoutChangingPublicationIdentity() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val main = Room.inMemoryDatabaseBuilder(context, HistoryDatabase::class.java).build()
        val vk = VkCommunityDatabase(context)
        try {
            val row = DownloadEntity("vk-stable", "qa", "video.mp4", MediaType.VIDEO,
                DownloadState.COMPLETED, old, 100L, 123456L)
            main.downloadDao().upsert(row)
            context.getSharedPreferences("external_media_catalog", 0).edit().clear().commit()
            context.getSharedPreferences("media_playlists", 0).edit().clear().commit()
            context.getSharedPreferences("video_playback_positions", 0).edit().clear().commit()
            vk.writableDatabase.execSQL("INSERT INTO jobs(id,link,start_date,end_date,zone_id,phase,created_at) VALUES('job','https://example.invalid','2026-01-01','2026-01-01','UTC','FINISHED',1)")
            vk.writableDatabase.execSQL("INSERT INTO images(job_id,photo_key,post_key,post_date,url,width,height,phase,sha256,saved_uri) VALUES('job','photo','post',1,'https://example.invalid',320,240,'DONE','oldhash',?)", arrayOf(old))
            vk.writableDatabase.execSQL("INSERT INTO hashes(sha256,saved_uri,photo_key) VALUES('oldhash',?,'photo')", arrayOf(old))
            val links = HistoricalReferenceLinks(context, main)
            val snapshot = links.snapshot(row)
            main.downloadDao().rebindSavedFileWhenCurrent(row.id, old, row.originalName, 100, new, row.originalName, 120)
            repeat(2) { links.rebind(snapshot, old, new, "oldhash", "newhash", row.originalName, 120) }
            links.verify(snapshot, new, "newhash")
            vk.readableDatabase.rawQuery("SELECT job_id,photo_key,post_key,phase,saved_uri,sha256 FROM images", null).use {
                assertTrue(it.moveToFirst()); assertEquals("job", it.getString(0)); assertEquals("photo", it.getString(1))
                assertEquals("post", it.getString(2)); assertEquals("DONE", it.getString(3))
                assertEquals(new, it.getString(4)); assertEquals("oldhash", it.getString(5)); assertFalse(it.moveToNext())
            }
            vk.readableDatabase.rawQuery("SELECT sha256,saved_uri FROM hashes ORDER BY sha256", null).use {
                assertEquals(2, it.count)
                assertTrue(it.moveToFirst()); assertEquals("newhash", it.getString(0)); assertEquals(new, it.getString(1))
                assertTrue(it.moveToNext()); assertEquals("oldhash", it.getString(0)); assertEquals(new, it.getString(1))
                assertFalse(it.moveToNext())
            }
        } finally { vk.close(); main.close() }
    }
}
