package com.domenota.medialoader.data.tasks

import com.domenota.medialoader.core.logging.AppLog

/** Only enums, counts and a random operation token enter the exported journal. */
internal data class TaskJournalEvent(
    val event:TaskEvent, val kind:TaskKind, val token:TaskCorrelation,
    val status:TaskStatus, val stage:TaskStage?, val wait:TaskWait?,
    val trigger:TaskTrigger?, val failure:TaskFailure?, val outcomes:TaskOutcomes?,
    val files:TaskProgress?, val errors:Int, val elapsedMs:Long?,
    val command:TaskCommand?=null, val forwarded:Boolean?=null, val checkpointCount:Int?=null
) {
    fun message():String=buildString {
        append("$event · op=${token.value} · kind=$kind · status=$status")
        stage?.let{append(" · stage=$it")};wait?.let{append(" · wait=$it")}
        trigger?.let{append(" · trigger=$it")};failure?.let{append(" · failure=$it")}
        files?.let{append(" · done=${it.done} · total=${it.total ?: "unknown"}")}
        outcomes?.let{append(" · cached=${it.cached} · generated=${it.generated} · skipped=${it.skipped} · failed=${it.failed}")}
        append(" · errors=$errors");elapsedMs?.let{append(" · elapsedMs=$it")}
        checkpointCount?.let{append(" · checkpoints=$it")}
        command?.let{append(" · command=$it")};forwarded?.let{append(" · forwarded=$it")}
    }
    companion object {
        fun from(event:TaskEvent,row:TaskSnapshot,now:Long,command:TaskCommand?=null,forwarded:Boolean?=null):TaskJournalEvent? {
            val d=row.details ?: return null
            return TaskJournalEvent(event,row.kind,d.correlation ?: return null,row.status,row.stage,row.wait,d.trigger,d.failure,d.outcomes,
                row.files,row.errors,d.startedAt?.let{(now-it).coerceAtLeast(0)},command,forwarded,d.checkpoints?.count)
        }
    }
}
internal object TaskJournal {
    fun write(event:TaskJournalEvent) {
        when(event.event) {
            TaskEvent.FAIL -> AppLog.e("TaskCenter",event.message())
            TaskEvent.PARTIAL -> AppLog.w("TaskCenter",event.message())
            TaskEvent.START,TaskEvent.COMPLETE,TaskEvent.CANCEL -> AppLog.i("TaskCenter",event.message())
            else -> AppLog.verbose("TaskCenter",event.message())
        }
    }
}
