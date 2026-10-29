package com.domenota.medialoader.core.diagnostics

import org.junit.Assert.assertTrue
import org.junit.Test

class InstagramLoginFailureTest {
    @Test fun rateLimitExplainsRetryWithoutBlamingSavedSession() {
        val explanation = InstagramLoginFailure.explain(429)
        assertTrue(explanation.contains("429"))
        assertTrue(explanation.lowercase().contains("подожд"))
    }

    @Test fun otherHttpFailureKeepsStatusForDiagnostics() {
        assertTrue(InstagramLoginFailure.explain(403).contains("403"))
    }
}
