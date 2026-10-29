package com.domenota.medialoader.data.privatevault

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Test

class PrivateMoveSessionTest {
    @Test fun transferSurvivesUiObserverRecreationAndIsNotStartedTwice() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val release = CompletableDeferred<Unit>()
            val moved = mutableListOf<String>()
            val session = PrivateMoveSession(owner) { id -> release.await(); moved += id }
            assertTrue(session.start(listOf("a", "b")))
            val ui = launch(start = CoroutineStart.UNDISPATCHED) { session.state.collect {} }
            ui.cancelAndJoin()
            assertTrue(session.state.value.busy)
            assertFalse(session.start(listOf("a", "b")))
            release.complete(Unit)
            yield()
            assertEquals(listOf("a", "b"), moved)
            assertFalse(session.state.value.busy)
            assertEquals(2, session.state.value.result?.succeeded)
            val revision = session.state.value.revision
            session.consume(revision)
            assertNull(session.state.value.result)
        } finally { owner.cancel() }
    }
}
