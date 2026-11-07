package com.domenota.medialoader.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicSpeedActionTest {
    @Test fun compactSpeedMenuHasNormalSpeedAndDeterministicOptions() {
        assertEquals(listOf(.75f, 1f, 1.25f, 1.5f, 2f), musicSpeedChoices)
        assertEquals(musicSpeedChoices.size, musicSpeedChoices.distinct().size)
        assertTrue(musicSpeedChoices.zipWithNext().all { (before, after) -> before < after })
    }
}
