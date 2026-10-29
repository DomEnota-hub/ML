package com.domenota.medialoader.core.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeNetworkAddressTest {
    @Test fun diagnosticHidesPathAndQueryValuesButKeepsStableFingerprint() {
        val url = "https://rmr.muzmo.cc/get/music/20261006/private-song.mp3?token=secret"
        val first = SafeNetworkAddress.describe(url)

        assertTrue(first.contains("host=rmr.muzmo.cc"))
        assertTrue(first.contains("path=get"))
        assertTrue(first.contains("query=true"))
        assertFalse(first.contains("private-song"))
        assertFalse(first.contains("secret"))
        assertEquals(first, SafeNetworkAddress.describe(url))
        assertFalse(first == SafeNetworkAddress.describe(url.replace("secret", "another")))
    }
}
