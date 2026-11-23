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

internal data class DuplicateTrashReportRow(val id:String,val name:String,val success:Boolean,val reason:String)
internal data class DuplicateTrashReport(val at:Long,val epoch:Long,val rows:List<DuplicateTrashReportRow>)

/** One private, non-backed-up snapshot. Atomic replacement preserves the previous successful scan. */
internal object MusicDuplicateSessionStore {
    private fun file(context: Context) = AtomicFile(File(context.noBackupFilesDir, "music-duplicates-v1.json"))
    private val lock = DuplicatePrivacyGuard.lock
    private val journalRevision = java.util.concurrent.atomic.AtomicLong(0L)
    // Reading this token must never wait for a long-running report decode/commit under the privacy lock.
    fun journalGeneration():Long = journalRevision.get()
    /** Clear reports only; an old debounced UI save must not recreate them. */
    fun clearJournal(context:Context,includePartial:Boolean):Boolean = synchronized(lock){
        journalRevision.incrementAndGet()
        runCatching{
            val target=file(context);target.delete()
            check(listOf(target.baseFile,File(target.baseFile.path+".bak"),File(target.baseFile.path+".new")).none{it.exists()})
            DuplicateReportJournal.clearCompleted(context,"music")
            MusicDuplicateShardStore.clearCompleted(context)
            if(includePartial){DuplicateReportJournal.clearPartial(context,"music");MusicDuplicateShardStore.clearPartialSelection(context)}
        }.isSuccess
    }
    /** Delete one completed scan from all backends; never touch partial scans
     * or user media. Revision invalidates any captured debounced UI writer.
     */
    fun deleteCompleted(context:Context,at:Long):Boolean = synchronized(lock){
        if(at<=0L || DuplicateBackgroundScan.state.value.running ||
            DuplicateBackgroundScan.state.value.committing ||
            DuplicateReportJournal.isDeleted(context,"music",at))return@synchronized false
        val legacy=file(context)
        val legacyAt=runCatching{
            decode(legacy.openRead().bufferedReader().use{it.readText()}).scannedAt
        }.getOrNull()
        val archive=File(context.noBackupFilesDir,"duplicate-journal-music/$at.json")
        val existed=legacyAt==at || archive.exists() ||
            MusicDuplicateShardStore.hasCompletedAt(context,at)
        if(!existed)return@synchronized false
        journalRevision.incrementAndGet()
        runCatching{
            DuplicateReportJournal.markDeleted(context,"music",at)
            MusicDuplicateShardStore.deleteCompleted(context,at)
            if(legacyAt==at) {
                legacy.delete()
                check(!legacy.baseFile.exists()){"LEGACY_REPORT_DELETE_FAILED"}
            }
            DuplicateReportJournal.deleteCompleted(context,"music",at)
            check(DuplicateReportJournal.isDeleted(context,"music",at)){"REPORT_DELETE_NOT_RECORDED"}
        }.isSuccess
    }

    fun discardPartial(context:Context):Boolean = synchronized(lock){
        // Invalidate any pending debounced UI snapshot before clearing this
        // checkpoint. Otherwise a late save can resurrect a discarded scan.
        journalRevision.incrementAndGet()
        runCatching{DuplicateReportJournal.clearPartial(context,"music");MusicDuplicateShardStore.clearPartialSelection(context)}.isSuccess
    }
    private fun trashReportFile(context:Context)=AtomicFile(File(context.noBackupFilesDir,"music-duplicate-trash-v1.json"))
    fun clearForPrivacy(context: Context) {
        journalRevision.incrementAndGet()
        DuplicateReportJournal.clear(context,"music")
        MusicDuplicateShardStore.clear(context)
        val target=file(context);target.delete()
        val report=trashReportFile(context);report.delete()
        check(!target.baseFile.exists()&&!report.baseFile.exists()){"Не удалось очистить отчёт дубликатов"}
    }
    private fun reportPermitted(context:Context,report:DuplicateTrashReport):Boolean =
        report.epoch==DuplicatePrivacyGuard.generation(context)&&report.rows.all{DuplicatePrivacyGuard.accepts(context,report.epoch,it.id)}
    suspend fun saveTrashReport(context:Context,report:DuplicateTrashReport):Boolean=withContext(Dispatchers.IO+NonCancellable){
        synchronized(lock){
            if(!reportPermitted(context,report))return@synchronized false
            val json=JSONObject().put("at",report.at).put("epoch",report.epoch).put("rows",JSONArray().apply{
                report.rows.forEach{row->put(JSONObject().put("id",row.id).put("name",row.name)
                    .put("success",row.success).put("reason",row.reason))}
            })
            runCatching{write(trashReportFile(context),json.toString())}.isSuccess
        }
    }
    suspend fun trashReport(context:Context):DuplicateTrashReport?=withContext(Dispatchers.IO){
        synchronized(lock){runCatching{
            val json=JSONObject(trashReportFile(context).openRead().bufferedReader().use{it.readText()})
            val array=json.getJSONArray("rows")
            DuplicateTrashReport(json.getLong("at"),json.getLong("epoch"),(0 until array.length()).map{i->
                val row=array.getJSONObject(i)
                DuplicateTrashReportRow(row.getString("id"),row.getString("name"),row.getBoolean("success"),row.getString("reason"))
            }).takeIf{reportPermitted(context,it)}
        }.getOrNull()}
    }
    private fun write(target:AtomicFile,encoded:String){
        val stream=target.startWrite()
        try{stream.write(encoded.toByteArray(Charsets.UTF_8));target.finishWrite(stream)}
        catch(e:Exception){target.failWrite(stream);throw e}
    }
    private fun permitted(context: Context, session: DuplicateSession): Boolean = session.result.privacyEpoch==DuplicatePrivacyGuard.generation(context) && session.result.groups.all { group ->
        group.tracks.all { DuplicatePrivacyGuard.accepts(context,it.privacyEpoch,it.id) }
    }
    suspend fun load(context: Context): DuplicateSession? = withContext(Dispatchers.IO) {
        synchronized(lock) {
            runCatching { MusicDuplicateShardStore.load(context)?.takeIf { permitted(context,it) &&
                !DuplicateReportJournal.isDeleted(context,"music",it.scannedAt) } }.getOrNull()
                ?: runCatching { decode(file(context).openRead().bufferedReader().use { it.readText() }).takeIf { permitted(context,it) &&
                !DuplicateReportJournal.isDeleted(context,"music",it.scannedAt) } }.getOrNull()
        }
    }
    suspend fun history(context:Context):List<DuplicateSession> = withContext(Dispatchers.IO){synchronized(lock){
        val current=runCatching{decode(file(context).openRead().bufferedReader().use{it.readText()})}.getOrNull()
        (MusicDuplicateShardStore.history(context)+listOfNotNull(current)+DuplicateReportJournal.history(context,"music").mapNotNull{runCatching{decode(it)}.getOrNull()})
            .filter{permitted(context,it) &&
                !DuplicateReportJournal.isDeleted(context,"music",it.scannedAt)}
            .distinctBy{it.scannedAt}.sortedByDescending{it.scannedAt}
    }}
    /** Checkpoints are independent of successful reports and are never promoted implicitly. */
    fun savePartial(context:Context,session:DuplicateSession,expectedJournalGeneration:Long?=null):Boolean=synchronized(lock){
        if(expectedJournalGeneration!=null && expectedJournalGeneration!=journalRevision.get())return@synchronized false
        if(!permitted(context,session) ||
            DuplicateReportJournal.isDeleted(context,"music",session.scannedAt))return@synchronized false
        runCatching{DuplicateReportJournal.savePartial(context,"music",encode(session))}.isSuccess
    }
    suspend fun partial(context:Context):DuplicateSession?=withContext(Dispatchers.IO){synchronized(lock){
        val legacy=runCatching{
            DuplicateReportJournal.partial(context,"music")?.let(::decode)?.takeIf{permitted(context,it)}
                ?.let{MusicDuplicateShardStore.applySelection(context,it,true)}
        }.getOrNull()
        val recovered=runCatching{MusicDuplicateShardStore.recovery(context)?.takeIf{permitted(context,it)}}.getOrNull()
        listOfNotNull(legacy,recovered).maxByOrNull{it.result.groups.size}
    }}
    /** Store only lightweight UI choices. Moving the list must not reserialize thousands
     * of acoustic landmarks or overwrite a completed/partial scan payload. */
    suspend fun saveInteraction(context:Context,session:DuplicateSession,partial:Boolean,
                                expectedJournalGeneration:Long?=null):Boolean=withContext(Dispatchers.IO){
        synchronized(lock) {
            if(expectedJournalGeneration!=null&&expectedJournalGeneration!=journalRevision.get())return@synchronized false
            if(!permitted(context,session) ||
            DuplicateReportJournal.isDeleted(context,"music",session.scannedAt))return@synchronized false
            runCatching{MusicDuplicateShardStore.updateSelection(context,session,partial)}.isSuccess
        }
    }
    suspend fun save(context: Context, session: DuplicateSession, expectedJournalGeneration:Long?=null): Boolean = withContext(Dispatchers.IO + NonCancellable) {
        synchronized(lock) {
            if(expectedJournalGeneration!=null&&expectedJournalGeneration!=journalRevision.get()) {
                DuplicateSaveDiagnostic.record(context,"GENERATION_MISMATCH",null)
                return@synchronized false
            }
            if(!permitted(context,session) ||
                DuplicateReportJournal.isDeleted(context,"music",session.scannedAt)) {
                DuplicateSaveDiagnostic.record(context,"PRIVACY_GUARD",null)
                return@synchronized false
            }
            runCatching {
                MusicDuplicateShardStore.commit(context,session)
            }.onFailure { failure->
                DuplicateSaveDiagnostic.record(context,"SHARD_COMMIT_FAILED",failure)
            }.isSuccess
        }
    }
    /** Only verified successes are removed, including archived and partial reports.
     * Keep the user's remaining selections when a group still has a keeper. */
    private fun withoutTrashed(old:DuplicateSession,uris:Set<String>):DuplicateSession{
        val groups=old.result.groups.mapNotNull{g->
            val remaining=g.tracks.filter{it.uri !in uris}
            if(remaining.size>=2)g.copy(tracks=remaining)else null
        }
        val keep=groups.associate{g->g.key to (old.kept.entries.firstOrNull{(_,id)->g.tracks.any{it.id==id}}?.value
            ?:MusicDuplicatePolicy.recommended(g))}
        val selected=old.selected.intersect(groups.flatMap{g->g.tracks.filter{it.id!=keep[g.key]}.map{it.id}}.toSet())
        return old.copy(result=old.result.copy(groups=groups),selected=selected,kept=keep,
            index=old.index.coerceAtMost((groups.size-1).coerceAtLeast(0)))
    }
    suspend fun removeTrashed(context:Context,uris:Set<String>)=withContext(Dispatchers.IO+NonCancellable){
        synchronized(lock){
            val target=file(context)
            val sharded=runCatching{MusicDuplicateShardStore.load(context)}.getOrNull()
            if(sharded!=null && permitted(context,sharded) && sharded.result.groups.any{g->g.tracks.any{it.uri in uris}})
                MusicDuplicateShardStore.commit(context,withoutTrashed(sharded,uris))
            val old=runCatching{decode(target.openRead().bufferedReader().use{it.readText()})}.getOrNull()
            if(old!=null&&permitted(context,old))write(target,encode(withoutTrashed(old,uris)))
            DuplicateReportJournal.history(context,"music").forEach{raw->
                val saved=runCatching{decode(raw)}.getOrNull()
                if(saved!=null&&permitted(context,saved)&&saved.result.groups.any{g->g.tracks.any{it.uri in uris}})
                    DuplicateReportJournal.archive(context,"music",saved.scannedAt,encode(withoutTrashed(saved,uris)))
            }
            DuplicateReportJournal.partial(context,"music")?.let{raw->
                val saved=runCatching{decode(raw)}.getOrNull()
                if(saved!=null&&permitted(context,saved)&&saved.result.groups.any{g->g.tracks.any{it.uri in uris}})
                    DuplicateReportJournal.savePartial(context,"music",encode(withoutTrashed(saved,uris)))
            }
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
                        AcousticFingerprint(packedAcousticLandmarks((0 until array.length()).map { i -> array.getJSONArray(i).let { AcousticLandmark(it.getInt(0),it.getInt(1)) } }))
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
            .put("acousticUnavailable",session.result.acousticUnavailable).put("expanded",session.result.expanded).put("privacyEpoch",session.result.privacyEpoch)
            .put("scopeLabel",session.result.scopeLabel)
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
        return DuplicateSession(DuplicateScanResult(groups,json.getInt("skipped"),json.optInt("acousticUnavailable",0),json.optBoolean("expanded",false),json.optLong("privacyEpoch",groups.firstOrNull()?.tracks?.firstOrNull()?.privacyEpoch?:0L),json.optString("scopeLabel","Все файлы")),selected,keep,
            json.getInt("index").coerceAtLeast(0),json.getInt("offset").coerceAtLeast(0),json.getLong("scannedAt"))
    }
}
