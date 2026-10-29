package com.domenota.medialoader.core.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionProbeRulesTest {
    @Test fun onlyAccountEvidenceConfirmsInstagramSession() {
        assertEquals(DiagnosticState.OK,
            SessionProbeRules.classify(DiagnosticSource.INSTAGRAM, 200,
                "{\"user\":{\"pk\":123}}").state)
        assertEquals(DiagnosticState.UNAVAILABLE,
            SessionProbeRules.classify(DiagnosticSource.INSTAGRAM, 200,
                "<html>login</html>").state)
    }

    @Test fun authChallengeDiffersFromAmbiguousResponse() {
        assertEquals("SESSION_LOGIN_REQUIRED",
            SessionProbeRules.classify(DiagnosticSource.VK, 401, "").reasonCode)
        assertEquals("SESSION_HTTP_403",
            SessionProbeRules.classify(DiagnosticSource.VK, 403, "").reasonCode)
        assertEquals("SESSION_HTTP_429",
            SessionProbeRules.classify(DiagnosticSource.INSTAGRAM, 429, "").reasonCode)
        assertEquals("SESSION_LOGIN_REQUIRED",
            SessionProbeRules.classify(DiagnosticSource.INSTAGRAM, 302, "",
                redirectHost = "www.instagram.com", redirectPath = "/accounts/login/").reasonCode)
        assertEquals("SESSION_PROFILE_UNRECOGNIZED",
            SessionProbeRules.classify(DiagnosticSource.RUTUBE, 200,
                "{\"status\":\"ok\"}", contentType = "application/json").reasonCode)
    }

    @Test fun instagramBadRequestBodyCanIdentifyLoginOrChallenge() {
        assertEquals("SESSION_LOGIN_REQUIRED",
            SessionProbeRules.classify(DiagnosticSource.INSTAGRAM, 400,
                "{\"message\":\"login_required\"}").reasonCode)
        assertEquals("SESSION_CHALLENGE_REQUIRED",
            SessionProbeRules.classify(DiagnosticSource.INSTAGRAM, 400,
                "{\"message\":\"checkpoint_required\"}").reasonCode)
        assertEquals("SESSION_HTTP_400",
            SessionProbeRules.classify(DiagnosticSource.INSTAGRAM, 400,
                "{\"message\":\"unknown\"}").reasonCode)
    }

    @Test fun rutubeProfileRequiresAccountIdentity() {
        assertEquals(DiagnosticState.OK,
            SessionProbeRules.classify(DiagnosticSource.RUTUBE, 200,
                "{\"id\":1,\"username\":\"member\"}",
                contentType = "application/json").state)
        assertEquals("SESSION_LOGIN_PAGE",
            SessionProbeRules.classify(DiagnosticSource.RUTUBE, 200,
                "<html>login</html>", contentType = "text/html").reasonCode)
    }

    @Test fun youtubeRequiresExplicitLoggedInMarker() {
        assertEquals(DiagnosticState.OK,
            SessionProbeRules.classify(DiagnosticSource.YOUTUBE, 200,
                "{\"LOGGED_IN\":true}").state)
        assertEquals(DiagnosticState.UNAVAILABLE,
            SessionProbeRules.classify(DiagnosticSource.YOUTUBE, 200,
                "<html>account</html>").state)
    }
}
