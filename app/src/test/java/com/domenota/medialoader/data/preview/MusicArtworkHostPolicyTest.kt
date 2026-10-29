package com.domenota.medialoader.data.preview

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicArtworkHostPolicyTest {
    @Test fun allowsTrustedHttpsCoversWithoutMusicSourceHint() {
        assertTrue(MusicArtworkHostPolicy.allowed("https://is1-ssl.mzstatic.com/image/600x600bb.jpg"))
        assertTrue(MusicArtworkHostPolicy.allowed("https://is2-ssl.mzstatic.com/image/100x100bb.jpg"))
        assertTrue(MusicArtworkHostPolicy.allowed("https://www.shazam.com/image/cover.png"))
    }

    @Test fun rejectsUntrustedHostsSchemesPortsAndCredentials() {
        listOf(
            "https://is1-ssl.mzstatic.com.attacker.example/image.jpg",
            "http://is1-ssl.mzstatic.com/image.jpg",
            "https://username:password@is1-ssl.mzstatic.com/image.jpg",
            "https://is1-ssl.mzstatic.com:8443/image.jpg",
            "https://evil.example.com/img.jpg",
            "https://is1-ssl.mzstatic.com/",
        ).forEach { assertFalse("Rejected: $it", MusicArtworkHostPolicy.allowed(it)) }
    }
}
