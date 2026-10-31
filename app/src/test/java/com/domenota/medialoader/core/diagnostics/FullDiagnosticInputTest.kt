package com.domenota.medialoader.core.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FullDiagnosticInputTest {
    @Test fun classifiesOnlySupportedControlLinks() {
        val input = FullDiagnosticInput.parse("""
            https://www.instagram.com/reel/example/
            https://youtu.be/example
            https://vk.com/video1_2
            https://rutube.ru/video/example/
            https://malicious.example/watch?v=1
        """.trimIndent(), "DJ", "Song")
        assertEquals(4, input.links.size)
        assertEquals("https://youtu.be/example", input.linkFor(DiagnosticSource.YOUTUBE))
        assertNull(input.linkFor(DiagnosticSource.PRIMARY_MUSIC))
        assertEquals("DJ", input.artist)
        assertEquals("Song", input.title)
    }

    @Test fun musicErrorsNeverSuggestAccountSessions() {
        val music = SourceWorkflowRules.musicUnavailable()
        assertEquals(DiagnosticState.UNAVAILABLE, music.state)
        assertEquals(DiagnosticEvidence.NONE, music.evidence)
        assertEquals("MUSIC_SEARCH_UNAVAILABLE", music.reasonCode)
        assertTrue(!music.explanation.contains("сесси", ignoreCase = true))
        assertTrue(!music.explanation.contains("авторизац", ignoreCase = true))
        val http = SourceDiagnosticRules.http(403, requiresAccountCheck = false)
        assertTrue(!http.explanation.contains("сесси", ignoreCase = true))
        assertTrue(http.explanation.contains("403"))
    }

    @Test fun missingControlDataProducesUncheckedOutcome() {
        val outcome = SourceWorkflowRules.missingInput(DiagnosticSource.INSTAGRAM)
        assertEquals(DiagnosticState.UNCHECKED, outcome.state)
        assertEquals(DiagnosticEvidence.NONE, outcome.evidence)
        assertTrue(outcome.explanation.contains("контрольная ссылка"))
    }

    @Test fun extractedItemsProveOnlyMetadataStage() {
        val found = SourceWorkflowRules.extracted(3)
        assertEquals(DiagnosticState.OK, found.state)
        assertEquals("WORKFLOW_METADATA_OK", found.reasonCode)
        assertTrue(found.explanation.contains("метаданные 3 объектов"))
    }
}
