package com.domenota.medialoader.data.playback

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** One local database for future listening analytics. No media files, names or artwork
 * are copied. Idempotent session updates never add another credited play. */
internal class ListeningHistoryStore private constructor(context: Context) {
    private val helper = object : SQLiteOpenHelper(context.applicationContext,"listening_history.db",null,1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE tracks (id TEXT PRIMARY KEY)")
            db.execSQL("CREATE TABLE aliases (alias TEXT PRIMARY KEY, track_id TEXT NOT NULL)")
            db.execSQL("CREATE INDEX alias_track ON aliases(track_id)")
            db.execSQL("CREATE TABLE sessions (id TEXT PRIMARY KEY, track_id TEXT NOT NULL, started_at INTEGER NOT NULL, last_at INTEGER NOT NULL, media_ms INTEGER NOT NULL, wall_ms INTEGER NOT NULL, credited_at INTEGER, position INTEGER NOT NULL, closed INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX session_credit ON sessions(credited_at,track_id)")
            db.execSQL("CREATE INDEX session_track ON sessions(track_id,closed,last_at)")
            db.execSQL("CREATE TABLE excluded (track_id TEXT PRIMARY KEY)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Неизвестная версия статистики")
    }
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val epoch = AtomicLong(0)
    val generation: Long get() = epoch.get()
    private val changes = MutableStateFlow(0L)
    val revision = changes.asStateFlow()
    private val smartChanges = MutableStateFlow(0L)
    val smartRevision = smartChanges.asStateFlow()
    private fun changed() { changes.value++; smartChanges.value++ }

    private fun aliasMap(db: SQLiteDatabase): Map<String,String> = db.rawQuery("SELECT alias,track_id FROM aliases",null).use { c ->
        buildMap { while (c.moveToNext()) put(c.getString(0),c.getString(1)) }
    }
    private fun resolve(keys: Set<String>, aliases: Map<String,String>): String? =
        keys.mapNotNull(aliases::get).distinct().singleOrNull()

    private fun Cursor.progress(generation: Long): ListeningProgress = ListeningProgress(
        getString(0),getString(1),getLong(2),getLong(3),getLong(4),getLong(5),
        if (isNull(6)) null else getLong(6),getLong(7),getInt(8)!=0,generation)

    suspend fun begin(keys: Set<String>, position: Long, now: Long): ListeningProgress? = withContext(Dispatchers.IO) {
        if (keys.isEmpty()) return@withContext null
        mutex.withLock {
            val db = helper.writableDatabase
            db.beginTransaction()
            try {
                val aliases = buildMap<String,String> {
                    keys.chunked(100).forEach { batch ->
                        val placeholders = batch.joinToString(",") { "?" }
                        db.rawQuery("SELECT alias,track_id FROM aliases WHERE alias IN ($placeholders)",batch.toTypedArray()).use { c ->
                            while (c.moveToNext()) put(c.getString(0),c.getString(1))
                        }
                    }
                }
                val matched = keys.mapNotNull(aliases::get).distinct()
                if (matched.size > 1) return@withLock null
                val track = matched.singleOrNull() ?: UUID.randomUUID().toString().also { id ->
                    db.insertOrThrow("tracks",null,ContentValues().apply { put("id",id) })
                }
                keys.forEach { key -> if (key !in aliases) db.insertOrThrow("aliases",null,ContentValues().apply {
                    put("alias",key); put("track_id",track)
                }) }
                val saved = db.rawQuery("SELECT id,track_id,started_at,last_at,media_ms,wall_ms,credited_at,position,closed FROM sessions WHERE track_id=? AND closed=0 ORDER BY last_at DESC LIMIT 1",arrayOf(track)).use { c ->
                    if (c.moveToFirst()) c.progress(generation) else null
                }
                val resumed = saved?.takeIf { ListeningHistoryPolicy.mayResume(it,position,now) }
                val result = resumed ?: ListeningProgress(UUID.randomUUID().toString(),track,now,now,position=position,generation=generation)
                db.execSQL("UPDATE sessions SET closed=1 WHERE closed=0 AND id<>?",arrayOf(result.sessionId))
                if (resumed == null) db.insertOrThrow("sessions",null,ContentValues().apply {
                    put("id",result.sessionId); put("track_id",track); put("started_at",now); put("last_at",now)
                    put("media_ms",0L); put("wall_ms",0L); putNull("credited_at"); put("position",position); put("closed",0)
                })
                db.setTransactionSuccessful()
                result
            } finally { db.endTransaction() }
        }
    }

    fun submit(progress: ListeningProgress) {
        scope.launch {
            try { save(progress) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { com.domenota.medialoader.core.logging.AppLog.w("Listening","Unable to save listening statistics") }
        }
    }

    internal suspend fun save(progress: ListeningProgress) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (progress.generation != generation) return@withLock
            val db = helper.writableDatabase
            val freshCredit = progress.creditedAt != null && db.rawQuery("SELECT credited_at FROM sessions WHERE id=?",arrayOf(progress.sessionId)).use { c ->
                c.moveToFirst() && c.isNull(0)
            }
            db.execSQL("UPDATE sessions SET media_ms=MAX(media_ms,?),wall_ms=MAX(wall_ms,?),credited_at=COALESCE(credited_at,?),position=CASE WHEN last_at<=? THEN ? ELSE position END,last_at=MAX(last_at,?),closed=MAX(closed,?) WHERE id=? AND track_id=?",
                arrayOf(progress.mediaMs,progress.wallMs,progress.creditedAt,progress.lastAt,progress.position,progress.lastAt,
                    if (progress.closed) 1 else 0,progress.sessionId,progress.trackId))
            if (freshCredit) changed() else smartChanges.value++
        }
    }

    suspend fun selection(keysByRow: Map<String,Set<String>>, period: ListeningPeriod, now: Long): ListeningSelection = withContext(Dispatchers.IO) {
        mutex.withLock {
            val db = helper.readableDatabase
            val aliases = aliasMap(db)
            val mapped = keysByRow.mapNotNull { (row,keys) -> resolve(keys,aliases)?.let { row to it } }.toMap()
            val rowsByTrack = mapped.entries.groupBy({ it.value },{ it.key })
            val excluded = db.rawQuery("SELECT track_id FROM excluded ORDER BY track_id",null).use { c ->
                buildList { while (c.moveToNext()) add(c.getString(0)) }
            }
            val excludedSet = excluded.toSet()
            val candidates = db.rawQuery("SELECT track_id,COUNT(*),MAX(credited_at) FROM sessions WHERE credited_at>=? AND credited_at<=? GROUP BY track_id HAVING COUNT(*)>=2",
                arrayOf(period.cutoff(now).toString(),now.toString())).use { c ->
                buildList {
                    while(c.moveToNext()) {
                        val track = c.getString(0)
                        val row = rowsByTrack[track]?.singleOrNull()
                        if (row != null && track !in excludedSet) add(ListeningRank(row,track,c.getLong(1),c.getLong(2)))
                    }
                }
            }
            ListeningSelection(ListeningHistoryPolicy.rank(candidates),
                excluded.map { ListeningExcluded(it,rowsByTrack[it]?.singleOrNull()) })
        }
    }

    /** Read the original schema without migration or a parallel statistics counter.
     * Duration is real wall time, attributed to the session's last recorded date.
     * Old sessions don't contain daily slices, so cross-midnight time is not split. */
    suspend fun smartSnapshot(candidates: List<SmartTrackCandidate>, period: ListeningPeriod, now: Long,
        seed: Long = now / SmartMusicPolicy.DAY_MS): SmartMusicSnapshot = withContext(Dispatchers.IO) {
        mutex.withLock {
            val db = helper.readableDatabase
            val resolved = SmartMusicPolicy.resolve(candidates,aliasMap(db))
            val excluded = db.rawQuery("SELECT track_id FROM excluded",null).use { c ->
                buildSet { while(c.moveToNext()) add(c.getString(0)) }
            }
            data class Totals(val plays: Long,val last: Long,val periodPlays: Long,val wall: Long)
            val cutoff = period.cutoff(now).toString()
            val end = now.toString()
            val history = db.rawQuery(
                "SELECT track_id, SUM(CASE WHEN credited_at<=? THEN 1 ELSE 0 END), " +
                "MAX(CASE WHEN media_ms>0 AND last_at<=? THEN last_at ELSE 0 END), " +
                "SUM(CASE WHEN credited_at>=? AND credited_at<=? THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN last_at>=? AND last_at<=? THEN wall_ms ELSE 0 END) FROM sessions GROUP BY track_id",
                arrayOf(end,end,cutoff,end,cutoff,end)).use { c ->
                buildMap { while(c.moveToNext()) put(c.getString(0),Totals(c.getLong(1),c.getLong(2),c.getLong(3),c.getLong(4))) }
            }
            val rows = resolved.map { (row,track) ->
                val data = history[track]
                SmartTrackHistory(row.rowId,track,row.artist,row.favorite,data?.plays ?: 0,data?.last ?: 0,
                    data?.periodPlays ?: 0,data?.wall ?: 0,track in excluded)
            }
            SmartMusicPolicy.snapshot(rows,now,seed)
        }
    }

    suspend fun setExcluded(trackId: String, excluded: Boolean) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val db = helper.writableDatabase
            if (excluded) {
                check(db.rawQuery("SELECT id FROM tracks WHERE id=?",arrayOf(trackId)).use { it.moveToFirst() }) { "Трек недоступен" }
                db.insertWithOnConflict("excluded",null,ContentValues().apply { put("track_id",trackId) },SQLiteDatabase.CONFLICT_IGNORE)
            } else db.delete("excluded","track_id=?",arrayOf(trackId))
            changed()
        }
    }

    suspend fun restoreAll() = withContext(Dispatchers.IO) { mutex.withLock {
        helper.writableDatabase.delete("excluded",null,null); changed()
    } }
    suspend fun clearHistory() = withContext(Dispatchers.IO) { mutex.withLock {
        helper.writableDatabase.delete("sessions",null,null); epoch.incrementAndGet(); changed()
    } }

    internal fun closeForTest() { scope.cancel(); helper.close() }

    companion object {
        @Volatile private var instance: ListeningHistoryStore? = null
        fun get(context: Context): ListeningHistoryStore = instance ?: synchronized(this) {
            instance ?: ListeningHistoryStore(context).also { instance=it }
        }
        internal fun forTest(context: Context) = ListeningHistoryStore(context)
    }
}
