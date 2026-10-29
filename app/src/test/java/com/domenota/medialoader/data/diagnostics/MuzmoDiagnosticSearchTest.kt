package com.domenota.medialoader.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MuzmoDiagnosticSearchTest {
    @Test fun countsOnlyReturnedRowsFromBrowserSearchScript() {
        assertEquals(2, MuzmoDiagnosticSearch.parseCount(
            "\"{\\\"tracks\\\":[{},{}]}\""))
        assertNull(MuzmoDiagnosticSearch.parseCount("null"))
        assertNull(MuzmoDiagnosticSearch.parseCount("\"not-json\""))
    }
}
