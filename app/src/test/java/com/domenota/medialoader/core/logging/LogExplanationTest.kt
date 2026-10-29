package com.domenota.medialoader.core.logging

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogExplanationTest {
    @Test fun crashUsesItsExceptionInsteadOfGenericError() {
        val header = "2026-10-25 12:06:36.408 [ERROR] [Crash] Uncaught exception on thread main"
        val detail = requireNotNull(LogExplanation.forRecord(header, listOf(
            "    java.lang.IllegalStateException: Session ID must be unique. ID=",
            "    at androidx.media3.session.MediaSession.Builder.build(MediaSession.java:508)",
        )))
        assertTrue(detail.meaning.contains("медиасессии"))
        assertFalse(detail.meaning.contains("пока нет точного пояснения"))
        assertFalse(detail.meaning.contains("ID="))
        assertTrue(requireNotNull(LogExplanation.forRecord(header, emptyList())).meaning
            .contains("аварийно"))
    }

    @Test fun explainsInstagramWarningsFromUserLog() {
        assertTrue(requireNotNull(LogExplanation.forLine(
            "2026-10-25 [WARN] [InstagramProfile] Task stopped · mode=scan · stage=feed · reason=RATE_LIMITED"
        )).meaning.contains("ограничил"))
        assertTrue(requireNotNull(LogExplanation.forLine(
            "2026-10-25 [WARN] [BrowserWebView] Network error · source=instagram · mainFrame=false · code=-1"
        )).meaning.contains("вспомогательн"))
        assertTrue(requireNotNull(LogExplanation.forLine(
            "2026-10-25 [INFO] [Diagnostics] Session probe · source=instagram · status=400 · reason=SESSION_HTTP_400"
        )).meaning.contains("не подтверд"))
    }

    @Test fun exportAnnotationsUseStackWithoutRemovingOriginalLines() {
        val raw = "2026-10-25 12:06:36.408 [ERROR] [Crash] Uncaught exception on thread main\n" +
            "    java.lang.IllegalStateException: Session ID must be unique. ID=\n" +
            "    at androidx.media3.session.MediaSession.Builder.build(MediaSession.java:508)\n" +
            "2026-10-25 12:06:39.242 [INFO] [App] Session started"
        val annotated = LogExplanation.annotate(raw)
        assertTrue(annotated.contains("медиасессии"))
        assertTrue(annotated.contains("java.lang.IllegalStateException: Session ID must be unique. ID="))
        assertTrue(annotated.contains("2026-10-25 12:06:39.242 [INFO] [App] Session started"))
    }

    @Test fun explainsTimeoutWithoutClaimingServerIsDown() {
        val details = requireNotNull(LogExplanation.forLine(
            "2026-10-19 23:40:29 [WARN] [HitMoz] java.net.SocketTimeoutException: Read timed out"
        ))
        assertTrue(details.meaning.contains("время"))
        assertFalse(details.meaning.contains("сервер не работает"))
    }

    @Test fun distinguishesHttpErrorsAndTransport() {
        assertTrue(requireNotNull(LogExplanation.forLine(
            "2026-10-19 [DEBUG] [MusicWebView] HTTP error · code=405 · method=POST"
        )).meaning.contains("метод"))
        assertTrue(requireNotNull(LogExplanation.forLine(
            "2026-10-19 [DEBUG] [HitMoz] MP3 transport · client=platform-retry"
        )).meaning.contains("Android"))
    }

    @Test fun missingAndStacktraceLinesAreNotInvented() {
        assertNull(LogExplanation.forLine("    at kotlin.coroutines.jvm.internal.BaseContinuation"))
        assertNull(LogExplanation.forLine("2026-10-19 [INFO] [Foo] Unrecognized future event"))
    }

    @Test fun recognitionsAreExplainedByProvenance() {
        assertTrue(requireNotNull(LogExplanation.forLine(
            "2026-10-19 [INFO] [Recognition] Applied · source=SHAZAM_DIRECT"
        )).meaning.contains("звуковому"))
        assertTrue(requireNotNull(LogExplanation.forLine(
            "2026-10-19 [INFO] [Recognition] Applied · source=SOURCE_METADATA"
        )).meaning.contains("метаданных"))
    }

    @Test fun dnsLookupExplanationDoesNotAccuseAProviderOrCdn() {
        val details = requireNotNull(LogExplanation.forLine(
            "2026-10-20 [DEBUG] [HitMoz] MP3 network failure · host=cdn12.deliciousoranges.com · reason=DNS_LOOKUP_FAILED"
        ))
        assertTrue(details.meaning.contains("DNS"))
        assertTrue(details.meaning.contains("не доказывает"))
    }

    @Test fun watchdogIsRedButOrdinaryWarningsRemainAmber() {
        assertTrue(LogExplanation.isCriticalSearchTimeout(
            "2026-10-20 [WARN] [HitMoz] Search watchdog expired · revision=10 · timeoutMs=20000"
        ))
        assertFalse(LogExplanation.isCriticalSearchTimeout(
            "2026-10-20 [WARN] [HitMoz] WebView timed out · checking available music results"
        ))
        assertFalse(LogExplanation.isCriticalSearchTimeout(
            "2026-10-20 [WARN] [Thumbnail] Thumbnail HTTP 403"
        ))
    }

    @Test fun resetAndWebviewTimeoutKeepDistinctExplanations() {
        val reset = requireNotNull(LogExplanation.forLine(
            "2026-10-20 [WARN] [HitMoz] MP3 attempt diagnostic · reason=CONNECTION_RESET"
        ))
        val pending = requireNotNull(LogExplanation.forLine(
            "2026-10-20 [WARN] [HitMoz] WebView timed out · checking available music results"
        ))
        assertTrue(reset.meaning.contains("сбросом"))
        assertTrue(pending.meaning.contains("ещё проверяет"))
    }

    @Test fun doesNotEchoPrivateFieldsFromTheRawLog() {
        val detail = requireNotNull(LogExplanation.forLine(
            "2026-10-19 [WARN] [Thumbnail] Thumbnail HTTP 403 · token=SECRET"
        ))
        assertFalse(detail.meaning.contains("SECRET"))
    }
}
