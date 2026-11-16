package com.domenota.medialoader.data.library

import com.domenota.medialoader.data.storage.SavedFileStatus
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CollectionLookupBudgetTest {
    @Test fun nonCooperativeProviderDoesNotKeepCallerWaiting() = runBlocking {
        val release=CountDownLatch(1)
        try {
            val result=withTimeout(2_000) { CollectionLookupBudget.run(75) { release.await(10,TimeUnit.SECONDS); "late" } }
            assertNull(result)
            assertEquals("other",withTimeout(2_000) { CollectionLookupBudget.run(500) { "other" } })
        } finally { release.countDown() }
    }
    @Test fun expiredBudgetIsUncheckedAndDoesNotInvokeAnotherProvider() = runBlocking {
        var now=0L
        val deadline=CollectionLookupDeadline(totalMs=100,perAddressMs=100,clock={now})
        val first=deadline.lookup { AddressedFile(null,SavedFileStatus.PRESENT) }
        assertEquals(SavedFileStatus.PRESENT,first.status)
        now=101L
        var called=false
        val later=deadline.lookup { called=true; AddressedFile(null,SavedFileStatus.MISSING) }
        assertEquals(SavedFileStatus.UNCHECKED,later.status)
        assertFalse(called)
    }
    @Test fun providerFailureIsNotReportedAsDeletion() = runBlocking {
        assertEquals(SavedFileStatus.UNCHECKED,CollectionLookupDeadline().lookup { error("provider down") }.status)
    }
}
