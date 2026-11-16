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
    private val helper = object : SQLiteOpenHelper(context.applicationContext,"listening_history.db",null,3) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE tracks (id TEXT PRIMARY KEY)")
            db.execSQL("CREATE TABLE aliases (alias TEXT PRIMARY KEY, track_id TEXT NOT NULL)")
            db.execSQL("CREATE INDEX alias_track ON aliases(track_id)")
            db.execSQL("CREATE TABLE sessions (id TEXT PRIMARY KEY, track_id TEXT NOT NULL, started_at INTEGER NOT NULL, last_at INTEGER NOT NULL, media_ms INTEGER NOT NULL, wall_ms INTEGER NOT NULL, credited_at INTEGER, position INTEGER NOT NULL, closed INTEGER NOT NULL, legacy_wall_ms INTEGER NOT NULL DEFAULT 0, legacy_at INTEGER NOT NULL DEFAULT 0, timed_wall_ms INTEGER NOT NULL DEFAULT 0, listened_at INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE INDEX session_credit ON sessions(credited_at,track_id)")
            db.execSQL("CREATE INDEX session_track ON sessions(track_id,closed,last_at)")
            db.execSQL("CREATE TABLE excluded (track_id TEXT PRIMARY KEY)")
            createWallMinutes(db)
            createProjectionCache(db)
        }
        private fun createProjectionCache(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE projection_revision (id INTEGER PRIMARY KEY, revision INTEGER NOT NULL)")
            db.execSQL("INSERT INTO projection_revision VALUES(1,0),(2,0),(3,0)")
            db.execSQL("CREATE TABLE projection_cache (key TEXT PRIMARY KEY, revision INTEGER NOT NULL, computed_at INTEGER NOT NULL, payload TEXT NOT NULL)")
            // Exact per-track UTC minute totals are derived transactionally from the
            // authoritative session buckets. Never round/truncate wall time.
            db.execSQL("CREATE TABLE track_wall_minutes (track_id TEXT NOT NULL, minute_at INTEGER NOT NULL, wall_ms INTEGER NOT NULL, PRIMARY KEY(track_id,minute_at))")
            db.execSQL("CREATE INDEX track_wall_time ON track_wall_minutes(minute_at,track_id)")
            db.execSQL("INSERT INTO track_wall_minutes SELECT s.track_id,m.minute_at,SUM(m.wall_ms) FROM session_wall_minutes m JOIN sessions s ON s.id=m.session_id GROUP BY s.track_id,m.minute_at")
            db.execSQL("CREATE TRIGGER track_wall_insert AFTER INSERT ON session_wall_minutes BEGIN INSERT OR IGNORE INTO track_wall_minutes SELECT track_id,NEW.minute_at,0 FROM sessions WHERE id=NEW.session_id; UPDATE track_wall_minutes SET wall_ms=wall_ms+NEW.wall_ms WHERE track_id=(SELECT track_id FROM sessions WHERE id=NEW.session_id) AND minute_at=NEW.minute_at; END")
            db.execSQL("CREATE TRIGGER track_wall_update AFTER UPDATE OF wall_ms ON session_wall_minutes WHEN NEW.wall_ms<>OLD.wall_ms BEGIN UPDATE track_wall_minutes SET wall_ms=wall_ms+NEW.wall_ms-OLD.wall_ms WHERE track_id=(SELECT track_id FROM sessions WHERE id=NEW.session_id) AND minute_at=NEW.minute_at; END")
            db.execSQL("CREATE TRIGGER track_wall_delete AFTER DELETE ON session_wall_minutes BEGIN UPDATE track_wall_minutes SET wall_ms=wall_ms-OLD.wall_ms WHERE track_id=(SELECT track_id FROM sessions WHERE id=OLD.session_id) AND minute_at=OLD.minute_at; DELETE FROM track_wall_minutes WHERE track_id=(SELECT track_id FROM sessions WHERE id=OLD.session_id) AND minute_at=OLD.minute_at AND wall_ms=0; END")
        }
        private fun createWallMinutes(db:SQLiteDatabase){
            db.execSQL("CREATE TABLE session_wall_minutes (session_id TEXT NOT NULL, minute_at INTEGER NOT NULL, wall_ms INTEGER NOT NULL, PRIMARY KEY(session_id,minute_at))")
            db.execSQL("CREATE INDEX wall_minute_time ON session_wall_minutes(minute_at,session_id)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            check(oldVersion in 1..2 && newVersion==3){ "Неизвестная версия статистики" }
            if (oldVersion == 1) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN legacy_wall_ms INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sessions ADD COLUMN legacy_at INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sessions ADD COLUMN timed_wall_ms INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE sessions SET legacy_wall_ms=wall_ms,legacy_at=last_at")
                createWallMinutes(db)
            }
            db.execSQL("ALTER TABLE sessions ADD COLUMN listened_at INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE sessions SET listened_at=last_at WHERE media_ms>0 OR wall_ms>0")
            createProjectionCache(db)
        }
    }
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val epoch = AtomicLong(0)
    val generation: Long get() = epoch.get()
    private val changes = MutableStateFlow(0L)
    val revision = changes.asStateFlow()
    private val smartChanges = MutableStateFlow(0L)
    val smartRevision = smartChanges.asStateFlow()
    private val visibilityChanges = MutableStateFlow(0L)
    /** In-memory display snapshots must be discarded on exclusion, alias change or clear. */
    val visibilityRevision = visibilityChanges.asStateFlow()
    private fun changed() { changes.value++; smartChanges.value++ }
    private fun invalidateVisibility(db: SQLiteDatabase) {
        db.delete("projection_cache",null,null)
        bumpProjection(db,*ListeningProjectionDomain.entries.toTypedArray())
    }
    private fun bumpProjection(db: SQLiteDatabase, vararg domains: ListeningProjectionDomain) {
        domains.forEach { db.execSQL("UPDATE projection_revision SET revision=revision+1 WHERE id=?", arrayOf(it.id)) }
    }
    private fun projectionRevision(db: SQLiteDatabase, domain: ListeningProjectionDomain): Long =
        db.rawQuery("SELECT revision FROM projection_revision WHERE id=?",arrayOf(domain.id.toString())).use { c ->
            check(c.moveToFirst()); c.getLong(0)
        }
    suspend fun persistedProjectionRevision(domain: ListeningProjectionDomain): Long = withContext(Dispatchers.IO) {
        mutex.withLock { projectionRevision(helper.readableDatabase,domain) }
    }
    suspend fun <T> readProjection(key: String, domain: ListeningProjectionDomain, decode: (org.json.JSONObject) -> T): CachedListeningProjection<T>? = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val db = helper.readableDatabase
                val revision = projectionRevision(db,domain)
                db.rawQuery("SELECT revision,computed_at,payload FROM projection_cache WHERE key=?", arrayOf(key)).use { c ->
                    if (!c.moveToFirst()) null else CachedListeningProjection(decode(org.json.JSONObject(c.getString(2))),c.getLong(0),revision,c.getLong(1)).also {
                        if(it.revision==revision)com.domenota.medialoader.core.logging.LibraryWorkCounters.increment(com.domenota.medialoader.core.logging.LibraryWork.LISTENING_CACHE_HIT)
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { null }
        }
    }
    suspend fun writeProjection(key: String, domain: ListeningProjectionDomain, expectedRevision: Long, now: Long, payload: org.json.JSONObject) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val db = helper.writableDatabase
            if (projectionRevision(db,domain) != expectedRevision) return@withLock
            val value = payload.toString()
            if (value.toByteArray(Charsets.UTF_8).size > 1024 * 1024) return@withLock
            db.beginTransaction()
            try {
                db.insertWithOnConflict("projection_cache",null,ContentValues().apply {
                    put("key",key);put("revision",expectedRevision);put("computed_at",now);put("payload",value)
                },SQLiteDatabase.CONFLICT_REPLACE)
                db.execSQL("DELETE FROM projection_cache WHERE key NOT IN (SELECT key FROM projection_cache ORDER BY computed_at DESC LIMIT 32)")
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
    }
    /** Only derived snapshots are revoked. Raw listening history is preserved. */
    suspend fun clearDerivedProjections() = withContext(Dispatchers.IO) { mutex.withLock {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.delete("projection_cache",null,null)
            bumpProjection(db,*ListeningProjectionDomain.entries.toTypedArray())
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        visibilityChanges.value++; changed()
    } }

    private fun aliasMap(db: SQLiteDatabase): Map<String,String> = db.rawQuery("SELECT alias,track_id FROM aliases",null).use { c ->
        buildMap { while (c.moveToNext()) put(c.getString(0),c.getString(1)) }
    }
    private fun resolve(keys: Set<String>, aliases: Map<String,String>): String? =
        keys.mapNotNull(aliases::get).distinct().singleOrNull()

    /** Called only after a byte-verified ordinary-folder move. Existing sessions,
     * credits and exclusion state keep their track ID; no new track is fabricated. */
    suspend fun rebindVerifiedVisibility(old: Set<String>, target: Set<String>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val db = helper.writableDatabase
            db.beginTransaction()
            var changedAliases = false
            try {
                val aliases = aliasMap(db)
                val track = com.domenota.medialoader.data.catalog.visibility.VisibilityReferencePolicy.listeningTrack(aliases,old,target)
                if (track != null) {
                    for (key in target - aliases.keys) {
                        db.insertOrThrow("aliases",null,ContentValues().apply { put("alias",key);put("track_id",track) })
                        changedAliases = true
                    }
                    if (changedAliases) invalidateVisibility(db)
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            if (changedAliases) { visibilityChanges.value++; changed() }
        }
    }
    suspend fun verifyVisibilityAliases(old: Set<String>, target: Set<String>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val aliases = aliasMap(helper.readableDatabase)
            val track = com.domenota.medialoader.data.catalog.visibility.VisibilityReferencePolicy.listeningTrack(aliases,old,target)
            if (track != null) check(target.all { aliases[it] == track }) { "История прослушиваний не подтвердила новый адрес" }
        }
    }

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
                val result = resumed?.let { previous ->
                    val minutes=db.rawQuery("SELECT minute_at,wall_ms FROM session_wall_minutes WHERE session_id=?",arrayOf(previous.sessionId)).use{c->
                        buildMap<Long,Long>{while(c.moveToNext())put(c.getLong(0),c.getLong(1))}
                    }
                    previous.copy(wallMinutes=minutes)
                } ?: ListeningProgress(UUID.randomUUID().toString(),track,now,now,position=position,generation=generation)
                db.execSQL("UPDATE sessions SET closed=1 WHERE closed=0 AND id<>?",arrayOf(result.sessionId))
                if (resumed == null) db.insertOrThrow("sessions",null,ContentValues().apply {
                    put("id",result.sessionId); put("track_id",track); put("started_at",now); put("last_at",now)
                    put("media_ms",0L); put("wall_ms",0L); putNull("credited_at"); put("position",position); put("closed",0)
                })
                // A newly opened, unplayed session has no effect on analytics.
                val aliasChanged = keys.any { it !in aliases }
                if (aliasChanged) invalidateVisibility(db)
                db.setTransactionSuccessful()
                result.also { if (aliasChanged) { visibilityChanges.value++; changed() } }
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
            val prior = db.rawQuery("SELECT media_ms,wall_ms,credited_at FROM sessions WHERE id=? AND track_id=?",
                arrayOf(progress.sessionId,progress.trackId)).use { c ->
                if (!c.moveToFirst()) null else Triple(c.getLong(0),c.getLong(1),if(c.isNull(2)) null else c.getLong(2))
            } ?: return@withLock
            val freshCredit = progress.creditedAt != null && prior.third == null
            val heard = progress.mediaMs > prior.first || progress.wallMs > prior.second
            var changedMinutes = false
            db.beginTransaction()
            try {
                db.execSQL("UPDATE sessions SET media_ms=MAX(media_ms,?),wall_ms=MAX(wall_ms,?),credited_at=COALESCE(credited_at,?),position=CASE WHEN last_at<=? THEN ? ELSE position END,last_at=MAX(last_at,?),closed=MAX(closed,?),listened_at=CASE WHEN ? THEN MAX(listened_at,?) ELSE listened_at END WHERE id=? AND track_id=?",
                    arrayOf(progress.mediaMs,progress.wallMs,progress.creditedAt,progress.lastAt,progress.position,progress.lastAt,
                        if (progress.closed) 1 else 0,if(heard) 1 else 0,progress.lastAt,progress.sessionId,progress.trackId))
                // Cumulative samples can be replayed/out of order. Only a positive
                // delta updates the minute aggregate; never double-count a session.
                if(progress.wallMinutes.isNotEmpty()) {
                    val oldMinutes=db.rawQuery("SELECT minute_at,wall_ms FROM session_wall_minutes WHERE session_id=?",arrayOf(progress.sessionId)).use { c ->
                        buildMap<Long,Long> { while(c.moveToNext()) put(c.getLong(0),c.getLong(1)) }
                    }
                    for((minute,wall) in progress.wallMinutes) {
                        val old=oldMinutes[minute]
                        if(wall<=0 || (old!=null && wall<=old)) continue
                        if(old==null) db.execSQL("INSERT INTO session_wall_minutes(session_id,minute_at,wall_ms) VALUES(?,?,?)",arrayOf(progress.sessionId,minute,wall))
                        else db.execSQL("UPDATE session_wall_minutes SET wall_ms=? WHERE session_id=? AND minute_at=?",arrayOf(wall,progress.sessionId,minute))
                        changedMinutes=true
                    }
                    if(changedMinutes) db.execSQL("UPDATE sessions SET timed_wall_ms=(SELECT COALESCE(SUM(wall_ms),0) FROM session_wall_minutes WHERE session_id=?) WHERE id=?",arrayOf(progress.sessionId,progress.sessionId))
                }
                if (heard || changedMinutes || freshCredit) bumpProjection(db,ListeningProjectionDomain.STATS)
                if (freshCredit) bumpProjection(db,ListeningProjectionDomain.RANK)
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            if (freshCredit) changed() else if(heard || changedMinutes) smartChanges.value++
        }
    }

    suspend fun selection(keysByRow: Map<String,Set<String>>, period: ListeningPeriod, now: Long): ListeningSelection = withContext(Dispatchers.IO) {
        com.domenota.medialoader.core.logging.LibraryWorkCounters.increment(com.domenota.medialoader.core.logging.LibraryWork.LISTENING_RANK_QUERY)
        mutex.withLock {
            val db = helper.readableDatabase
            val aliases = aliasMap(db)
            val mapped = keysByRow.mapNotNull { (row,keys) -> resolve(keys,aliases)?.let { row to it } }.toMap()
            val rowsByTrack = mapped.entries.groupBy({ it.value },{ it.key })
            val excluded = db.rawQuery("SELECT track_id FROM excluded ORDER BY track_id",null).use { c ->
                buildList { while (c.moveToNext()) add(c.getString(0)) }
            }
            val excludedSet = excluded.toSet()
            var validUntil=Long.MAX_VALUE
            val candidates = db.rawQuery("SELECT track_id,COUNT(*),MAX(credited_at),MIN(credited_at) FROM sessions WHERE credited_at>=? AND credited_at<=? GROUP BY track_id HAVING COUNT(*)>=2",
                arrayOf(period.cutoff(now).toString(),now.toString())).use { c ->
                buildList {
                    while(c.moveToNext()) {
                        val track = c.getString(0)
                        val row = rowsByTrack[track]?.singleOrNull()
                        if (row != null && track !in excludedSet) {
                            add(ListeningRank(row,track,c.getLong(1),c.getLong(2)))
                            period.days?.let { days -> validUntil=minOf(validUntil,c.getLong(3)+days*86_400_000L+1L) }
                        }
                    }
                }
            }
            // If the wall clock was moved backwards, a previously future credit
            // can enter the ranking without any database mutation.
            db.rawQuery("SELECT track_id,MIN(credited_at) FROM sessions WHERE credited_at>? GROUP BY track_id",arrayOf(now.toString())).use { c ->
                while(c.moveToNext()) if(rowsByTrack[c.getString(0)]?.size==1 && c.getString(0) !in excludedSet)
                    validUntil=minOf(validUntil,c.getLong(1))
            }
            ListeningSelection(ListeningHistoryPolicy.rank(candidates),
                excluded.map { ListeningExcluded(it,rowsByTrack[it]?.singleOrNull()) },validUntil)
        }
    }

    /** The original sessions remain the single source of credited plays. Legacy
     * wall time stays at its actual historical last-recorded date. New measured
     * UTC-minute buckets split elapsed listening time at calendar boundaries. */
    suspend fun smartSnapshot(candidates: List<SmartTrackCandidate>, period: SmartStatsPeriod, now: Long,
        seed: Long = now / SmartMusicPolicy.DAY_MS, diversity: Int = 50, forgottenDays: Long = 30,
        zone: java.time.ZoneId = java.time.ZoneId.systemDefault(), interval: SmartStatsInterval? = null): SmartMusicSnapshot = withContext(Dispatchers.IO) {
        com.domenota.medialoader.core.logging.LibraryWorkCounters.increment(com.domenota.medialoader.core.logging.LibraryWork.LISTENING_STATS_QUERY)
        mutex.withLock {
            val db = helper.readableDatabase
            val resolved = SmartMusicPolicy.resolve(candidates,aliasMap(db))
            val excluded = db.rawQuery("SELECT track_id FROM excluded",null).use { c ->
                buildSet { while(c.moveToNext()) add(c.getString(0)) }
            }
            val known=resolved.mapNotNull{it.second}.toHashSet()
            val rowByTrack=resolved.mapNotNull{(row,id)->id?.let{it to row.rowId}}.toMap()
            data class Session(val id:String,val track:String,val start:Long,val last:Long,val media:Long,val wall:Long,
                val credit:Long?,val legacy:Long,val legacyAt:Long,val timed:Long)
            data class Totals(var plays:Long=0,var last:Long=0,var periodPlays:Long=0,var wall:Long=0)
            val sessions=db.rawQuery("SELECT id,track_id,started_at,listened_at,media_ms,wall_ms,credited_at,legacy_wall_ms,legacy_at,timed_wall_ms FROM sessions",null).use{c->
                buildList{while(c.moveToNext())if(c.getString(1) in known)add(Session(c.getString(0),c.getString(1),c.getLong(2),c.getLong(3),c.getLong(4),c.getLong(5),if(c.isNull(6))null else c.getLong(6),c.getLong(7),c.getLong(8),c.getLong(9)))}
            }
            val earliest=sessions.flatMap{session->listOfNotNull(session.credit,session.legacyAt.takeIf{session.legacy>0},
                session.last.takeIf{session.wall-session.timed-session.legacy>0},session.start.takeIf{session.timed>0})}
                .filter{it<=now}.minOrNull()?.let{java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate()}
            val selected=interval?:SmartStatsInterval.overview(period,now,zone,earliest)
            val cutoff=selected.startMs(zone);val end=minOf(selected.endMs(zone),now+1)
            val timeline=selected.buckets().associateWith{longArrayOf(0,0)}.toSortedMap()
            fun point(at:Long)=timeline[selected.bucket(at,zone)]
            val totals=HashMap<String,Totals>()
            val sessionWall=HashMap<String,Long>()
            val approximate=HashSet<String>()
            val firstMinute=HashMap<String,Long>()
            fun addWall(sessionId:String,track:String,at:Long,wall:Long,legacy:Boolean=false){
                if(wall<=0||at<cutoff||at>=end)return
                totals.getOrPut(track){Totals()}.wall+=wall
                point(at)?.let{it[1]+=wall}
                if(selected.isSingleDay){sessionWall[sessionId]=(sessionWall[sessionId]?:0L)+wall;if(legacy)approximate+=sessionId else firstMinute[sessionId]=minOf(firstMinute[sessionId]?:at,at)}
            }
            for(s in sessions){
                val t=totals.getOrPut(s.track){Totals()}
                s.credit?.takeIf{it<end}?.let{at->t.plays++;if(at>=cutoff){t.periodPlays++;point(at)?.let{it[0]++}}}
                if(s.media>0&&s.last<end)t.last=maxOf(t.last,s.last)
                addWall(s.id,s.track,s.legacyAt,s.legacy,true)
                // Old snapshots and any untrusted wall-clock jump have no
                // precise calendar bucket. Do not invent an interval.
                addWall(s.id,s.track,s.last,(s.wall-s.legacy-s.timed).coerceAtLeast(0),true)
            }
            if(end>cutoff){
                val sql=if(selected.isSingleDay) "SELECT m.session_id,s.track_id,m.minute_at,m.wall_ms FROM session_wall_minutes m JOIN sessions s ON s.id=m.session_id WHERE m.minute_at>=? AND m.minute_at<?"
                    else "SELECT '',track_id,minute_at,wall_ms FROM track_wall_minutes WHERE minute_at>=? AND minute_at<?"
                db.rawQuery(sql,arrayOf(cutoff.toString(),end.toString())).use{c->
                    while(c.moveToNext())if(c.getString(1) in known)addWall(c.getString(0),c.getString(1),c.getLong(2),c.getLong(3))
                }
            }
            val rows=resolved.map{(row,track)->
                val data=totals[track]
                SmartTrackHistory(row.rowId,track,row.artist,row.favorite,data?.plays?:0,data?.last?:0,data?.periodPlays?:0,data?.wall?:0,track in excluded)
            }
            val daySessions=if(selected.isSingleDay)sessions.mapNotNull{s->
                val credit=s.credit?.takeIf{it>=cutoff&&it<end}
                val wall=sessionWall[s.id]?:0L
                if(credit==null&&wall==0L)null else SmartDaySession(s.id,rowByTrack.getValue(s.track),s.start,s.last,credit,wall,s.id in approximate,firstMinute[s.id])
            }.sortedWith(compareBy<SmartDaySession>{it.firstMinute?:it.creditedAt?:it.lastAt}.thenBy{it.sessionId}) else emptyList()
            SmartMusicPolicy.snapshot(rows,now,seed,diversity,forgottenDays).copy(
                chart=timeline.map{(day,value)->SmartChartPoint(selected.label(day),value[0],value[1],day)},
                statsInterval=selected,statsPeriod=period,daySessions=daySessions)
        }
    }

    /** Only called after a verified, same-locator MP3 tag replacement.
     * Never merge two existing track identities; old resume keys remain valid. */
    suspend fun relinkVerifiedEdit(before:Set<String>,after:Set<String>)=withContext(Dispatchers.IO){
        require(before.isNotEmpty()&&after.isNotEmpty())
        mutex.withLock{
            val db=helper.writableDatabase
            db.beginTransaction()
            try{
                val aliases=aliasMap(db)
                val old=before.mapNotNull(aliases::get).distinct()
                val fresh=after.mapNotNull(aliases::get).distinct()
                check(old.size<=1&&fresh.size<=1&&(fresh.isEmpty()||old==fresh)){"Неоднозначная история трека"}
                val track=old.singleOrNull()?:UUID.randomUUID().toString().also{id->
                    db.insertOrThrow("tracks",null,ContentValues().apply{put("id",id)})
                }
                for(key in before+after)if(key !in aliases)db.insertOrThrow("aliases",null,ContentValues().apply{
                    put("alias",key);put("track_id",track)
                })
                invalidateVisibility(db)
                db.setTransactionSuccessful()
            }finally{db.endTransaction()}
            visibilityChanges.value++; changed()
        }
    }

    suspend fun resolveResume(sessions:List<ResumeQueueSession>,candidates:List<SmartTrackCandidate>):List<ResolvedResumeQueue> =
        withContext(Dispatchers.IO) { mutex.withLock {
            val aliases=aliasMap(helper.readableDatabase)
            ResumeQueuePolicy.resolveAll(sessions,candidates,aliases)
        }}

    suspend fun setExcluded(trackId: String, excluded: Boolean) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val db = helper.writableDatabase
            db.beginTransaction()
            try {
                if (excluded) {
                    check(db.rawQuery("SELECT id FROM tracks WHERE id=?",arrayOf(trackId)).use { it.moveToFirst() }) { "Трек недоступен" }
                    db.insertWithOnConflict("excluded",null,ContentValues().apply { put("track_id",trackId) },SQLiteDatabase.CONFLICT_IGNORE)
                } else db.delete("excluded","track_id=?",arrayOf(trackId))
                invalidateVisibility(db); db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            visibilityChanges.value++; changed()
        }
    }

    suspend fun restoreAll() = withContext(Dispatchers.IO) { mutex.withLock {
        val db=helper.writableDatabase
        db.beginTransaction()
        try { db.delete("excluded",null,null); invalidateVisibility(db); db.setTransactionSuccessful() } finally { db.endTransaction() }
        visibilityChanges.value++; changed()
    } }
    suspend fun clearHistory() = withContext(Dispatchers.IO) { mutex.withLock {
        val db=helper.writableDatabase
        db.beginTransaction()
        try {
            db.delete("session_wall_minutes",null,null);db.delete("track_wall_minutes",null,null);db.delete("sessions",null,null)
            invalidateVisibility(db);db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        epoch.incrementAndGet(); visibilityChanges.value++; changed()
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
