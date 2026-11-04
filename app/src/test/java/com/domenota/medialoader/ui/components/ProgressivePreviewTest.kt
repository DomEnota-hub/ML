package com.domenota.medialoader.ui.components

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProgressivePreviewTest {
    @Test fun lowResolutionRemainsVisibleUntilDetailedImageIsReady() = runTest {
        val visible = mutableListOf<String>()
        val detail = CompletableDeferred<String>()
        val job = launch { progressivePreview(null, { "small" }, { detail.await() }, visible::add) }
        runCurrent(); assertEquals(listOf("small"), visible)
        advanceTimeBy(121); runCurrent(); assertEquals(listOf("small"), visible)
        detail.complete("sharp"); job.join()
        assertEquals(listOf("small", "sharp"), visible)
    }
    @Test fun leavingTheCellCancelsExpensiveRefinement() = runTest {
        var fullLoads = 0
        val job = launch { progressivePreview(null, { "small" }, { fullLoads++; "sharp" }, {}) }
        runCurrent(); job.cancelAndJoin(); advanceUntilIdle()
        assertEquals(0, fullLoads)
    }
    @Test fun detailedCacheHitAvoidsBothDecodeStages() = runTest {
        val shown = mutableListOf<String>()
        progressivePreview("cached", { error("no quick decode") }, { error("no full decode") }, shown::add)
        assertEquals(listOf("cached"), shown)
    }
}
