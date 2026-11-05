package com.domenota.medialoader.data

import com.domenota.medialoader.data.storage.SavedFileStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class PermanentMediaDeletionTest {
    @Test fun successfulDeletionRemovesBytesBeforeMetadata() = runBlocking {
        val calls = mutableListOf<String>()
        permanentlyDeleteSavedMedia(SavedFileStatus.PRESENT,
            deleteFile = { calls += "bytes"; true }, deleteRecord = { calls += "metadata" })
        assertEquals(listOf("bytes", "metadata"), calls)
    }

    @Test fun providerRefusalPreservesMetadata() = runBlocking {
        var recordDeleted = false
        try {
            permanentlyDeleteSavedMedia(SavedFileStatus.PRESENT,
                deleteFile = { false }, deleteRecord = { recordDeleted = true })
            fail("A provider refusal must fail the operation")
        } catch (_: IOException) { }
        assertEquals(false, recordDeleted)
    }

    @Test fun consentFailurePropagatesWithoutRemovingMetadata() = runBlocking {
        val consentFailure = SecurityException("MediaStore consent required")
        var recordDeleted = false
        try {
            permanentlyDeleteSavedMedia(SavedFileStatus.PRESENT,
                deleteFile = { throw consentFailure }, deleteRecord = { recordDeleted = true })
            fail("The consent error must reach the host")
        } catch (error: SecurityException) {
            assertSame(consentFailure, error)
        }
        assertEquals(false, recordDeleted)
    }

    @Test fun missingFileAfterSystemDeletionOnlyRemovesMetadata() = runBlocking {
        val calls = mutableListOf<String>()
        permanentlyDeleteSavedMedia(SavedFileStatus.MISSING,
            deleteFile = { calls += "bytes"; true }, deleteRecord = { calls += "metadata" })
        assertEquals(listOf("metadata"), calls)
    }

    @Test fun inaccessibleAndUncheckedFilesArePreserved() = runBlocking {
        for (status in listOf(SavedFileStatus.INACCESSIBLE, SavedFileStatus.UNCHECKED)) {
            val calls = mutableListOf<String>()
            try {
                permanentlyDeleteSavedMedia(status,
                    deleteFile = { calls += "bytes"; true }, deleteRecord = { calls += "metadata" })
                fail("Availability must be established first")
            } catch (_: IOException) { }
            assertEquals(emptyList<String>(), calls)
        }
    }

    @Test fun cancellationDoesNotDeleteMetadata() = runBlocking {
        val cancellation = CancellationException("Cancelled")
        var recordDeleted = false
        try {
            permanentlyDeleteSavedMedia(SavedFileStatus.PRESENT,
                deleteFile = { throw cancellation }, deleteRecord = { recordDeleted = true })
            fail("Cancellation must propagate")
        } catch (error: CancellationException) {
            assertSame(cancellation, error)
        }
        assertEquals(false, recordDeleted)
    }
}
