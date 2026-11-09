package com.domenota.medialoader.data.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class MaskActionGroupingTest {
    @Test fun oneAutomaticSelectionUndoesAndRedoesAsSingleAction() {
        val history = MaskActionGrouping()
        history.record(854)
        assertEquals(854, history.undoCount())
        assertEquals(854, history.redoCount())
    }

    @Test fun manualCorrectionIsSeparateFromEarlierAutomaticMask() {
        val history = MaskActionGrouping()
        history.record(523)
        history.record(1)
        history.record(1)
        assertEquals(1, history.undoCount())
        assertEquals(1, history.undoCount())
        assertEquals(523, history.undoCount())
        assertEquals(523, history.redoCount())
        assertEquals(1, history.redoCount())
        assertEquals(1, history.redoCount())
    }

    @Test fun newActionAfterUndoInvalidatesRedoBranch() {
        val history = MaskActionGrouping()
        history.record(500)
        history.undoCount()
        history.record(1)
        assertEquals(0, history.redoCount())
    }

    @Test fun emptyHistoryCannotInventUndoOrRedo() {
        val history = MaskActionGrouping()
        assertEquals(0, history.undoCount())
        assertEquals(0, history.redoCount())
        history.record(321)
        assertEquals(321, history.undoCount())
        assertEquals(0, history.undoCount())
        assertEquals(321, history.redoCount())
        assertEquals(0, history.redoCount())
        history.clear()
        assertEquals(0, history.undoCount())
        assertEquals(0, history.redoCount())
    }

    @Test fun newImageOrModeStartsFreshHistory() {
        val history = MaskActionGrouping()
        history.record(200)
        history.clear()
        history.record(1)
        assertEquals(1, history.undoCount())
    }
}
