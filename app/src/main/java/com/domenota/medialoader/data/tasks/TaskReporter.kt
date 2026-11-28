package com.domenota.medialoader.data.tasks

import java.util.UUID

/** Observes executor events; never starts, retries or schedules work itself. */
internal class TaskReporter private constructor(private val owner: TaskOwner, private val kind: TaskKind, private val id: String) {
    val taskId:String get()=id
    private var last: TaskSnapshot? = null
    private var operation: TaskDetails? = null
    @Synchronized fun report(status: TaskStatus = TaskStatus.RUNNING, files: TaskProgress? = null,
        percent: Int? = null, errors: Int = 0, wait: TaskWait? = null, stage: TaskStage? = null,
        commands:Set<TaskCommand> = emptySet(), idle:Boolean=false, details:TaskDetails?=null, onCommand:((TaskCommand)->Unit)?=null) {
        if (idle) operation=null
        else if (operation==null || (last?.status?.terminal==true && !status.terminal)) {
            operation=TaskDetails(TaskCorrelation.create(),System.currentTimeMillis())
        }
        val base=operation
        val metadata=if(idle)null else details?.copy(correlation=details.correlation?:base?.correlation,startedAt=details.startedAt?:base?.startedAt)?:base
        operation=metadata
        val row = TaskSnapshot(id, kind, status, files = files, errors = errors, wait = wait,
            percent = percent, stage = stage, commands=commands, idle=idle, details=metadata)
        if (row == last) return
        last = row
        TaskCenter.registry.record(owner, row.copy(updatedAt = System.currentTimeMillis()),onCommand)
    }
    @Synchronized fun remove() { TaskCenter.registry.remove(owner, id); last = null; operation=null }
    companion object {
        fun create(owner: TaskOwner, kind: TaskKind, key: String = UUID.randomUUID().toString()) =
            TaskReporter(owner, kind, taskId(owner, key))
    }
}
internal fun countProgress(done: Int, total: Int): TaskProgress =
    TaskProgress(done.coerceAtLeast(0).toLong(), total.takeIf { it > 0 && it >= done }?.toLong())
