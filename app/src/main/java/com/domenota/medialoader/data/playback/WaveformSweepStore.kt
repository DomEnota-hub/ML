package com.domenota.medialoader.data.playback

import android.content.SharedPreferences
import com.domenota.medialoader.data.tasks.*

internal class WaveformSweepStore(private val prefs: SharedPreferences) {
    fun read():WaveformSweep? = runCatching {
        val token=TaskCorrelation.parse(prefs.getString("sweep_token",null)) ?: return null
        WaveformSweep(token,prefs.getString("sweep_inventory",null) ?: return null,
            prefs.getInt("sweep_total",-1),prefs.getLong("sweep_started",-1),
            TaskTrigger.valueOf(prefs.getString("sweep_trigger",null) ?: return null),
            TaskOutcomes(prefs.getInt("sweep_cached",0),prefs.getInt("sweep_generated",0),
                prefs.getInt("sweep_skipped",0),prefs.getInt("sweep_failed",0)),
            TaskFailure.entries.firstOrNull{it.name==prefs.getString("sweep_failure",null)})
    }.getOrNull()
    fun save(s:WaveformSweep):Boolean = prefs.edit()
        .putString("sweep_token",s.token.value).putString("sweep_inventory",s.inventory)
        .putInt("sweep_total",s.total).putLong("sweep_started",s.startedAt)
        .putString("sweep_trigger",s.trigger.name).putInt("sweep_cached",s.outcomes.cached)
        .putInt("sweep_generated",s.outcomes.generated).putInt("sweep_skipped",s.outcomes.skipped)
        .putInt("sweep_failed",s.outcomes.failed).putString("sweep_failure",s.failure?.name).commit()
    fun clear():Boolean = prefs.edit().remove("sweep_token").commit()
}
