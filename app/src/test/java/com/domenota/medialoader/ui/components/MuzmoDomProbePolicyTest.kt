package com.domenota.medialoader.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MuzmoDomProbePolicyTest {
    @Test fun neverFailsBeforeDocumentSettles() {
        assertFalse(MuzmoDomProbePolicy.exhausted("loading", 100, true))
        assertFalse(MuzmoDomProbePolicy.exhausted("interactive", 100, false))
    }

    @Test fun requiresRepeatedEmptySettledProbes() {
        assertFalse(MuzmoDomProbePolicy.exhausted("complete", 11, false))
        assertTrue(MuzmoDomProbePolicy.exhausted("complete", 12, false))
    }

    @Test fun malformedCandidateRowsAreNotReportedAsNoMatch() {
        assertFalse(MuzmoDomProbePolicy.exhausted("complete", 2, true))
        assertTrue(MuzmoDomProbePolicy.exhausted("complete", 3, true))
    }
}
