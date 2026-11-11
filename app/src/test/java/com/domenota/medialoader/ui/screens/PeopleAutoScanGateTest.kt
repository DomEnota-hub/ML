package com.domenota.medialoader.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeopleAutoScanGateTest {
    @Test fun autoScanOnceEvenAfterLaMaUndoRedoOrModeSwitch() {
        val gate = PeopleAutoScanGate()
        assertFalse(gate.claim(false))
        assertTrue(gate.claim(true))
        assertFalse("LaMa output must not be rescanned automatically", gate.claim(true))
        assertFalse(gate.claim(false))
        assertFalse("Re-entering mode must not rescan the same source", gate.claim(true))
        assertTrue("A new photo gets its own scan", PeopleAutoScanGate().claim(true))
    }
}
