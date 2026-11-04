package com.domenota.medialoader.ui.screens

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VideoEditorUpdateSamplingTest {
    @Test fun continuousDragProducesIntermediateFramesAndRetainsLatestValue() = runTest {
        val values = MutableStateFlow(0)
        val seen = mutableListOf<Pair<Long, Int>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            values.sampleVideoEditorUpdates().collect { seen += currentTime to it }
        }
        repeat(20) { index -> advanceTimeBy(10); values.value = index + 1; runCurrent() }
        assertTrue("Dragging must update before the gesture ends", seen.any { it.first < 150 && it.second > 0 })
        advanceTimeBy(51); runCurrent()
        assertEquals(20, seen.last().second)
        assertTrue("Avoid one GPU update per pointer event", seen.size <= 6)
    }
}
