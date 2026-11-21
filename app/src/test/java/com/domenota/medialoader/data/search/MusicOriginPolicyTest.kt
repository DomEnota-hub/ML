package com.domenota.medialoader.data.search

import java.net.InetAddress
import kotlinx.coroutines.runBlocking
import com.domenota.medialoader.data.search.MusicOriginRecovery.Response
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
    private val hitRows = """<a href="/get/music/20260101/one.mp3">1</a>
        <a href="/get/music/20260101/two.mp3">2</a><a href="/song/123456">song</a>"""
    @Test fun temporaryFailureOrSingle404NeverCallsSearchEngine() = runBlocking {
        val checked = mutableListOf<String>()
        val result = MusicOriginRecovery.resolve(h, h.defaultHost) { url ->
            checked.add(url)
            if (url.endsWith("/")) Response(200) else Response(404)
        }
        assertNull(result)
        assertEquals(2, checked.size)
        assertTrue(checked.all { it.startsWith("https://ru.hitmoz.org/") })
        val blocked = MusicOriginRecovery.resolve(h, h.defaultHost) { url ->
            assertTrue(url.startsWith("https://ru.hitmoz.org/"))
            Response(403)
        }
        assertNull(blocked)
    }

    @Test fun permanentRedirectWithCompatibleSearchNeedsNoManualApproval() = runBlocking {
        val result = MusicOriginRecovery.resolve(h, h.defaultHost) { url ->
            when {
                url.startsWith("https://ru.hitmoz.org/") -> Response(301, "https://ru.hitmoz.net/")
                url == "https://ru.hitmoz.net/search?q=Mozart" -> Response(200, body = hitRows)
                else -> error("Unexpected request $url")
            }
        }
        assertEquals(MusicOriginRecovery.Evidence("ru.hitmoz.net", true), result)
    }

    @Test fun dnsLossFallsBackFromGoogleToYandexButRequiresConfirmation() = runBlocking {
        val checked = mutableListOf<String>()
        val result = MusicOriginRecovery.resolve(m, m.defaultHost) { url ->
            checked.add(url)
            when {
                url.startsWith("https://rmr.muzmo.cc/") -> Response(dnsMissing = true)
                url.startsWith("https://www.google.com/") -> Response(429)
                url.startsWith("https://yandex.ru/") -> Response(200, body = """<a href="https://rmr.muzmo.org/">result</a>""")
                url == "https://rmr.muzmo.org/search?q=Mozart" -> Response(200, body =
                    """<a href="/info?id=123">one</a><a href="/info?id=456">two</a>""")
                url == "https://rmr.muzmo.org/info?id=123" -> Response(200, body =
                    """<a href="/get/music/20260101/one.mp3">download</a>""")
                else -> error("Unexpected request $url")
            }
        }
        assertEquals(MusicOriginRecovery.Evidence("rmr.muzmo.org", false), result)
        assertEquals(3, checked.count { it.startsWith("https://rmr.muzmo.cc/") })
        assertTrue(checked.indexOfFirst { it.contains("google.com") } < checked.indexOfFirst { it.contains("yandex.ru") })
        assertTrue(checked.filter { it.contains("google.com") || it.contains("yandex.ru") }.all { it.endsWith("muzmo") })
    }

    @Test fun brokenOrForeignCandidateContractDoesNotActivate() = runBlocking {
        val result = MusicOriginRecovery.resolve(h, h.defaultHost) { url ->
            when {
                url.startsWith("https://ru.hitmoz.org/") -> Response(410)
                url.startsWith("https://www.google.com/") -> Response(200, body =
                    """<a href="https://hitmoz.net.evil.com/">fake</a><a href="https://ru.hitmoz.net/">wrong parser</a>""")
                url == "https://ru.hitmoz.net/search?q=Mozart" -> Response(200, body =
                    """<a href="https://foreign.test/get/music/20260101/one.mp3">fake</a>""")
                url.startsWith("https://yandex.ru/") -> Response(200, body = "")
                else -> error("Unexpected request $url")
            }
        }
        assertNull(result)
    }

}
