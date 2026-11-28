package com.domenota.medialoader.data.tasks
import org.json.JSONArray
import org.json.JSONObject
/** Bounded, typed checkpoint metadata; no media identities. */
internal object TaskCheckpointCodec {
    fun outcomes(value:TaskOutcomes)=JSONObject().put("cached",value.cached).put("generated",value.generated)
        .put("skipped",value.skipped).put("failed",value.failed)
    fun readOutcomes(value:JSONObject)=TaskOutcomes(value.getInt("cached"),value.getInt("generated"),value.getInt("skipped"),value.getInt("failed"))
    fun encode(value:TaskCheckpointSummary)=JSONObject().put("count",value.count).put("recent",JSONArray(value.recent.map{
        JSONObject().put("ordinal",it.ordinal).put("time",it.completedAt).put("outcomes",outcomes(it.outcomes))
    }))
    fun decode(value:JSONObject?):TaskCheckpointSummary?=value?.let { root->runCatching {
        val array=root.getJSONArray("recent");require(array.length()<=20)
        TaskCheckpointSummary(root.getInt("count"),(0 until array.length()).map{i->
            val item=array.getJSONObject(i)
            TaskCheckpoint(item.getInt("ordinal"),readOutcomes(item.getJSONObject("outcomes")),item.getLong("time"))
        })
    }.getOrNull() }
}
