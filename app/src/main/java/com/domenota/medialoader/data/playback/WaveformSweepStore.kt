package com.domenota.medialoader.data.playback

import android.content.SharedPreferences
import com.domenota.medialoader.data.tasks.*

internal class WaveformSweepStore(private val prefs: SharedPreferences) {
    fun read():WaveformSweep? = runCatching {
        val token=TaskCorrelation.parse(prefs.getString("sweep_token",null)) ?: return null
        val outcomes=TaskOutcomes(prefs.getInt("sweep_cached",0),prefs.getInt("sweep_generated",0),
            prefs.getInt("sweep_skipped",0),prefs.getInt("sweep_failed",0))
        val checkpointed=prefs.getString("sweep_checkpointed",null)?.let{TaskCheckpointCodec.readOutcomes(org.json.JSONObject(it))}?:outcomes
        val checkpoints=TaskCheckpointCodec.decode(prefs.getString("sweep_checkpoints",null)?.let{org.json.JSONObject(it)})?:TaskCheckpointSummary()
        WaveformSweep(token,prefs.getString("sweep_inventory",null) ?: return null,
            prefs.getInt("sweep_total",-1),prefs.getLong("sweep_started",-1),
            TaskTrigger.valueOf(prefs.getString("sweep_trigger",null) ?: return null),outcomes,
            TaskFailure.entries.firstOrNull{it.name==prefs.getString("sweep_failure",null)},checkpointed,checkpoints)
    }.getOrNull()
    fun plan():Set<String>?=prefs.getStringSet("sweep_plan_v2",null)?.toSet()
    fun receipts():Set<String> = prefs.getStringSet("confirmed_cache_v2",emptySet()).orEmpty().toSet()
    fun saveReceipts(receipts:Set<String>):Boolean=prefs.edit().putStringSet("confirmed_cache_v2",receipts.toSet()).commit()
    fun save(s:WaveformSweep,plan:Set<String>,receipts:Set<String>):Boolean = prefs.edit()
        .putStringSet("sweep_plan_v2",plan.toSet()).putStringSet("confirmed_cache_v2",receipts.toSet())
        .putString("sweep_token",s.token.value).putString("sweep_inventory",s.inventory)
        .putInt("sweep_total",s.total).putLong("sweep_started",s.startedAt)
        .putString("sweep_trigger",s.trigger.name).putInt("sweep_cached",s.outcomes.cached)
        .putInt("sweep_generated",s.outcomes.generated).putInt("sweep_skipped",s.outcomes.skipped)
        .putInt("sweep_failed",s.outcomes.failed).putString("sweep_failure",s.failure?.name)
        .putString("sweep_checkpointed",TaskCheckpointCodec.outcomes(s.checkpointed).toString())
        .putString("sweep_checkpoints",TaskCheckpointCodec.encode(s.checkpoints).toString()).commit()
    fun clear():Boolean = prefs.edit().remove("sweep_token").remove("sweep_plan_v2").commit()
}
