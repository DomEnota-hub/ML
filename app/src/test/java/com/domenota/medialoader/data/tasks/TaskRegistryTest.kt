package com.domenota.medialoader.data.tasks

import org.junit.Assert.*
import org.junit.Test

class TaskRegistryTest {
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
}
