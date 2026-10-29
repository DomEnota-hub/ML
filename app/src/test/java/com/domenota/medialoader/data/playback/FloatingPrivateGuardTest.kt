package com.domenota.medialoader.data.playback

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

class FloatingPrivateGuardTest {
    @Test fun movingAnAlreadyPlayingVideoClosesTheOverlay() = runBlocking {
        val ids = MutableStateFlow(emptySet<String>())
        val events = mutableListOf<String>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            guardFloatingPlayback("video", false, ids, { events += "open" }, { events += "close" })
        }
        assertEquals(listOf("open"), events)
        ids.value = setOf("video")
        yield()
        job.join()
        assertEquals(listOf("open", "close"), events)
    }

    @Test fun privateVideoNeverOpensEvenBeforeTheDatabaseEmits() = runBlocking {
        val events = mutableListOf<String>()
        guardFloatingPlayback("video", true, MutableSharedFlow(), { events += "open" }, { events += "close" })
        assertEquals(listOf("close"), events)
        guardFloatingPlayback("video", false, MutableStateFlow(setOf("video")), { events += "open" }, { events += "close" })
        assertEquals(listOf("close", "close"), events)
    }

    @Test fun overlayWaitsForInitialDatabaseCheckAndOpensOnlyOnce() = runBlocking {
        val ids = MutableSharedFlow<Set<String>>()
        val events = mutableListOf<String>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            guardFloatingPlayback("video", false, ids, { events += "open" }, { events += "close" })
        }
        assertEquals(emptyList<String>(), events)
        ids.emit(emptySet()); yield()
        ids.emit(setOf("other")); yield()
        assertEquals(listOf("open"), events)
        job.cancelAndJoin()
    }
}
