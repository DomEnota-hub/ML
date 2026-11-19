package com.domenota.medialoader.data.catalog.visibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.7.206 gate 1: an obsolete copy/delete transfer must not be callable. */
class RetiredFolderVisibilityTransferTest {
    @Test fun physicalFolderTransportIsRetired() {
        assertFalse(RetiredFolderVisibilityTransfer.ACTIVE)
    }

    @Test fun attemptsFailBeforeAnyFolderOrJournalMutation() {
        val error = runCatching { RetiredFolderVisibilityTransfer.requireActive() }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertEquals(RetiredFolderVisibilityTransfer.MESSAGE, error?.message)
    }
}
