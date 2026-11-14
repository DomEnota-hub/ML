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
    private val lock = DuplicatePrivacyGuard.lock
    fun clearForPrivacy(context: Context) { val target = file(context); target.delete(); check(!target.baseFile.exists()) { "Не удалось очистить отчёт дубликатов" } }
    private fun permitted(context: Context, session: DuplicateSession): Boolean = session.result.groups.all { group ->
        group.tracks.all { DuplicatePrivacyGuard.accepts(context,it.privacyEpoch,it.id) }
    }
    suspend fun load(context: Context): DuplicateSession? = withContext(Dispatchers.IO) {
        synchronized(lock) { runCatching { decode(file(context).openRead().bufferedReader().use { it.readText() }).takeIf { permitted(context,it) } }.getOrNull() }
    }
    suspend fun save(context: Context, session: DuplicateSession): Boolean = withContext(Dispatchers.IO + NonCancellable) {
        synchronized(lock) {
            if (!permitted(context,session)) return@synchronized false
            val target = file(context)
            runCatching {
                val bytes = encode(session).toByteArray(Charsets.UTF_8)
                val stream = target.startWrite()
                try { stream.write(bytes); target.finishWrite(stream) }
                catch (e: Exception) { target.failWrite(stream); throw e }
            }.isSuccess
        }
    }
    /** Only remove files the trash host actually confirmed. Keep cancelled/failed requests. */
    suspend fun removeTrashed(context: Context, uris: Set<String>) = withContext(Dispatchers.IO + NonCancellable) {
        synchronized(lock) {
            val target = file(context)
            val old = runCatching { decode(target.openRead().bufferedReader().use { it.readText() }) }.getOrNull() ?: return@synchronized
            val groups = old.result.groups.mapNotNull { g ->
                val remaining = g.tracks.filter { it.uri !in uris }
                if (remaining.size >= 2) g.copy(tracks = remaining) else null
            }
            val keep = groups.associate { g -> g.key to (old.kept.entries.firstOrNull { (_, id) -> g.tracks.any { it.id == id } }?.value ?: MusicDuplicatePolicy.recommended(g)) }
            val selected = old.selected.intersect(groups.flatMap { g -> g.tracks.filter { it.id != keep[g.key] }.map { it.id } }.toSet())
            val next = old.copy(result = old.result.copy(groups = groups), selected = selected, kept = keep,
                index = old.index.coerceAtMost((groups.size - 1).coerceAtLeast(0)))
            val stream = target.startWrite()
            try { stream.write(encode(next).toByteArray(Charsets.UTF_8)); target.finishWrite(stream) }
            catch (e: Exception) { target.failWrite(stream); throw e }
        }
    }
    internal fun encodeTrack(t: DuplicateTrack): JSONObject = JSONObject().apply {
                    put("id", t.id); put("title", t.title); put("artist", t.artist ?: JSONObject.NULL)
                    put("filename", t.filename); put("uri", t.uri); put("bytes", t.bytes)
                    put("modified", t.modified ?: JSONObject.NULL); put("sha", t.sha)
                    put("payload", t.payloadSha ?: JSONObject.NULL); put("duration", t.duration ?: JSONObject.NULL)
                    put("bitrate", t.bitrate ?: JSONObject.NULL); put("folder", t.folder ?: JSONObject.NULL)
                    put("linked", t.linked); put("current", t.current); put("privacyEpoch",t.privacyEpoch)
                    t.acoustic?.let { fp ->
                        put("acousticVersion", AcousticFingerprint.VERSION)
                        put("landmarks", JSONArray().apply { fp.landmarks.forEach { put(JSONArray().put(it.hash).put(it.frame)) } })
                    }
    }
    internal fun decodeTrack(t: JSONObject): DuplicateTrack {
        fun JSONObject.str(key: String): String? = if (isNull(key)) null else getString(key)
        fun JSONObject.lng(key: String): Long? = if (isNull(key)) null else getLong(key)
        return DuplicateTrack(t.getString("id"),t.getString("title"),t.str("artist"),t.getString("filename"),
                    t.getString("uri"),t.getLong("bytes"),t.lng("modified"),t.getString("sha"),t.str("payload"),
                    t.lng("duration"),t.lng("bitrate"),t.str("folder"),t.getBoolean("linked"),t.getBoolean("current"),
                    if (t.optInt("acousticVersion") == AcousticFingerprint.VERSION) t.optJSONArray("landmarks")?.let { array ->
                        require(array.length() <= 6000)
                        AcousticFingerprint((0 until array.length()).map { i -> array.getJSONArray(i).let { AcousticLandmark(it.getInt(0),it.getInt(1)) } })
                    } else null, t.optLong("privacyEpoch",0))
    }
    fun encode(session: DuplicateSession): String {
        val groups = JSONArray()
        session.result.groups.forEach { g ->
            val tracks = JSONArray()
            g.tracks.forEach { t ->
                tracks.put(encodeTrack(t))
            }
            groups.put(JSONObject().put("title", g.title).put("reason", g.reason).put("kind",g.kind.name).put("tracks", tracks))
        }
        val keep = JSONObject(); session.kept.forEach { (k,v) -> keep.put(k,v) }
        return JSONObject().put("schema",1).put("groups",groups).put("skipped",session.result.skipped)
            .put("selected",JSONArray(session.selected.toList())).put("kept",keep)
            .put("index",session.index).put("offset",session.offset).put("scannedAt",session.scannedAt).toString()
    }
    fun decode(text: String): DuplicateSession {
        val json = JSONObject(text); require(json.getInt("schema") == 1)
        val ga = json.getJSONArray("groups")
        val groups = (0 until ga.length()).map { i ->
            val g = ga.getJSONObject(i); val ta = g.getJSONArray("tracks")
            val tracks = (0 until ta.length()).map { j ->
                decodeTrack(ta.getJSONObject(j))
            }
            require(tracks.size >= 2)
            DuplicateGroup(tracks,g.getString("title"),g.getString("reason"),
                runCatching { DuplicateKind.valueOf(g.getString("kind")) }.getOrDefault(DuplicateKind.POSSIBLE))
        }
        val ka = json.getJSONObject("kept"); val keep = ka.keys().asSequence().associateWith { ka.getString(it) }
        val sa = json.getJSONArray("selected"); val selected = (0 until sa.length()).map { sa.getString(it) }.toSet()
        return DuplicateSession(DuplicateScanResult(groups,json.getInt("skipped")),selected,keep,
            json.getInt("index").coerceAtLeast(0),json.getInt("offset").coerceAtLeast(0),json.getLong("scannedAt"))
    }
}
