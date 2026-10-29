package com.domenota.medialoader.core.provider

import org.junit.Assert.assertEquals
import org.junit.Test

class YouTubeWarningDiagnosticsTest {
    @Test fun classifiesMissingJavaScriptSupportWithoutLoggingResponseText() {
        val stderr = "WARNING: [youtube] No supported JavaScript runtime could be found; " +
            "https://www.youtube.com/watch?v=secret&token=private\n" +
            "WARNING: [youtube] Signature extraction failed: n challenge solving failed"
        assertEquals(
            listOf("javascript-runtime-unavailable", "signature-challenge-failed"),
            YouTubeWarningDiagnostics.classify(stderr),
        )
    }

    @Test fun deduplicatesKnownWarningsAndIgnoresUnrelatedText() {
        assertEquals(
            listOf("po-token-required", "adaptive-streams-skipped"),
            YouTubeWarningDiagnostics.classify(
                "PO Token is required\npo_token missing\nSome formats have been skipped due to SABR streaming\n" +
                    "cookie=secret",
            ),
        )
    }

    @Test fun mentioningEjsDoesNotMeanRuntimeIsUnavailable() {
        assertEquals(emptyList<String>(), YouTubeWarningDiagnostics.classify(
            "[debug] Loading yt-dlp-ejs component with QuickJS JavaScript runtime",
        ))
    }
}
