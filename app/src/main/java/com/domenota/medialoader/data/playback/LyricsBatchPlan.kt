package com.domenota.medialoader.data.playback

import android.content.Context
import android.util.AtomicFile
import com.domenota.medialoader.data.tasks.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal enum class LyricsBatchOutcome { CACHED, DOWNLOADED, SKIPPED, FAILED }
internal data class LyricsBatchPlan(
    val token:TaskCorrelation, val startedAt:Long, val keys:List<String>,
    val cursor:Int=0, val outcomes:TaskOutcomes=TaskOutcomes(),
    val failed:List<String> = emptyList(), val status:TaskStatus=TaskStatus.PAUSED,
    val retryNotBefore:Long=0L, val finishedAt:Long?=null,
) {
    init {
        require(startedAt>=0 && retryNotBefore>=0 && keys.size<=50000)
        require(keys.distinct().size==keys.size && keys.all{it.matches(Regex("[0-9a-f]{64}"))})
        require(cursor in 0..keys.size && outcomes.checked==cursor)
        require(failed.distinct().size==failed.size && failed.size==outcomes.failed)
        val processed=keys.take(cursor).toHashSet()
        require(failed.all{it in processed})
        require(status==TaskStatus.PAUSED || status.terminal)
        require(status.terminal==(finishedAt!=null) && (finishedAt==null || finishedAt>=startedAt))
        if(status==TaskStatus.COMPLETE || status==TaskStatus.PARTIAL)require(cursor==keys.size)
    }
    fun advance(result:LyricsBatchOutcome):LyricsBatchPlan {
        check(cursor<keys.size && !status.terminal)
        val next=when(result){
            LyricsBatchOutcome.CACHED->outcomes.copy(cached=outcomes.cached+1)
            LyricsBatchOutcome.DOWNLOADED->outcomes.copy(generated=outcomes.generated+1)
            LyricsBatchOutcome.SKIPPED->outcomes.copy(skipped=outcomes.skipped+1)
            LyricsBatchOutcome.FAILED->outcomes.copy(failed=outcomes.failed+1)
        }
        return copy(cursor=cursor+1,outcomes=next,failed=if(result==LyricsBatchOutcome.FAILED)failed+keys[cursor] else failed)
    }
    fun terminal(value:TaskStatus,now:Long=System.currentTimeMillis()):LyricsBatchPlan {
        require(value.terminal)
        return copy(status=value,finishedAt=maxOf(now,startedAt))
    }
    fun finish(now:Long=System.currentTimeMillis())=terminal(if(outcomes.failed>0||outcomes.skipped>0)TaskStatus.PARTIAL else TaskStatus.COMPLETE,now)
    fun details(recovered:Boolean=false)=TaskDetails(correlation=token,startedAt=startedAt,trigger=TaskTrigger.USER,
        outcomes=outcomes,recovered=recovered,failure=TaskFailure.UNKNOWN.takeIf{outcomes.failed>0})
    companion object {
        fun fresh(keys:List<String>,token:TaskCorrelation=TaskCorrelation.create(),now:Long=System.currentTimeMillis())=
            LyricsBatchPlan(token,now,keys.distinct())
    }
}

internal object LyricsBatchCodec {
    fun encode(plan:LyricsBatchPlan):JSONObject=JSONObject().put("schema",1)
        .put("token",plan.token.value).put("started",plan.startedAt).put("keys",JSONArray(plan.keys))
        .put("cursor",plan.cursor).put("cached",plan.outcomes.cached).put("downloaded",plan.outcomes.generated)
        .put("skipped",plan.outcomes.skipped).put("failedCount",plan.outcomes.failed)
        .put("failed",JSONArray(plan.failed)).put("status",plan.status.name).put("retryNotBefore",plan.retryNotBefore).put("finishedAt",plan.finishedAt?:JSONObject.NULL)
    fun decode(o:JSONObject):LyricsBatchPlan {
        require(o.getInt("schema")==1)
        fun keys(name:String):List<String> {
            val a=o.getJSONArray(name)
            require(a.length()<=50000)
            return (0 until a.length()).map(a::getString)
        }
        return LyricsBatchPlan(requireNotNull(TaskCorrelation.parse(o.getString("token"))),o.getLong("started"),
            keys("keys"),o.getInt("cursor"),TaskOutcomes(o.getInt("cached"),o.getInt("downloaded"),o.getInt("skipped"),o.getInt("failedCount")),
            keys("failed"),TaskStatus.valueOf(o.getString("status")),o.optLong("retryNotBefore",0L),
            if(o.isNull("finishedAt"))null else o.getLong("finishedAt"))
    }
}
internal class LyricsBatchStore(context:Context) {
    private val file=AtomicFile(File(context.noBackupFilesDir,"lyrics-batch-v1.json"))
    fun read():LyricsBatchPlan? {
        if(!file.baseFile.exists()&&!File(file.baseFile.path+".bak").exists()&&!File(file.baseFile.path+".new").exists())return null
        require(file.baseFile.length()<=8L*1024*1024)
        return LyricsBatchCodec.decode(JSONObject(file.readFully().toString(Charsets.UTF_8)))
    }
    fun save(plan:LyricsBatchPlan) {
        val bytes=LyricsBatchCodec.encode(plan).toString().toByteArray(Charsets.UTF_8)
        require(bytes.size<=8*1024*1024)
        val out=file.startWrite()
        try{out.write(bytes);file.finishWrite(out)}
        catch(error:Throwable){file.failWrite(out);throw error}
    }
}
