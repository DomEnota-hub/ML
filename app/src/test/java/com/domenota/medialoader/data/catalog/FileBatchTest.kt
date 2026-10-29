package com.domenota.medialoader.data.catalog

import kotlinx.coroutines.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FileBatchTest {
    @Test fun wholeGroupIncludingHiddenChildrenIsProcessedAndFailuresDoNotAbortOthers() = runBlocking {
        val processed = mutableListOf<String>()
        val result = runFileBatch((1..30).map(Int::toString) + "1") { id ->
            processed += id
            if (id == "2") error("denied")
        }
        assertEquals(30, processed.size)
        assertTrue("30" in processed)
        assertEquals(29, result.succeeded)
        assertEquals(1, result.failed)
    }
    @Test fun cancellationStopsTheBatchWithoutReportingFalseSuccess() = runBlocking<Unit> {
        val processed = mutableListOf<String>()
        try {
            runFileBatch(listOf("a", "b", "c")) { id -> processed += id; if (id == "b") throw CancellationException() }
            fail("Expected cancellation")
        } catch (_: CancellationException) { assertEquals(listOf("a", "b"), processed) }
    }
    @Test fun cancellationDuringFinalNonCancellableCommitNeverReportsSuccess() = runBlocking {
        var reported = false
        var committed = false
        val task = launch(Dispatchers.Unconfined) {
            val parent = currentCoroutineContext()[Job]!!
            try {
                runFileBatch(listOf("last")) {
                    withContext(NonCancellable) { committed = true; parent.cancel() }
                }
                reported = true
            } catch (_: CancellationException) { }
        }
        task.join()
        assertTrue(committed)
        assertFalse(reported)
    }

}
