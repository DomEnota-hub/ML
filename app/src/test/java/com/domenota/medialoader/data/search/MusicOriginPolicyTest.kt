package com.domenota.medialoader.data.search

import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class MusicOriginPolicyTest {
    private val h = MusicOriginProvider.HITMOZ
    private val m = MusicOriginProvider.MUZMO

    @Test fun exactBrandAndSupportedSuffixOnly() {
        assertEquals("ru.hitmoz.net", MusicOriginPolicy.candidateHost(h, "RU.hitmoz.net"))
        assertEquals("rmr.muzmo.org", MusicOriginPolicy.hostOf(m, "https://rmr.muzmo.org/search"))
        listOf("hitmoz.org.evil.com", "evil-hitmoz.org", "hitmoz.evil", "foo.hitmoz.org",
            "muzmo.org", "127.0.0.1", "hitmoz.org.").forEach {
            assertNull(it, MusicOriginPolicy.candidateHost(h, it))
        }
        listOf("http://hitmoz.org", "https://user@hitmoz.org", "https://hitmoz.org:444",
            "https://hitmoz.org/a/../b", "https://hitmoz.org/#x").forEach {
            assertNull(it, MusicOriginPolicy.hostOf(h, it))
        }
    }

    @Test fun onlyMainFrameActiveSearchMissingTriggersRecovery() {
        assertTrue(MusicOriginPolicy.shouldInvestigate(true, true, true, null))
        assertTrue(MusicOriginPolicy.shouldInvestigate(true, true, false, 404))
        assertTrue(MusicOriginPolicy.shouldInvestigate(true, true, false, 410))
        for (status in listOf(200, 401, 403, 429, 500, 503))
            assertFalse(MusicOriginPolicy.shouldInvestigate(true, true, false, status))
        assertFalse(MusicOriginPolicy.shouldInvestigate(false, true, true, null))
        assertFalse(MusicOriginPolicy.shouldInvestigate(true, false, false, 404))
        assertFalse(MusicOriginPolicy.shouldInvestigate(true, true, false, null))
    }

    @Test fun identityContinuityRequiresPermanentHttpsRedirectToSameBrand() {
        assertEquals("ru.hitmoz.net", MusicOriginPolicy.permanentMigration(h, h.defaultHost, 301, "https://ru.hitmoz.net/"))
        assertEquals("hitmoz.net", MusicOriginPolicy.permanentMigration(h, h.defaultHost, 308, "https://hitmoz.net/"))
        for (status in listOf(200, 302, 307, 404))
            assertNull(MusicOriginPolicy.permanentMigration(h, h.defaultHost, status, "https://hitmoz.net/"))
        assertNull(MusicOriginPolicy.permanentMigration(h, h.defaultHost, 301, "https://evil.net/"))
        assertNull(MusicOriginPolicy.permanentMigration(h, h.defaultHost, 301, "https://ru.hitmoz.org/"))
        assertFalse(MusicOriginPolicy.sameHost("https://hitmoz.net/", "https://hitmoz.org/"))
        assertTrue(MusicOriginPolicy.sameHost("https://hitmoz.net/a", "https://hitmoz.net/b"))
    }

    @Test fun privateAndReservedAddressesAreRejected() {
        listOf("127.0.0.1", "10.0.0.1", "192.168.1.1", "172.16.0.1", "169.254.1.1",
            "100.64.0.1", "192.0.2.1", "198.51.100.1", "::1", "fc00::1", "fe80::1", "2001:db8::1")
            .forEach { assertFalse(it, MusicOriginPolicy.publicAddress(InetAddress.getByName(it))) }
        assertTrue(MusicOriginPolicy.publicAddress(InetAddress.getByName("8.8.8.8")))
        assertTrue(MusicOriginPolicy.publicAddress(InetAddress.getByName("2606:4700:4700::1111")))
    }

    @Test fun searchResultsDoNotAcceptForeignHostsOrAdRedirects() {
        val html = """<a href="https://hitmoz.net/">ok</a>
          <a href="https://hitmoz.net.evil.com/">lookalike</a>
          <a href="https://www.google.com/url?q=https%3A%2F%2Fru.hitmoz.cc%2F">wrapped</a>
          <a href="https://ads.test/?url=https://hitmoz.org">ad</a>
          <a href="https://muzmo.org/">other</a>"""
        assertEquals(listOf("hitmoz.net", "ru.hitmoz.net", "ru.hitmoz.cc"),
            MusicOriginRecovery.candidates(h, "https://www.google.com/search?q=hitmoz", html))
    }
}
