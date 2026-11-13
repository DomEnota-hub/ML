package com.domenota.medialoader.data.library

import android.content.Context
import android.util.AtomicFile
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal data class DuplicateSession(
    val result: DuplicateScanResult, val selected: Set<String>, val kept: Map<String,String>,
    val index: Int, val offset: Int, val scannedAt: Long,
)

/** One private, non-backed-up snapshot. Atomic replacement preserves the previous successful scan. */
internal object MusicDuplicateSessionStore {
    private fun file(context: Context) = AtomicFile(File(context.noBackupFilesDir, "music-duplicates-v1.json"))
    private val lock = Any()
    suspend fun load(context: Context): DuplicateSession? = withContext(Dispatchers.IO) {
        synchronized(lock) { runCatching { decode(file(context).openRead().bufferedReader().use { it.readText() }) }.getOrNull() }
    }
    suspend fun save(context: Context, session: DuplicateSession): Boolean = withContext(Dispatchers.IO + NonCancellable) {
        synchronized(lock) {
            val target = file(context)
            runCatching {
                val bytes = encode(session).toByteArray(Charsets.UTF_8)
                val stream = target.startWrite()
                try { stream.write(bytes); target.finishWrite(stream) }
                catch (e: Exception) { target.failWrite(stream); throw e }
            }.isSuccess
        }
    }
    fun encode(session: DuplicateSession): String {
        val groups = JSONArray()
        session.result.groups.forEach { g ->
            val tracks = JSONArray()
            g.tracks.forEach { t ->
                tracks.put(JSONObject().apply {
                    put("id", t.id); put("title", t.title); put("artist", t.artist ?: JSONObject.NULL)
                    put("filename", t.filename); put("uri", t.uri); put("bytes", t.bytes)
                    put("modified", t.modified ?: JSONObject.NULL); put("sha", t.sha)
                    put("payload", t.payloadSha ?: JSONObject.NULL); put("duration", t.duration ?: JSONObject.NULL)
                    put("bitrate", t.bitrate ?: JSONObject.NULL); put("folder", t.folder ?: JSONObject.NULL)
                    put("linked", t.linked); put("current", t.current)
                })
            }
            groups.put(JSONObject().put("title", g.title).put("reason", g.reason).put("tracks", tracks))
        }
        val keep = JSONObject(); session.kept.forEach { (k,v) -> keep.put(k,v) }
        return JSONObject().put("schema",1).put("groups",groups).put("skipped",session.result.skipped)
            .put("selected",JSONArray(session.selected.toList())).put("kept",keep)
            .put("index",session.index).put("offset",session.offset).put("scannedAt",session.scannedAt).toString()
    }
    fun decode(text: String): DuplicateSession {
        val json = JSONObject(text); require(json.getInt("schema") == 1)
        fun JSONObject.str(key: String): String? = if (isNull(key)) null else getString(key)
        fun JSONObject.lng(key: String): Long? = if (isNull(key)) null else getLong(key)
        val ga = json.getJSONArray("groups")
        val groups = (0 until ga.length()).map { i ->
            val g = ga.getJSONObject(i); val ta = g.getJSONArray("tracks")
            val tracks = (0 until ta.length()).map { j ->
                val t = ta.getJSONObject(j)
                DuplicateTrack(t.getString("id"),t.getString("title"),t.str("artist"),t.getString("filename"),
                    t.getString("uri"),t.getLong("bytes"),t.lng("modified"),t.getString("sha"),t.str("payload"),
                    t.lng("duration"),t.lng("bitrate"),t.str("folder"),t.getBoolean("linked"),t.getBoolean("current"))
            }
            require(tracks.size >= 2)
            DuplicateGroup(tracks,g.getString("title"),g.getString("reason"))
        }
        val ka = json.getJSONObject("kept"); val keep = ka.keys().asSequence().associateWith { ka.getString(it) }
        val sa = json.getJSONArray("selected"); val selected = (0 until sa.length()).map { sa.getString(it) }.toSet()
        return DuplicateSession(DuplicateScanResult(groups,json.getInt("skipped")),selected,keep,
            json.getInt("index").coerceAtLeast(0),json.getInt("offset").coerceAtLeast(0),json.getLong("scannedAt"))
    }
}
