package com.domenota.medialoader.core.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicHttpFailurePolicyTest {
    @Test fun mainDocument403DuringSearchIsTerminalUserOperationFailure() {
        val decision = MusicHttpFailurePolicy.classify(
            statusCode = 403, isMainFrame = true, searching = true, detailPending = false)
        assertEquals(MusicHttpLogDecision.SEARCH_FAILED, decision)
        assertEquals("Search document HTTP failure", MusicHttpFailurePolicy.eventName(decision))
    }

    @Test fun mainDocument403DuringDetailSelectionFailsThatOperationOnly() {
        assertEquals(MusicHttpLogDecision.DETAIL_FAILED,
            MusicHttpFailurePolicy.classify(403, true, false, true))
        assertEquals(MusicHttpLogDecision.SEARCH_FAILED,
            MusicHttpFailurePolicy.classify(429, true, true, false))
    }

    @Test fun thumbnailsScriptsOrBackground403DoNotTurnWholeSearchRed() {
        for (code in listOf(403, 404, 429, 503)) {
            assertEquals(MusicHttpLogDecision.SUBRESOURCE_DEBUG,
                MusicHttpFailurePolicy.classify(code, false, true, true))
        }
        assertEquals(MusicHttpLogDecision.MAIN_FRAME_WARNING,
            MusicHttpFailurePolicy.classify(403, true, false, false))
        assertEquals(MusicHttpLogDecision.IGNORED,
            MusicHttpFailurePolicy.classify(200, true, true, false))
        assertEquals(MusicHttpLogDecision.IGNORED,
            MusicHttpFailurePolicy.classify(302, true, true, false))
    }

    @Test fun successfulSubsequentSearchDoesNotInheritPriorFailure() {
        val blocked = MusicHttpFailurePolicy.classify(403, true, true, false)
        val successful = MusicHttpFailurePolicy.classify(200, true, true, false)
        assertEquals(MusicHttpLogDecision.SEARCH_FAILED, blocked)
        assertEquals(MusicHttpLogDecision.IGNORED, successful)
        // Every callback is classified from its current state, not old errors.
    }

    @Test fun oldDebugHttp403RemainsDebugAndNewTerminalEventAppearsInErrorFilter() {
        val events = LogDisplay.entries("""
            2026-10-21 15:37:19.001 [DEBUG] [MuzmoWebView] HTTP error · mainFrame=true · method=GET · status=403
            2026-10-21 15:37:19.002 [ERROR] [Muzmo] Search document HTTP failure · op=muzmo-2 · status=403
            2026-10-21 15:37:19.003 [DEBUG] [MuzmoWebView] HTTP error · mainFrame=false · method=GET · status=403
        """.trimIndent())
        assertEquals(listOf(LogTone.DEBUG, LogTone.ERROR, LogTone.DEBUG),
            events.map { it.tone })
        assertEquals(1, LogDisplay.filter(events, LogTone.ERROR).size)
        val readable = LogDisplay.readable(events[1])
        assertTrue(readable.contains("HTTP 403"))
        assertTrue(readable.contains("VPN"))
        assertFalse(readable.contains("muzmo-2"))
    }

    @Test fun anOldWebViewOnlyDebugLineIsNotRetrospectivelyRecolored() {
        val recorded = LogDisplay.entries(
            "2026-10-20 15:00:15.000 [DEBUG] [MuzmoWebView] HTTP error · mainFrame=true · status=403"
        ).single()
        assertEquals(LogTone.DEBUG, recorded.tone)
        assertEquals(0, LogDisplay.filter(listOf(recorded), LogTone.ERROR).size)
    }
}
