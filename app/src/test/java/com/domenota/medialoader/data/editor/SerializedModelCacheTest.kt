package com.domenota.medialoader.data.editor

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SerializedModelCacheTest {
    private class Handle(val id: Int) : AutoCloseable {
        var closed = false
        override fun close() { check(!closed); closed = true }
    }

    @Test fun sequentialAndConcurrentRequestsShareOneHandleUntilKeyChanges() = runBlocking {
        val cache = SerializedModelCache<Handle>(); var opens = 0
        val entered = CompletableDeferred<Handle>(); val finish = CompletableDeferred<Unit>()
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            cache.use({ "a" }, { Handle(++opens) }) { value, reused ->
                assertFalse(reused); entered.complete(value); finish.await(); assertFalse(value.closed); value
            }
        }
        val original = entered.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            cache.use({ "a" }, { Handle(++opens) }) { value, reused ->
                assertTrue(reused); value
            }
        }
        assertEquals(1,opens); assertFalse(second.isCompleted)
        finish.complete(Unit)
        assertSame(original,first.await()); assertSame(original,second.await())
        val changed = cache.use({ "b" }, { Handle(++opens) }) { value, reused ->
            assertFalse(reused); value
        }
        assertTrue(original.closed); assertEquals(2,opens); assertFalse(changed.closed)
        cache.releaseIf { true }; assertTrue(changed.closed)
    }

    @Test fun evictionRechecksEditorGenerationAfterWaitingForInference() = runBlocking {
        val cache = SerializedModelCache<Handle>(); val finish = CompletableDeferred<Unit>()
        var generation = 4L; var editors = 0; val handle = Handle(1)
        val running = launch(start = CoroutineStart.UNDISPATCHED) {
            cache.use({ "a" }, { handle }) { value, _ -> finish.await(); assertFalse(value.closed) }
        }
        val eviction = async(start = CoroutineStart.UNDISPATCHED) {
            cache.releaseIf { LaMaIdlePolicy.mayRelease(editors,generation,4L) }
        }
        editors = 1; generation++
        finish.complete(Unit); running.join()
        assertFalse(eviction.await()); assertFalse(handle.closed)
        cache.releaseIf { true }; assertTrue(handle.closed)
    }

    @Test fun replacementWaitsForRunningInferenceAndPreventsOldHandleReuse() = runBlocking {
        val cache = SerializedModelCache<Handle>(); val finish = CompletableDeferred<Unit>()
        val old = Handle(1); var key = "old"
        val running = launch(start = CoroutineStart.UNDISPATCHED) {
            cache.use({ key }, { old }) { value, _ -> finish.await(); assertFalse(value.closed) }
        }
        val replacement = async(start = CoroutineStart.UNDISPATCHED) {
            cache.mutate { assertTrue(old.closed); key = "new" }
        }
        assertEquals("old", key); assertFalse(old.closed)
        finish.complete(Unit); running.join(); replacement.await()
        val new = cache.use({ key }, { Handle(2) }) { value, reused -> assertFalse(reused); value }
        assertEquals(2,new.id); cache.releaseIf { true }; assertTrue(new.closed)
    }

    @Test fun cancelledWaiterNeverOpensAndFailedInitializationCanRetry() = runBlocking {
        val cache = SerializedModelCache<Handle>(); var opens = 0
        val finish = CompletableDeferred<Unit>()
        val first = launch(start = CoroutineStart.UNDISPATCHED) {
            cache.use({ "a" }, { Handle(++opens) }) { _, _ -> finish.await() }
        }
        val cancelled = launch(start = CoroutineStart.UNDISPATCHED) {
            cache.use({ "b" }, { Handle(++opens) }) { _, _ -> error("cancelled waiter ran") }
        }
        cancelled.cancelAndJoin(); finish.complete(Unit); first.join()
        assertEquals(1,opens)
        try {
            cache.use<Unit>({ "bad" }, { throw IllegalStateException("failed init") }) { _, _ -> error("unexpected") }
            fail("expected initialization failure")
        } catch (expected: IllegalStateException) { assertEquals("failed init", expected.message) }
        val next = cache.use({ "bad" }, { Handle(++opens) }) { value, reused -> assertFalse(reused); value }
        assertEquals(2,next.id); cache.releaseIf { true }; assertTrue(next.closed)
    }

    @Test fun idleGraceIsBoundedAndMemoryPressureWins() {
        val mib = 1024L*1024
        assertEquals(20_000L,LaMaIdlePolicy.graceMillis(false,1024*mib,256*mib))
        assertEquals(0L,LaMaIdlePolicy.graceMillis(true,1024*mib,256*mib))
        assertEquals(0L,LaMaIdlePolicy.graceMillis(false,400*mib,100*mib))
        assertEquals(0L,LaMaIdlePolicy.graceMillis(false,600*mib,400*mib))
        assertTrue(LaMaIdlePolicy.mayRelease(0,7,7))
        assertFalse(LaMaIdlePolicy.mayRelease(0,8,7))
        assertFalse(LaMaIdlePolicy.mayRelease(1,7,7))
    }
}
