package com.domenota.medialoader.data.privatevault

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Test

class PrivateMoveSessionTest {
    @Test fun consecutiveGroupsCompleteAndOldResultCannotConsumeNewResult() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val moved = mutableListOf<String>()
            val session = PrivateMoveSession(owner) { moved += it }
            assertTrue(session.start(listOf("a", "b")))
            val firstRevision = session.state.value.revision
            assertFalse(session.state.value.busy)
            session.consume(firstRevision)
            assertTrue(session.start(listOf("c", "d")))
            assertFalse(session.state.value.busy)
            assertEquals(listOf("a", "b", "c", "d"), moved)
            assertEquals(firstRevision + 1, session.state.value.revision)
            session.consume(firstRevision)
            assertEquals(2, session.state.value.result?.succeeded)
        } finally { owner.cancel() }
    }

    @Test fun failedFileDoesNotLeaveNextGroupBlocked() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val moved = mutableListOf<String>()
            val session = PrivateMoveSession(owner) { if (it == "bad") error("Unreadable file") else moved += it }
            assertTrue(session.start(listOf("bad")))
            assertFalse(session.state.value.busy)
            assertEquals(1, session.state.value.result?.failed)
            assertTrue(session.start(listOf("good")))
            assertEquals(listOf("good"), moved)
            assertEquals(1, session.state.value.result?.succeeded)
        } finally { owner.cancel() }
    }

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
