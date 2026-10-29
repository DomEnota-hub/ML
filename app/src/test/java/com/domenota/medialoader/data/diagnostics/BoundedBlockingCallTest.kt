package com.domenota.medialoader.data.diagnostics

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedBlockingCallTest {
    @Test fun coroutineDeadlineDoesNotWaitForBlockingNetworkCall() = runBlocking {
        val started = System.nanoTime()
        val result = withTimeoutOrNull(120) {
            BoundedBlockingCall.run {
                Thread.sleep(10_000)
                "late"
            }
        }
        assertEquals(null, result)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 2_000)
    }
}
