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
    val outcomes: TaskOutcomes = TaskOutcomes(),
    val failure:TaskFailure?=null,
    val checkpointed:TaskOutcomes=TaskOutcomes(),
    val checkpoints:TaskCheckpointSummary=TaskCheckpointSummary()
) {
    init { require(total >= 0 && outcomes.checked <= total && startedAt >= 0);outcomes.since(checkpointed) }
    val cursor get() = outcomes.checked
    val finished get() = cursor == total
    fun advance(outcome: WaveformOutcome,reason:TaskFailure?=null): WaveformSweep {
        check(!finished)
        val next = when(outcome) {
            WaveformOutcome.CACHED -> outcomes.copy(cached=outcomes.cached+1)
            WaveformOutcome.GENERATED -> outcomes.copy(generated=outcomes.generated+1)
            WaveformOutcome.SKIPPED -> outcomes.copy(skipped=outcomes.skipped+1)
            WaveformOutcome.FAILED -> outcomes.copy(failed=outcomes.failed+1)
        }
        val nextFailure=if(outcome!=WaveformOutcome.FAILED)failure else {
            val known=reason?:TaskFailure.UNKNOWN
            if(outcomes.failed==0)known else if(failure==known)known else TaskFailure.UNKNOWN
        }
        return copy(outcomes=next,failure=nextFailure)
    }
    fun finishCheckpoint(now:Long):WaveformSweep {
        val delta=outcomes.since(checkpointed)
        if(delta.checked==0)return this
        return copy(checkpointed=outcomes,checkpoints=checkpoints.append(delta,now))
    }
    fun details(recovered:Boolean=false)=TaskDetails(token,startedAt,trigger,
        if(outcomes.failed>0) failure?:TaskFailure.UNKNOWN else null,outcomes,recovered,checkpoints.takeIf{it.count>0})
    companion object {
        // Only used for local checkpoint validation, not emitted in logs/history.
        fun fingerprint(keys:List<String>):String {
            val d=MessageDigest.getInstance("SHA-256")
            keys.forEach { key -> val bytes=key.toByteArray(Charsets.UTF_8)
                d.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array());d.update(bytes) }
            return waveformHex(d.digest())
        }
    }
}

/** Avoid Formatter allocation per byte when fingerprinting a large library. */
internal fun waveformHex(bytes:ByteArray):String=buildString(bytes.size*2) {
    val hex="0123456789abcdef"
    bytes.forEach { byte -> val n=byte.toInt() and 255; append(hex[n ushr 4]);append(hex[n and 15]) }
}
