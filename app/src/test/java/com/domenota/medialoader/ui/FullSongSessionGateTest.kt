package com.domenota.medialoader.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FullSongSessionGateTest {
    @Test fun recognitionMaySetLaunchFieldsOnlyOnce() {
        val gate = FullSongSessionGate()
        val id = gate.open()
        assertTrue(gate.acceptRecognition(id))
        assertFalse(gate.acceptRecognition(id))
    }

    @Test fun resultsFromClosedSessionAreDiscarded() {
        val gate = FullSongSessionGate()
        val first = gate.open()
        gate.close()
        assertFalse(gate.acceptRecognition(first))
        val next = gate.open()
        assertNotEquals(first, next)
        assertFalse(gate.acceptRecognition(first))
        assertTrue(gate.acceptRecognition(next))
    }

    @Test fun cancellationBeforeRecognitionDoesNotUnlockOldWindow() {
        val gate = FullSongSessionGate()
        val token = gate.open()
        gate.close()
        assertFalse(gate.acceptRecognition(token))
        assertFalse(gate.acceptRecognition(token))
    }
}
