package com.domenota.medialoader.data.library

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * Schema 2: completed scan data is written once, in bounded immutable chunks.
 * There is no monolithic completed-result JSON, and the active pointer moves
 * ONLY after each chunk and the manifest have been independently verified.
 *
 * Schema 1 completed/partial reports are still read by MusicDuplicateSessionStore.
 * A failed migration leaves them (and the owner's partial.json) unchanged.
 */
internal object MusicDuplicateShardStore {
    private const val SCHEMA = 2
    private const val GROUPS_PER_CHUNK = 100
    private const val BYTES_PER_CHUNK = 2 * 1024 * 1024
    private const val MAX_CHUNKS = 4096
    private fun root(context:Context)=File(context.noBackupFilesDir,"music-duplicate-results-v2")
    private fun active(context:Context)=AtomicFile(File(root(context),"active.json"))
    private fun identity(context:Context,snapshot:DuplicateSession)=
        snapshot.result.privacyEpoch==DuplicatePrivacyGuard.generation(context)

    private fun sha(file:File):String{
        val md=MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use{input->val buffer=ByteArray(65536);while(true){
            val read=input.read(buffer);if(read<0)break;md.update(buffer,0,read)
        }}
        return md.digest().joinToString(""){"%02x".format(it)}
    }
    private fun writeAtomic(file:File,writer:(java.io.Writer)->Unit) {
        val atomic=AtomicFile(file)
        val stream=atomic.startWrite()
        try {
            val out=stream.writer(Charsets.UTF_8).buffered(32768)
            writer(out)
            out.flush()
            atomic.finishWrite(stream)
        } catch(t:Throwable) {
            atomic.failWrite(stream)
            throw t
        }
    }
    private fun atomicText(file:File):String=AtomicFile(file).openRead().bufferedReader().use{it.readText()}
    private fun groupJson(group:DuplicateGroup):String{
        val tracks=JSONArray()
        group.tracks.forEach{tracks.put(MusicDuplicateSessionStore.encodeTrack(it))}
        return JSONObject().put("title",group.title).put("reason",group.reason)
            .put("kind",group.kind.name).put("tracks",tracks).toString()
    }
    private fun decodeGroup(text:String):DuplicateGroup{
        val group=JSONObject(text)
        val rows=group.getJSONArray("tracks")
        val tracks=(0 until rows.length()).map{MusicDuplicateSessionStore.decodeTrack(rows.getJSONObject(it))}
        require(tracks.size>=2){"Invalid duplicate group"}
        return DuplicateGroup(tracks,group.getString("title"),group.getString("reason"),
            DuplicateKind.valueOf(group.getString("kind")))
    }
    internal fun chunksForGroups(groups:List<String>,maxGroups:Int=GROUPS_PER_CHUNK,
                                 maxBytes:Int=BYTES_PER_CHUNK):List<List<String>> {
        require(maxGroups>0 && maxBytes>0)
        val chunks=mutableListOf<List<String>>()
        var current=mutableListOf<String>()
        var bytes=0
        for(group in groups) {
            val size=group.toByteArray(Charsets.UTF_8).size+1
            if(current.isNotEmpty() && (current.size>=maxGroups || bytes+size>maxBytes)) {
                chunks.add(current);current=mutableListOf();bytes=0
            }
            current.add(group);bytes+=size
        }
        if(current.isNotEmpty())chunks.add(current)
        return chunks
    }
    /** Only main service completion calls this. A report never becomes complete
     * until the last verified manifest and active pointer are durable. */
    fun commit(context:Context,session:DuplicateSession) {
        require(identity(context,session)){"PRIVACY_EPOCH_CHANGED"}
        val folder=root(context).apply{mkdirs()}
        val id="scan_"+session.scannedAt+"_"+UUID.randomUUID().toString().replace("-","")
        val generation=File(folder,id).apply{check(mkdirs())}
        val headers=JSONArray()
        var written=0
        var position=0
        var currentBytes=0
        var currentGroups=0
        var writer:java.io.BufferedWriter?=null
        var atomic:AtomicFile?=null
        var output:java.io.FileOutputStream?=null
        var target:File?=null
        fun endChunk() {
            if(currentGroups==0)return
            val out=requireNotNull(writer);val stream=requireNotNull(output);val file=requireNotNull(target)
            try {out.flush();requireNotNull(atomic).finishWrite(stream)}
            catch(t:Throwable){requireNotNull(atomic).failWrite(stream);throw t}
            headers.put(JSONObject().put("name",file.name).put("sha",sha(file))
                .put("bytes",file.length()).put("groups",currentGroups))
            written+=currentGroups
            writer=null;output=null;target=null;atomic=null;currentGroups=0;currentBytes=0
        }
        try {
            for(group in session.result.groups){
                require(identity(context,session)){"PRIVACY_EPOCH_CHANGED"}
                // Materialize exactly ONE group at a time. A single oversized group
                // is kept together; it is never silently truncated or split.
                val encoded=groupJson(group)
                val size=encoded.toByteArray(Charsets.UTF_8).size+1
                if(currentGroups>0&&(currentGroups>=GROUPS_PER_CHUNK || currentBytes+size>BYTES_PER_CHUNK))endChunk()
                if(writer==null) {
                    require(position<MAX_CHUNKS){"TOO_MANY_CHUNKS"}
                    val chunk=File(generation,"chunk_%05d.jsonl".format(position++))
                    target=chunk;atomic=AtomicFile(chunk)
                    output=requireNotNull(atomic).startWrite()
                    writer=requireNotNull(output).writer(Charsets.UTF_8).buffered(32768)
                }
                writer!!.write(encoded);writer!!.newLine()
                currentGroups++;currentBytes+=size
            }
            endChunk()
            check(written==session.result.groups.size){"GROUP_COUNT_MISMATCH"}
            // Keep only small mutable UI values in a separate atomic file.
            val manifest=JSONObject()
                .put("schema",SCHEMA).put("id",id).put("at",session.scannedAt)
                .put("epoch",session.result.privacyEpoch).put("expanded",session.result.expanded)
                .put("skipped",session.result.skipped).put("acousticUnavailable",session.result.acousticUnavailable)
                .put("groupCount",written).put("chunks",headers)
            writeAtomic(File(generation,"manifest.json")){it.write(manifest.toString())}
            // Round-trip each chunk and validate SHA/group count BEFORE publish.
            requireNotNull(readGeneration(context,id,requireCurrentPrivacy=true))
            updateSelection(context,session,false)
            writeAtomic(active(context).baseFile){it.write(JSONObject().put("id",id).toString())}
        } catch(t:Throwable){
            // Partial generations are intentionally retained for diagnostics and
            // potential recovery, while active and legacy reports stay untouched.
            if(output!=null)runCatching{requireNotNull(atomic).failWrite(output)}
            throw t
        }
    }
    private fun readGeneration(context:Context,id:String,requireCurrentPrivacy:Boolean):DuplicateSession? {
        require(id.matches(Regex("scan_[0-9]+_[a-f0-9]{32}"))){"INVALID_ID"}
        val dir=File(root(context),id)
        val doc=JSONObject(atomicText(File(dir,"manifest.json")))
        require(doc.getInt("schema")==SCHEMA && doc.getString("id")==id)
        val epoch=doc.getLong("epoch")
        if(requireCurrentPrivacy && epoch!=DuplicatePrivacyGuard.generation(context))return null
        val chunks=doc.getJSONArray("chunks")
        require(chunks.length()<=MAX_CHUNKS)
        val groups=ArrayList<DuplicateGroup>(doc.getInt("groupCount"))
        for(i in 0 until chunks.length()){
            val part=chunks.getJSONObject(i)
            val expected="chunk_%05d.jsonl".format(i)
            require(part.getString("name")==expected){"SHARD_SEQUENCE"}
            val file=File(dir,expected)
            require(file.length()==part.getLong("bytes") && sha(file)==part.getString("sha")){"SHARD_CHECKSUM"}
            val sizeBefore=groups.size
            file.bufferedReader().useLines{lines->lines.forEach { line ->
                require(line.isNotBlank());groups.add(decodeGroup(line))
            }}
            require(groups.size-sizeBefore==part.getInt("groups")){"SHARD_COUNT"}
        }
        require(groups.size==doc.getInt("groupCount")){"TOTAL_GROUP_COUNT"}
        return DuplicateSession(DuplicateScanResult(groups,doc.getInt("skipped"),
            doc.getInt("acousticUnavailable"),doc.getBoolean("expanded"),epoch),
            emptySet(),emptyMap(),0,0,doc.getLong("at"))
    }
    fun load(context:Context):DuplicateSession? {
        if(!active(context).baseFile.exists())return null
        val pointer=JSONObject(atomicText(active(context).baseFile)).getString("id")
        return readGeneration(context,pointer,true)?.let{applySelection(context,it,false)}
    }
    fun history(context:Context):List<DuplicateSession> = root(context).listFiles().orEmpty()
        .asSequence().filter{it.isDirectory&&it.name.startsWith("scan_")}
        .sortedByDescending{it.lastModified()}.take(12)
        .mapNotNull{runCatching{readGeneration(context,it.name,true)}.getOrNull()}
        .distinctBy{it.scannedAt}.toList()

    private fun stateFile(context:Context,partial:Boolean)=AtomicFile(
        File(root(context),if(partial)"partial-ui.json" else "complete-ui.json"))
    fun updateSelection(context:Context,session:DuplicateSession,partial:Boolean) {
        require(identity(context,session)){"PRIVACY_EPOCH_CHANGED"}
        root(context).mkdirs()
        val data=JSONObject().put("schema",SCHEMA).put("at",session.scannedAt)
            .put("epoch",session.result.privacyEpoch).put("selected",JSONArray(session.selected.toList()))
            .put("index",session.index).put("offset",session.offset)
            .put("kept",JSONObject().also{obj->session.kept.forEach{(k,v)->obj.put(k,v)}})
        writeAtomic(stateFile(context,partial).baseFile){it.write(data.toString())}
    }
    fun applySelection(context:Context,saved:DuplicateSession,partial:Boolean):DuplicateSession {
        val target=stateFile(context,partial)
        if(!target.baseFile.exists())return saved
        return runCatching{
            val state=JSONObject(atomicText(target.baseFile))
            require(state.getInt("schema")==SCHEMA && state.getLong("at")==saved.scannedAt &&
                state.getLong("epoch")==saved.result.privacyEpoch)
            val ids=saved.result.groups.flatMap{it.tracks}.map{it.id}.toSet()
            val choices=state.getJSONArray("selected")
            val selected=(0 until choices.length()).map{choices.getString(it)}.filter{it in ids}.toSet()
            val chosen=state.getJSONObject("kept")
            val kept=chosen.keys().asSequence().associateWith{chosen.getString(it)}
                .filterKeys{key->saved.result.groups.any{it.key==key}}
            saved.copy(selected=selected,kept=kept,index=state.getInt("index"),
                offset=state.getInt("offset"))
        }.getOrElse{saved}
    }
    fun clearCompleted(context:Context) {
        val dir=root(context)
        dir.listFiles().orEmpty().filter{it.isDirectory&&it.name.startsWith("scan_")}
            .forEach{check(it.deleteRecursively()){"SHARD_CLEAR_FAILED"}}
        active(context).delete()
        stateFile(context,false).delete()
    }
    fun clear(context:Context) {
        val path=root(context)
        if(path.exists())check(path.deleteRecursively()){"SHARD_CLEAR_FAILED"}
        check(!path.exists()){"SHARD_CLEAR_FAILED"}
    }
}
