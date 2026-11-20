package com.domenota.medialoader.data.library

import android.content.Context
import android.util.AtomicFile
import java.io.File

/** Independent bounded journals. Active checkpoints never replace a completed report. */
internal object DuplicateReportJournal {
    private const val LIMIT=8
    private fun root(context:Context,kind:String)=File(context.noBackupFilesDir,"duplicate-journal-"+kind)
    fun clear(context:Context,kind:String){check(root(context,kind).deleteRecursively())}
    fun clearCompleted(context:Context,kind:String){
        // Only numeric completed report files and their AtomicFile companions.
        val completed=Regex("[0-9]+\\.json(?:\\.bak|\\.new)?")
        root(context,kind).listFiles().orEmpty().filter{it.name.matches(completed)}.forEach{check(it.delete()||!it.exists())}
        check(root(context,kind).listFiles().orEmpty().none{it.name.matches(completed)})
    }
    fun archive(context:Context,kind:String,at:Long,encoded:String){
        if(at<=0)return
        val dir=root(context,kind).apply{mkdirs()}
        write(AtomicFile(File(dir,"$at.json")),encoded)
        dir.listFiles().orEmpty().filter{it.name.matches(Regex("[0-9]+\\.json"))}
            .sortedByDescending{it.nameWithoutExtension.toLongOrNull()?:0L}.drop(LIMIT).forEach{it.delete()}
    }
    fun history(context:Context,kind:String):List<String> = root(context,kind).listFiles().orEmpty()
        .filter{it.name.matches(Regex("[0-9]+\\.json"))}.sortedByDescending{it.nameWithoutExtension.toLongOrNull()?:0L}
        .take(LIMIT).mapNotNull{runCatching{AtomicFile(it).openRead().bufferedReader().use{r->r.readText()}}.getOrNull()}
    /** Retain the previous interrupted result BEFORE a new attempt can replace
     * partial.json with its initial empty snapshot. This is deliberately separate
     * from completed reports and requires no new audio decoding. */
    fun preserveMusicPartial(context:Context) {
        val dir=root(context,"music").apply{mkdirs()}
        val source=AtomicFile(File(dir,"partial.json"))
        val backup=AtomicFile(File(dir,"preserved-partial.json"))
        if(!source.baseFile.exists() || backup.baseFile.exists())return
        val stream=backup.startWrite()
        try{
            source.openRead().use{input->input.copyTo(stream,65536)}
            backup.finishWrite(stream)
        }catch(t:Throwable){backup.failWrite(stream);throw t}
    }
    fun savePartial(context:Context,kind:String,encoded:String){
        val dir=root(context,kind).apply{mkdirs()};write(AtomicFile(File(dir,"partial.json")),encoded)
    }
    fun partial(context:Context,kind:String):String? {
        fun read(name:String)=runCatching{
            AtomicFile(File(root(context,kind),name)).openRead().bufferedReader().use{it.readText()}
        }.getOrNull()
        val current=read("partial.json")
        if(kind!="music")return current
        val old=read("preserved-partial.json") ?: return current
        if(current==null)return old
        fun groupCount(json:String)=runCatching{org.json.JSONObject(json).getJSONArray("groups").length()}.getOrDefault(-1)
        return if(groupCount(old)>groupCount(current))old else current
    }
    fun clearPartial(context:Context,kind:String){
        val names=if(kind=="music")listOf("partial.json","preserved-partial.json")
            else listOf("partial.json")
        names.forEach{name->
            AtomicFile(File(root(context,kind),name)).delete()
            check(listOf(name,"$name.bak","$name.new").none{File(root(context,kind),it).exists()})
        }
    }
    private fun write(target:AtomicFile,value:String){
        val stream=target.startWrite()
        try{stream.write(value.toByteArray(Charsets.UTF_8));target.finishWrite(stream)}
        catch(e:Exception){target.failWrite(stream);throw e}
    }
}
