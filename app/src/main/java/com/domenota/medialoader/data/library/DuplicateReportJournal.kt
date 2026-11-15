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
    fun savePartial(context:Context,kind:String,encoded:String){
        val dir=root(context,kind).apply{mkdirs()};write(AtomicFile(File(dir,"partial.json")),encoded)
    }
    fun partial(context:Context,kind:String):String?=runCatching{AtomicFile(File(root(context,kind),"partial.json")).openRead().bufferedReader().use{it.readText()}}.getOrNull()
    fun clearPartial(context:Context,kind:String){
        val target=AtomicFile(File(root(context,kind),"partial.json"));target.delete()
        check(listOf("partial.json","partial.json.bak","partial.json.new").none{File(root(context,kind),it).exists()})
    }
    private fun write(target:AtomicFile,value:String){
        val stream=target.startWrite()
        try{stream.write(value.toByteArray(Charsets.UTF_8));target.finishWrite(stream)}
        catch(e:Exception){target.failWrite(stream);throw e}
    }
}
