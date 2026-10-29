package com.domenota.medialoader.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HitMozSearchPageGateTest {
    private val expected = "https://ru.hitmoz.org/search?q=%D0%95%D0%B2%D0%B0+%D0%92%D0%BB%D0%B0%D1%81%D0%BE%D0%B2%D0%B0"

    @Test fun exactSearchAcceptsTrackingParametersButNotDifferentQueries() {
        assertEquals(HitMozSearchPageGate.Route.EXACT,
            HitMozSearchPageGate.route(expected, expected + "&utm_source=ref"))
        assertEquals(HitMozSearchPageGate.Route.REJECT,
            HitMozSearchPageGate.route(expected, "https://ru.hitmoz.org/search?q=Other"))
    }

    @Test fun querylessSearchPageNeedsDomProof() {
        assertEquals(HitMozSearchPageGate.Route.NEED_DOM_PROOF,
            HitMozSearchPageGate.route(expected, "https://ru.hitmoz.org/search"))
        assertTrue(HitMozSearchPageGate.domQueryMatches(expected, "  ева    власова  "))
        assertFalse(HitMozSearchPageGate.domQueryMatches(expected, "Ева Петрова"))
    }

    @Test fun neverTrustsOtherHostsPathsProtocolsOrCredentialUrls() {
        listOf(
            "https://evil.example.com/search?q=Eva",
            "https://ru.hitmoz.org.evil.test/search?q=Eva",
            "http://ru.hitmoz.org/search?q=Eva",
            "https://user:pass@ru.hitmoz.org/search?q=Eva",
            "https://ru.hitmoz.org:8443/search?q=Eva",
            "https://ru.hitmoz.org/song/12345",
            "https://ru.hitmoz.org/search?q=other",
        ).forEach { assertEquals(it, HitMozSearchPageGate.Route.REJECT,
            HitMozSearchPageGate.route(expected, it)) }
    }

    @Test fun emptyOrUntrustedRequestedSearchIsNeverAccepted() {
        assertEquals(HitMozSearchPageGate.Route.REJECT,
            HitMozSearchPageGate.route("https://ru.hitmoz.org/search?q=", "https://ru.hitmoz.org/search"))
        assertEquals(HitMozSearchPageGate.Route.REJECT,
            HitMozSearchPageGate.route("https://evil.example.com/search?q=a", "https://ru.hitmoz.org/search?q=a"))
    }
}
