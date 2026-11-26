package com.domenota.medialoader.data.tasks

import java.util.UUID

/** Observes executor events; never starts, retries or schedules work itself. */
internal class TaskReporter private constructor(private val owner: TaskOwner, private val kind: TaskKind, private val id: String) {
    private var last: TaskSnapshot? = null
    @Synchronized fun report(status: TaskStatus = TaskStatus.RUNNING, files: TaskProgress? = null,
        percent: Int? = null, errors: Int = 0, wait: TaskWait? = null, stage: TaskStage? = null,
        commands:Set<TaskCommand> = emptySet(), onCommand:((TaskCommand)->Unit)?=null) {
        val row = TaskSnapshot(id, kind, status, files = files, errors = errors, wait = wait,
            percent = percent, stage = stage, commands=commands)
        if (row == last) return
        last = row
        TaskCenter.registry.record(owner, row.copy(updatedAt = System.currentTimeMillis()),onCommand)
    }
    @Synchronized fun remove() { TaskCenter.registry.remove(owner, id); last = null }
    companion object {
        fun create(owner: TaskOwner, kind: TaskKind, key: String = UUID.randomUUID().toString()) =
            TaskReporter(owner, kind, taskId(owner, key))
    }
}
internal fun countProgress(done: Int, total: Int): TaskProgress =
    TaskProgress(done.coerceAtLeast(0).toLong(), total.takeIf { it > 0 && it >= done }?.toLong())
