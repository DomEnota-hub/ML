package com.domenota.medialoader.data.playback

import com.domenota.medialoader.data.tasks.*
import java.security.MessageDigest

internal enum class WaveformOutcome { CACHED, GENERATED, SKIPPED, FAILED }

/** One logical pass; a WorkManager batch is only a checkpoint. */
internal data class WaveformSweep(
    val token: TaskCorrelation,
    val inventory: String,
    val total: Int,
    val startedAt: Long,
    val trigger: TaskTrigger,
    val outcomes: TaskOutcomes = TaskOutcomes()
) {
    init { require(total >= 0 && outcomes.checked <= total && startedAt >= 0) }
    val cursor get() = outcomes.checked
    val finished get() = cursor == total
    fun advance(outcome: WaveformOutcome): WaveformSweep {
        check(!finished)
        val next = when(outcome) {
            WaveformOutcome.CACHED -> outcomes.copy(cached=outcomes.cached+1)
            WaveformOutcome.GENERATED -> outcomes.copy(generated=outcomes.generated+1)
            WaveformOutcome.SKIPPED -> outcomes.copy(skipped=outcomes.skipped+1)
            WaveformOutcome.FAILED -> outcomes.copy(failed=outcomes.failed+1)
        }
        return copy(outcomes=next)
    }
    fun details()=TaskDetails(token,startedAt,trigger,
        if(outcomes.failed>0) TaskFailure.UNKNOWN else null,outcomes)
    companion object {
        // Only used for local checkpoint validation, not emitted in logs/history.
        fun fingerprint(keys:List<String>):String {
            val d=MessageDigest.getInstance("SHA-256")
            keys.forEach { key -> val bytes=key.toByteArray(Charsets.UTF_8)
                d.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array());d.update(bytes) }
            return d.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
