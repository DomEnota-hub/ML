package com.domenota.medialoader.core.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogDisplayTest {
    private val sample = """
        2026-10-20 18:00:03.147 [INFO] [MusicSource] History full-song launch · adapter=muzmo
        2026-10-20 18:00:15.904 [INFO] [Muzmo] DOM search rows parsed · op=muzmo-1 · count=8
        2026-10-20 18:00:26.694 [OK] [Muzmo] MP3 stream verified · bytes=5287192
        2026-10-20 18:01:26.775 [INFO] [HitMoz] MP3 stream verified · bytes=5201581
        2026-10-20 18:06:42.511 [DEBUG] [Thumbnail] Fetch · source=default · host=cdn.invalid · status=403
        2026-10-20 18:06:42.611 [DEBUG] [Thumbnail] Fetch · source=default · host=cdn2.invalid · status=403
        2026-10-20 18:06:42.711 [WARN] [Thumbnail] Image host=cdn2.invalid · Thumbnail HTTP 403
        2026-10-20 18:06:42.811 [WARN] [Thumbnail] Image host=cdn3.invalid · Thumbnail HTTP 403
        2026-10-20 18:07:42.111 [ERROR] [Download] Failed · id=1
            java.io.IOException: test failure
    """.trimIndent()

    @Test fun parsesRealisticLegacyLogAndGroupsCorrectly() {
        val entries = LogDisplay.entries(sample)
        assertEquals(9, entries.size)
        assertEquals(LogTone.INFO, entries[0].tone)
        assertEquals(LogTone.OK, entries[2].tone)
        assertEquals(LogTone.OK, entries[3].tone) // historical INFO success
        assertEquals(LogTone.ERROR, entries[8].tone)
        assertEquals(1, entries[8].stack.size)
        val compact = LogDisplay.compact(entries)
        assertEquals(7, compact.size)
        assertEquals(2, compact[4].repeats)
        assertEquals(2, compact[5].repeats)
    }

    @Test fun separatedThumbnailEventsMustNeverCollapseAcrossOtherEvents() {
        val input = LogDisplay.entries("""
            2026-10-20 18:06:42.511 [DEBUG] [Thumbnail] Fetch · host=cdn1.invalid · status=403
            2026-10-20 18:06:42.599 [INFO] [Download] Started · id=1
            2026-10-20 18:06:42.611 [DEBUG] [Thumbnail] Fetch · host=cdn2.invalid · status=403
        """.trimIndent())
        assertEquals(3, LogDisplay.compact(input).size)
        assertEquals(2, LogDisplay.filter(LogDisplay.compact(input), LogTone.DEBUG).size)
    }

    @Test fun filterCountsAndRawMessagesAreNotChanged() {
        val entries = LogDisplay.entries(sample)
        assertEquals(2, LogDisplay.filter(entries, LogTone.WARN).size)
        assertEquals(2, LogDisplay.filter(entries, LogTone.OK).size)
        assertEquals(9, LogDisplay.filter(entries, null).size)
        assertTrue(entries[4].original.contains("cdn.invalid"))
        assertFalse(LogDisplay.readable(entries[4]).contains("cdn.invalid"))
        assertFalse(LogDisplay.readable(entries[4]).contains("muzmo"))
    }

    @Test fun transientHttpErrorsDoNotAutomaticallyBecomeFatal() {
        val entry = LogDisplay.entries(
            "2026-10-20 18:01:19.639 [DEBUG] [MusicWebView] HTTP error · code=405 · mainFrame=false"
        ).single()
        assertEquals(LogTone.DEBUG, entry.tone)
        assertTrue(LogDisplay.readable(entry).contains("сервер", ignoreCase = true))
    }

    @Test fun errorDoesNotMeanSearchReturnedNoResults() {
        val problem = LogDisplay.entries(
            "2026-10-20 18:00:17.100 [WARN] [Muzmo] Settled search DOM has no usable rows · scans=12"
        ).single()
        assertEquals(LogTone.WARN, problem.tone)
        assertTrue(LogDisplay.readable(problem).contains("не доказывает отсутствие песни"))
    }

    @Test fun criticalWatchdogIsRedButOrdinaryTimeoutStaysWarning() {
        assertEquals(LogTone.ERROR, LogDisplay.entries(
            "2026-10-20 18:00:00.000 [WARN] [HitMoz] Search watchdog expired · revision=10"
        ).single().tone)
        assertEquals(LogTone.WARN, LogDisplay.entries(
            "2026-10-20 18:00:00.000 [WARN] [HitMoz] WebView timed out · checking available music results"
        ).single().tone)
    }

    @Test fun readableCrashUsesAttachedStackTrace() {
        val crash = LogDisplay.entries("""
            2026-10-25 12:06:36.408 [ERROR] [Crash] Uncaught exception on thread main
                java.lang.IllegalStateException: Session ID must be unique. ID=
                at androidx.media3.session.MediaSession.Builder.build(MediaSession.java:508)
        """.trimIndent()).single()
        assertTrue(LogDisplay.readable(crash).contains("медиасессии"))
        assertEquals("Сбой приложения", LogDisplay.groupName(crash))
    }
}
