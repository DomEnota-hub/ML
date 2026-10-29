package com.domenota.medialoader.data.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicArtworkLookupTest {
    private val payload = """
        {
          "results": [
            {"artistName":"Damage","trackName":"STEAL MY HEART (Slowed)",
             "artworkUrl100":"https://is1-ssl.mzstatic.com/image/remix.jpg"},
            {"artistName":"Damage","trackName":"STEAL MY HEART",
             "artworkUrl100":"https://is1-ssl.mzstatic.com/image/album.jpg"},
            {"artistName":"Another Artist","trackName":"STEAL MY HEART",
             "artworkUrl100":"https://is1-ssl.mzstatic.com/image/other.jpg"}
          ]
        }
    """.trimIndent()

    @Test fun picksOnlyExactTrackNotRemix() {
        assertEquals(
            "https://is1-ssl.mzstatic.com/image/album.jpg",
            MusicArtworkLookup.parseExactArtwork(payload, "DAMAGE", "STEAL MY HEART"),
        )
        assertNull(MusicArtworkLookup.parseExactArtwork(payload, "Damage", "Another Track"))
    }

    @Test fun expandsCatalogPreviewWithoutChangingUnknownArtworkPaths() {
        assertEquals(
            "https://is1-ssl.mzstatic.com/image/600x600bb.jpg",
            MusicArtworkLookup.highResolutionArtwork("https://is1-ssl.mzstatic.com/image/100x100bb.jpg"),
        )
        assertEquals(
            "https://is1-ssl.mzstatic.com/image/custom.jpg",
            MusicArtworkLookup.highResolutionArtwork("https://is1-ssl.mzstatic.com/image/custom.jpg"),
        )
    }

    @Test fun rejectsUntrustedAndNonHttpsImages() {
        assertTrue(MusicArtworkLookup.isTrustedArtwork("https://is1-ssl.mzstatic.com/image/a.jpg"))
        assertFalse(MusicArtworkLookup.isTrustedArtwork("http://is1-ssl.mzstatic.com/image/a.jpg"))
        assertFalse(MusicArtworkLookup.isTrustedArtwork("https://mzstatic.com.evil.example/a.jpg"))
        assertFalse(MusicArtworkLookup.isTrustedArtwork("https://attacker@is1-ssl.mzstatic.com/a.jpg"))
        assertFalse(MusicArtworkLookup.isTrustedArtwork("https://is1-ssl.mzstatic.com:8080/a.jpg"))
    }

    @Test fun ignoresSpoofedCatalogArtwork() {
        val unsafe = """
          {"results":[{"artistName":"Damage","trackName":"STEAL MY HEART",
           "artworkUrl100":"https://other.invalid/image.jpg"}]}
        """.trimIndent()
        assertNull(MusicArtworkLookup.parseExactArtwork(unsafe, "Damage", "STEAL MY HEART"))
    }
}
