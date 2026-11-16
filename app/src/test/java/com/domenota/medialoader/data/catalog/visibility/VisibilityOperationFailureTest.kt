package com.domenota.medialoader.data.catalog.visibility

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class VisibilityOperationFailureTest {
    @Test fun wrappedFailureExposesFixedStageAndCodeWithoutPrivateMessageOrCause() {
        val secret = "file:///synthetic/private-name.jpg"
        val original = IllegalStateException(secret, SecurityException("denied $secret"))
        val failure = VisibilityOperationFailure.wrap(VisibilityOperationStage.COPY, original)
        assertEquals(VisibilityOperationStage.COPY, failure.info.stage)
        assertEquals("FV_COPY_ACCESS", failure.info.code)
        assertFalse(failure.toString().contains(secret))
        assertFalse(failure.info.toString().contains(secret))
        assertNull(failure.cause)
        assertArrayEquals(original.stackTrace, failure.stackTrace)
        assertSame(failure, VisibilityOperationFailure.wrap(VisibilityOperationStage.JOURNAL, failure))
    }

    @Test fun cancellationRetainsIdentityAndIsNeverConvertedToFailure() {
        val cancelled = CancellationException("synthetic cancellation")
        try { VisibilityOperationFailure.wrap(VisibilityOperationStage.CLEANUP, cancelled); fail("Cancellation must propagate") }
        catch (actual: CancellationException) { assertSame(cancelled, actual) }
    }

    @Test fun reportingPortsIdentifyCopyBoundaryWithoutExposingExceptionMessage() = runBlocking {
        val record = VisibilityMoveRecord("op", "folder", "member", VisibilityDirection.HIDE,
            "source", "synthetic.jpg", null, VisibilityFingerprint(12L, "a".repeat(64)), "token")
        val files = object : VisibilityMovePorts {
            override suspend fun currentUri(record: VisibilityMoveRecord) = "source"
            override suspend fun fingerprint(uri: String) = record.fingerprint
            override suspend fun presence(uri: String) = VisibilityPresence.PRESENT
            override suspend fun allocate(record: VisibilityMoveRecord) = "destination"
            override suspend fun copyAndSync(record: VisibilityMoveRecord): Unit = throw java.io.IOException("file:///synthetic/private-name.jpg")
            override suspend fun verifyDestination(record: VisibilityMoveRecord) = Unit
            override suspend fun rebind(record: VisibilityMoveRecord) = Unit
            override suspend fun verifyBinding(record: VisibilityMoveRecord) = Unit
            override suspend fun removeSource(record: VisibilityMoveRecord) = Unit
            override suspend fun verifyFinalVisibility(record: VisibilityMoveRecord) = Unit
        }
        val batches = object : VisibilityBatchPorts {
            override suspend fun preflight(batch: VisibilityBatchRecord) = Unit
            override suspend fun completed(member: VisibilityMoveRecord) = false
            override suspend fun commitVisibility(batch: VisibilityBatchRecord) = Unit
        }
        val stages = mutableListOf<VisibilityOperationStage>()
        val ports = ReportingVisibilityPorts(files, batches, stages::add)
        try { ports.copyAndSync(record); fail("Expected copy failure") }
        catch (failure: VisibilityOperationFailure) {
            assertEquals("FV_COPY_IO", failure.info.code)
            assertFalse(failure.toString().contains("private-name"))
        }
        assertEquals(listOf(VisibilityOperationStage.COPY), stages)
    }
}
