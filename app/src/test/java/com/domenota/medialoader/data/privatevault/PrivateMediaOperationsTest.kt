package com.domenota.medialoader.data.privatevault

import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PrivateMediaOperationsTest {
    private fun row() = DownloadEntity("id", "test", "video.mp4", MediaType.VIDEO,
        DownloadState.COMPLETED, "content://public/1", 4, 1)

    @Test fun failedPublicRemovalRetainsVerifiedCopyAndRetryState() = runBlocking {
        var saved = row()
        var allowDelete = false
        val deleted = mutableListOf<String>()
        val ops = PrivateMediaOperations({}, { saved }, { saved = it }, {},
            { _, _ -> "file:///private/id.mp4" }, { deleted += it; true },
            { allowDelete }, { error("unused") })
        assertTrue(runCatching { ops.move("id") }.isFailure)
        assertTrue(saved.isPrivate)
        assertEquals("content://public/1", saved.privateSourceUri)
        assertTrue(deleted.isEmpty())
        allowDelete = true
        ops.finishMove("id")
        assertNull(saved.privateSourceUri)
    }

    @Test fun databaseFailureKeepsPublicSourceAndCleansCopy() = runBlocking {
        val deleted = mutableListOf<String>()
        var publicDeleted = false
        val ops = PrivateMediaOperations({}, { row() }, { error("database") }, {},
            { _, _ -> "file:///private/id.mp4" }, { deleted += it; true },
            { publicDeleted = true; true }, { error("unused") })
        assertTrue(runCatching { ops.move("id") }.isFailure)
        assertFalse(publicDeleted)
        assertEquals(listOf("file:///private/id.mp4"), deleted)
    }

    @Test fun restoreDatabaseFailureDeletesNewPublicCopyAndKeepsPrivate() = runBlocking {
        val privateRow = row().copy(isPrivate = true, savedUri = "file:///private/id.mp4")
        var privateDeleted = false
        var publicDeleted = false
        val ops = PrivateMediaOperations({}, { privateRow }, { error("database") }, {},
            { _, _ -> error("unused") }, { privateDeleted = true; true },
            { publicDeleted = true; true }, { "restored.mp4" to "content://restored/2" })
        assertTrue(runCatching { ops.restore("id") }.isFailure)
        assertFalse(privateDeleted)
        assertTrue(publicDeleted)
    }

    @Test fun configuredLockedAppendSucceedsWithoutUnlockingAndPreservesGroup() = runBlocking {
        var locked = true
        var saved = row().copy(groupId = "original-group")
        val events = mutableListOf<String>()
        val ops = PrivateMediaOperations({ check(!locked) { "locked" } }, { saved },
            { events += "save"; saved = it }, {},
            { _, _ -> events += "copy"; "file:///private/id.mp4" }, { error("unexpected deletion") },
            { events += "remove public"; true }, { error("unexpected publish") },
            requireConfigured = {})
        ops.move("id")
        assertTrue(locked)
        assertTrue(saved.isPrivate)
        assertNull(saved.privateSourceUri)
        assertEquals("original-group", saved.groupId)
        assertEquals(listOf("copy", "save", "remove public", "save"), events)
    }

    @Test fun lockingDuringImportStillFinishesPublicCleanup() = runBlocking {
        var locked = false
        var saved = row()
        val ops = PrivateMediaOperations({ check(!locked) { "locked" } }, { saved }, { saved = it }, {},
            { _, _ -> locked = true; "file:///private/id.mp4" }, { true }, { true }, { error("unused") },
            requireConfigured = {})
        ops.move("id")
        assertTrue(locked)
        assertTrue(saved.isPrivate)
        assertNull(saved.privateSourceUri)
    }

    @Test fun configuredLockedPrivateOperationsRemainDenied() = runBlocking {
        var changes = 0
        val saved = row().copy(isPrivate = true, savedUri = "file:///private/id.mp4", privateSourceUri = "content://public/1")
        val ops = PrivateMediaOperations({ error("locked") }, { saved }, { changes++ }, { changes++ },
            { _, _ -> changes++; "unused" }, { changes++; true }, { changes++; true }, { changes++; "x" to "y" },
            requireConfigured = {})
        assertEquals("locked", runCatching { ops.move("id") }.exceptionOrNull()?.message)
        assertEquals("locked", runCatching { ops.finishMove("id") }.exceptionOrNull()?.message)
        assertEquals("locked", runCatching { ops.restore("id") }.exceptionOrNull()?.message)
        assertEquals("locked", runCatching { ops.delete("id") }.exceptionOrNull()?.message)
        assertEquals(0, changes)
    }

    @Test fun unconfiguredAppendIsDeniedBeforeReading() = runBlocking {
        val ops = PrivateMediaOperations({}, { error("read") }, { error("save") }, {},
            { _, _ -> error("copy") }, { error("delete") }, { error("delete public") }, { error("publish") },
            requireConfigured = { error("unconfigured") })
        assertEquals("unconfigured", runCatching { ops.move("id") }.exceptionOrNull()?.message)
    }

    @Test fun lockedOperationsNeverReadOrChangeFiles() = runBlocking {
        val ops = PrivateMediaOperations({ error("locked") }, { error("read") }, { error("save") }, {},
            { _, _ -> error("copy") }, { error("delete") }, { error("delete public") }, { error("publish") })
        assertEquals("locked", runCatching { ops.move("id") }.exceptionOrNull()?.message)
        assertEquals("locked", runCatching { ops.restore("id") }.exceptionOrNull()?.message)
        assertEquals("locked", runCatching { ops.delete("id") }.exceptionOrNull()?.message)
    }
}
