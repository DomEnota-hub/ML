package com.domenota.medialoader.data.tasks

import org.junit.Assert.*
import org.junit.Test

class TaskRegistryTest {
    @Test fun idleProcessIsVisibleAndControllableButNotAWaitingTask(){
        val registry=TaskRegistry()
        val idle=TaskSnapshot(taskId(TaskOwner.OCR,"idle"),TaskKind.OCR,TaskStatus.PAUSED,commands=setOf(TaskCommand.RESUME),idle=true)
        var calls=0
        registry.record(TaskOwner.OCR,idle){calls++}
        assertTrue(registry.tasks.value.isEmpty())
        assertEquals(listOf(idle),registry.liveTasks.value)
        assertTrue(registry.dispatch(idle.id,TaskCommand.RESUME))
        assertEquals(1,calls)
        registry.clearHistory()
        assertEquals(listOf(idle),registry.liveTasks.value)
        assertTrue(registry.history.value.rows.isEmpty())
    }
    @Test fun taskPresentationDoesNotUseTerminalBarsOrRawBytes(){
        val row=TaskSnapshot(taskId(TaskOwner.DOWNLOADS,"done"),TaskKind.DOWNLOAD,TaskStatus.COMPLETE,progress=TaskProgress(1024,1024))
        assertFalse(TaskCenterPresentation.showBar(row))
        assertEquals("1,0 КБ / 1,0 КБ",TaskCenterPresentation.progressText(row))
        assertTrue(TaskCenterPresentation.showBar(row.copy(status=TaskStatus.RUNNING)))
        assertEquals(TaskKind.entries.toSet(),TaskSection.entries.flatMap{it.kinds}.toSet())
        assertEquals(TaskKind.entries.size,TaskSection.entries.sumOf{it.kinds.size})
    }
    private fun row(key: String, status: TaskStatus = TaskStatus.RUNNING, commands: Set<TaskCommand> = emptySet()) =
        TaskSnapshot(taskId(TaskOwner.DOWNLOADS, key), TaskKind.DOWNLOAD, status, commands = commands)
    @Test fun sourceReplacementDoesNotDuplicateTasks() {
        val registry = TaskRegistry()
        val first = row("1")
        registry.publish(TaskOwner.DOWNLOADS, listOf(first))
        registry.publish(TaskOwner.DOWNLOADS, listOf(first.copy(progress = TaskProgress(5, 10))))
        assertEquals(1, registry.tasks.value.size)
        assertEquals(.5f, registry.tasks.value.single().progress!!.fraction!!, 0f)
        registry.clear(TaskOwner.DOWNLOADS)
        assertTrue(registry.tasks.value.isEmpty())
    }
    @Test fun commandsRequireCurrentCapability() {
        val registry = TaskRegistry()
        var calls = 0
        val first = row("1", commands = setOf(TaskCommand.CANCEL))
        registry.publish(TaskOwner.DOWNLOADS, listOf(first)) { _, _ -> calls++ }
        assertFalse(registry.dispatch(first.id, TaskCommand.PAUSE))
        assertTrue(registry.dispatch(first.id, TaskCommand.CANCEL))
        registry.publish(TaskOwner.DOWNLOADS, listOf(first.copy(status = TaskStatus.COMPLETE, commands = emptySet())))
        assertFalse(registry.dispatch(first.id, TaskCommand.CANCEL))
        assertEquals(1, calls)
    }
    @Test fun opaqueIdsAreStableAndOwnerScoped() {
        val raw = "/private/path/image.jpg"
        assertEquals(taskId(TaskOwner.OCR, raw), taskId(TaskOwner.OCR, raw))
        assertNotEquals(taskId(TaskOwner.OCR, raw), taskId(TaskOwner.DOWNLOADS, raw))
        assertFalse(taskId(TaskOwner.OCR, raw).contains(raw))
    }
    @Test fun unknownProgressAndWaitingRemainDistinct() {
        assertNull(TaskProgress(2, null).fraction)
        assertFalse(TaskStatus.WAITING.terminal)
        assertTrue(TaskStatus.PARTIAL.terminal)
        assertTrue(runCatching { TaskProgress(11, 10) }.isFailure)
    }

    @Test fun clearingTaskHistoryKeepsActiveWorkAndDoesNotReimportOldTerminalRows() {
        val registry=TaskRegistry()
        val old=row("old",TaskStatus.COMPLETE).copy(updatedAt=System.currentTimeMillis()-1000)
        val live=row("live")
        registry.publish(TaskOwner.DOWNLOADS,listOf(old,live))
        assertEquals(2,registry.tasks.value.size)
        registry.clearHistory()
        registry.publish(TaskOwner.DOWNLOADS,listOf(old,live))
        assertEquals(listOf(live.id),registry.tasks.value.map{it.id})
        assertTrue(registry.history.value.rows.isEmpty())
    }
    @Test fun historyDoesNotRestoreExecutableCommands() {
        val registry=TaskRegistry()
        val ended=row("ended",TaskStatus.FAILED,setOf(TaskCommand.RETRY)).copy(updatedAt=System.currentTimeMillis())
        registry.publish(TaskOwner.DOWNLOADS,listOf(ended)){_,_->}
        assertTrue(registry.dispatch(ended.id,TaskCommand.RETRY))
        val saved=registry.history.value
        val restored=TaskRegistry()
        restored.restore(saved)
        assertEquals(1,restored.tasks.value.size)
        assertTrue(restored.tasks.value.single().commands.isEmpty())
        assertFalse(restored.dispatch(ended.id,TaskCommand.RETRY))
    }
    @Test fun reporterCommandsAreCheckedAgainstLatestSnapshot() {
        val registry=TaskRegistry()
        val live=row("scan",commands=setOf(TaskCommand.PAUSE))
        var count=0
        registry.record(TaskOwner.AUDIO_SCAN,live){count++}
        assertTrue(registry.dispatch(live.id,TaskCommand.PAUSE))
        registry.record(TaskOwner.AUDIO_SCAN,live.copy(status=TaskStatus.COMPLETE,commands=emptySet()))
        assertFalse(registry.dispatch(live.id,TaskCommand.PAUSE))
        assertEquals(1,count)
    }
}
