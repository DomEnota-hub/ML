package com.domenota.medialoader.data.library

import android.content.Context
import android.util.AtomicFile
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

internal data class PhoneStoreStamp(val versions: String?, val generation: String?)
internal data class CachedPhoneLibrary(val snapshot: PhoneMediaSnapshot, val stamp: PhoneStoreStamp)

/** Rebuildable metadata, never media bytes. Not included in Android backup/restore. */
internal class PhoneLibraryCache(context: Context) {
    private val directory = File(context.applicationContext.noBackupFilesDir, "phone-library-v1")
    private fun file(mode: AppMode) = AtomicFile(File(directory, "${mode.name.lowercase()}.json"))

    suspend fun read(mode: AppMode, access: String, privacy: String, stamp: PhoneStoreStamp): CachedPhoneLibrary? = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (stamp.versions == null) return@withLock null
            try {
                val root = file(mode).openRead().bufferedReader().use { JSONObject(it.readText()) }
                if (root.getInt("schema") != 1 || root.getString("access") != access ||
                    root.getString("privacy") != privacy || root.getString("versions") != stamp.versions) return@withLock null
                val entries = root.getJSONArray("entries")
                val identities = mutableMapOf<String, Set<String>>()
                val fingerprints = mutableMapOf<String, String>()
                val items = (0 until entries.length()).map { index ->
                    val row = entries.getJSONObject(index)
                    fun string(key: String): String? = if (row.isNull(key)) null else row.getString(key)
                    fun number(key: String): Long? = if (row.isNull(key)) null else row.getLong(key)
                    val uri = row.getString("uri")
                    val type = MediaType.valueOf(row.getString("type"))
                    require((mode == AppMode.MUSIC && type == MediaType.AUDIO) ||
                        (mode == AppMode.GALLERY && type in setOf(MediaType.PHOTO, MediaType.VIDEO)))
                    val aliases = row.getJSONArray("aliases")
                    identities[uri] = (0 until aliases.length()).mapTo(mutableSetOf()) { aliases.getString(it) }
                    string("fingerprint")?.let { fingerprints[uri] = it }
                    DownloadEntity(row.getString("id"), "device", row.getString("name"), type, DownloadState.COMPLETED,
                        uri, number("size"), row.getLong("date"), previewUrl = uri.takeIf { type != MediaType.AUDIO },
                        groupId = string("album"), bulkMediaKey = string("bucket"), durationMs = number("duration"),
                        sourceAudioTitle = string("title"), sourceAudioArtist = string("artist"))
                }
                CachedPhoneLibrary(PhoneMediaSnapshot(items, identities, fingerprints),
                    PhoneStoreStamp(stamp.versions, if (root.isNull("generation")) null else root.getString("generation")))
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { null }
        }
    }

    suspend fun write(mode: AppMode, access: String, privacy: String, stamp: PhoneStoreStamp, snapshot: PhoneMediaSnapshot) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val entries = JSONArray()
            snapshot.items.forEach { row ->
                if (row.isPrivate || row.hidden || row.savedUri == null) return@forEach
                entries.put(JSONObject().put("id", row.id).put("uri", row.savedUri).put("name", row.originalName)
                    .put("type", row.mediaType.name).put("size", row.sizeBytes).put("date", row.createdAtEpochMillis)
                    .put("album", row.groupId).put("bucket", row.bulkMediaKey).put("duration", row.durationMs)
                    .put("title", row.sourceAudioTitle).put("artist", row.sourceAudioArtist)
                    .put("aliases", JSONArray(snapshot.identities[row.savedUri].orEmpty().toList()))
                    .put("fingerprint", snapshot.fingerprints[row.savedUri]))
            }
            val root = JSONObject().put("schema", 1).put("access", access).put("privacy", privacy)
                .put("versions", stamp.versions).put("generation", stamp.generation).put("entries", entries)
            directory.mkdirs()
            val target = file(mode)
            val output = target.startWrite()
            try {
                output.write(root.toString().toByteArray(Charsets.UTF_8))
                target.finishWrite(output)
            } catch (e: Exception) { target.failWrite(output); throw e }
        }
    }

    suspend fun clear(mode: AppMode) = withContext(Dispatchers.IO) { mutex.withLock { file(mode).delete() } }

    companion object {
        private val mutex = Mutex()
        fun privacyTag(keys: Set<String>): String = MessageDigest.getInstance("SHA-256")
            .digest(keys.sorted().joinToString("\u0000").toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
