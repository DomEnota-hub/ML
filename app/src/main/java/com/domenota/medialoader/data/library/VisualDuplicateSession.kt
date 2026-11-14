package com.domenota.medialoader.data.library

import android.content.Context
import android.util.AtomicFile
import com.domenota.medialoader.core.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal data class VisualDuplicateSession(
    val result:VisualScanResult,val selected:Set<String>,val index:Int,val offset:Int,val scannedAt:Long
) {
    /** Never select every member, including after a partial trash operation. */
    fun normalized():VisualDuplicateSession {
        val allowed=result.groups.flatMap { group ->
            val ids=group.files.map{it.id}
            if(ids.all{it in selected})ids.dropLast(1) else ids.filter{it in selected}
        }.toSet()
        return copy(selected=allowed,index=index.coerceIn(0,(result.groups.size-1).coerceAtLeast(0)),offset=offset.coerceAtLeast(0))
    }
    /** Reconcile only against a complete public catalog. Never use this to
     * authorize a trash operation; that requires a fresh full fingerprint. */
    fun reconcile(refs:List<LibraryMediaRef>):VisualDuplicateSession {
        val byId=refs.groupBy{it.key}
        val groups=result.groups.mapNotNull{group->
            val remaining=group.files.filter{snapshot->
                val ref=byId[snapshot.id]?.singleOrNull()
                ref!=null && VisualDuplicateScanner.matchesCatalog(snapshot,ref)
            }
            if(remaining.size>=2)group.copy(files=remaining) else null
        }
        return copy(result=result.copy(groups=groups)).normalized()
    }

    fun removeConfirmed(uris:Set<String>):VisualDuplicateSession =
        copy(result=result.copy(groups=result.groups.mapNotNull{g->
            val remaining=g.files.filter{it.uri !in uris}
            if(remaining.size>=2)g.copy(files=remaining) else null
        })).normalized()
}

/** Private atomic checkpoint; cancellation of a new scan leaves the previous result intact. */
internal object VisualDuplicateSessionStore {
    private val lock=Any()
    private fun file(context:Context)=AtomicFile(File(context.noBackupFilesDir,"visual-duplicates-v1.json"))
    suspend fun load(context:Context):VisualDuplicateSession?=withContext(Dispatchers.IO){
        synchronized(lock){runCatching{decode(file(context).openRead().bufferedReader().use{it.readText()})}.getOrNull()}
    }
    suspend fun save(context:Context,session:VisualDuplicateSession):Boolean=withContext(Dispatchers.IO+NonCancellable){
        synchronized(lock){runCatching{
            val target=file(context);val stream=target.startWrite()
            try{stream.write(encode(session.normalized()).toByteArray(Charsets.UTF_8));target.finishWrite(stream)}
            catch(e:Exception){target.failWrite(stream);throw e}
        }.isSuccess}
    }
    /** Update only position, preserving a newer selection or scan written concurrently. */
    suspend fun savePosition(context:Context,scannedAt:Long,index:Int,offset:Int):Boolean=withContext(Dispatchers.IO+NonCancellable){
        synchronized(lock){runCatching{
            val target=file(context)
            val previous=decode(target.openRead().bufferedReader().use{it.readText()})
            if(previous.scannedAt==scannedAt){
                val stream=target.startWrite()
                try{stream.write(encode(previous.copy(index=index,offset=offset).normalized()).toByteArray(Charsets.UTF_8));target.finishWrite(stream)}
                catch(e:Exception){target.failWrite(stream);throw e}
            }
        }.isSuccess}
    }
    /** Apply confirmed moves to the persisted generation only, without replacing newer scans. */
    suspend fun removeConfirmed(context:Context,scannedAt:Long,uris:Set<String>):VisualDuplicateSession?=withContext(Dispatchers.IO+NonCancellable){
        synchronized(lock){runCatching{
            val target=file(context)
            val previous=decode(target.openRead().bufferedReader().use{it.readText()})
            check(previous.scannedAt==scannedAt)
            val next=previous.removeConfirmed(uris)
            val stream=target.startWrite()
            try{stream.write(encode(next).toByteArray(Charsets.UTF_8));target.finishWrite(stream)}
            catch(e:Exception){target.failWrite(stream);throw e}
            next
        }.getOrNull()}
    }
    internal fun encode(session:VisualDuplicateSession):String {
        val groups=JSONArray()
        session.result.groups.forEach{g->
            val files=JSONArray()
            g.files.forEach{f->files.put(JSONObject().put("id",f.id).put("uri",f.uri).put("type",f.type.name)
                .put("bytes",f.bytes).put("modified",f.modified?:JSONObject.NULL).put("sha",f.sha)
                .put("width",f.width).put("height",f.height).put("duration",f.durationMs?:JSONObject.NULL)
                .put("frames",JSONArray(f.frames)).put("informative",f.informative))}
            groups.put(JSONObject().put("reason",g.reason.name).put("files",files))
        }
        return JSONObject().put("schema",1).put("groups",groups).put("skipped",session.result.skipped)
            .put("selected",JSONArray(session.selected.toList())).put("index",session.index)
            .put("offset",session.offset).put("scannedAt",session.scannedAt).toString()
    }
    internal fun decode(raw:String):VisualDuplicateSession {
        val json=JSONObject(raw);require(json.getInt("schema")==1)
        val groups=json.getJSONArray("groups")
        val rows=(0 until groups.length()).map{i->
            val g=groups.getJSONObject(i);val files=g.getJSONArray("files")
            val members=(0 until files.length()).map{j->
                val f=files.getJSONObject(j);val frames=f.getJSONArray("frames")
                fun optional(key:String)=if(f.isNull(key))null else f.getLong(key)
                VisualFingerprint(f.getString("id"),f.getString("uri"),MediaType.valueOf(f.getString("type")),
                    f.getLong("bytes"),optional("modified"),f.getString("sha"),f.getInt("width"),f.getInt("height"),
                    optional("duration"),(0 until frames.length()).map{frames.getLong(it)},f.getBoolean("informative"))
            }
            require(members.size>=2&&members.all{it.type!=MediaType.AUDIO})
            require(members.map{it.id}.distinct().size==members.size&&members.map{it.uri}.distinct().size==members.size)
            VisualDuplicateGroup(members,VisualMatch.valueOf(g.getString("reason")))
        }
        require(rows.flatMap{it.files}.map{it.id}.let{it.size==it.distinct().size})
        val selected=json.getJSONArray("selected")
        return VisualDuplicateSession(VisualScanResult(rows,json.getInt("skipped")),
            (0 until selected.length()).map{selected.getString(it)}.toSet(),json.getInt("index"),
            json.getInt("offset"),json.getLong("scannedAt")).normalized()
    }
}
