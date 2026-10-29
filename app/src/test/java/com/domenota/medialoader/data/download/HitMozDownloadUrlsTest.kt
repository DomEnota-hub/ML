package com.domenota.medialoader.data.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HitMozDownloadUrlsTest {

    @Test fun acceptsRealBrowserVerifiedMusicLinks() {
        listOf(
            "https://ru.hitmoz.org/get/music/20260506/ENZRO_-_Ty_ne_bojjsya_nochi_81423717.mp3",
            "https://ru.hitmoz.org/get/music/20190820/Tones_and_I_-_Dance_Monkey_66175914.mp3",
            "https://ru.hitmoz.org/get/music/20190305/Korol_i_SHut_-_Kukla_kolduna_62570545.mp3",
            "https://ru.hitmoz.org/get/music/20260823/Jahballa_Ibu_DaDon_-_Active_82195132.mp3",
        ).forEach {
            assertTrue("A copied HitMoz download link must be accepted", HitMozDownloadUrls.isMp3(it))
        }
    }


    @Test fun diagnosticAddressCorrelatesRequestsWithoutExposingSignedUrl() {
        val address = "https://ru.hitmoz.org/get/music/Private_Song.mp3?token=private-value"
        val label = HitMozDownloadUrls.diagnosticAddress(address)
        assertEquals(label, HitMozDownloadUrls.diagnosticAddress(address))
        assertTrue(label.contains("path=music · query=true · urlId="))
        assertFalse(label.contains("Private_Song"))
        assertFalse(label.contains("private-value"))
        assertFalse(label == HitMozDownloadUrls.diagnosticAddress(address.replace("private-value", "other-value")))
        assertTrue(HitMozDownloadUrls.diagnosticAddress("blob:https://ru.hitmoz.org/id").contains("path=other"))
    }

    @Test fun errorPageDiagnosticReportsOnlyCategories() {
        assertEquals("cloudflare", HitMozResponseDiagnostics.errorCategory("Cloudflare challenge token=secret"))
        assertEquals("captcha", HitMozResponseDiagnostics.errorCategory("Please complete CAPTCHA"))
        assertEquals("access-denied", HitMozResponseDiagnostics.errorCategory("Access denied for user Alice"))
        assertEquals("other", HitMozResponseDiagnostics.errorCategory("<html>private content</html>"))
        assertEquals("empty", HitMozResponseDiagnostics.errorCategory(""))
    }

    @Test fun validatesBrowserReferers() {
        assertTrue(HitMozDownloadUrls.isTrustedPage(
            "https://ru.hitmoz.org/search?q=Jahballa+Active",
        ))
        assertTrue(HitMozDownloadUrls.isTrustedPage(
            "https://ru.hitmoz.org/song/123456",
        ))
        val unsafePages = listOf(
            "http://ru.hitmoz.org/search?q=x",
            "https://ru.hitmoz.org.evil.example/search",
            "https://eu.hitmoz.com/search?q=x",
            "https://ru.hitmoz.org:8080/search?q=x",
            "https://user:secret@ru.hitmoz.org/search",
            "https://ru.hitmoz.org/get/music/a.mp3",
            "https://ru.hitmoz.org/../../search?q=x",
        )
        unsafePages.forEach {
            assertFalse("Rejected browser referrer: $it", HitMozDownloadUrls.isTrustedPage(it))
        }
    }

    @Test fun acceptsStrictOriginalMusicMp3() {
        assertTrue(HitMozDownloadUrls.isMp3(
            "https://ru.hitmoz.org/get/music/20261014/Pitbull_-_Hotel_Room_Service_497858301.mp3",
        ))
        assertTrue(HitMozDownloadUrls.isMp3(
            "https://ru.hitmoz.org/get/music/a_song_12345678.mp3?ref=search",
        ))
    }

    @Test fun followsHitMozAndNumberedMediaCdnsOnly() {
        val trusted = listOf(
            "https://ru.hitmoz.org/get/music/20261014/song_45411599.mp3?token=abc",
            "https://eu.hitmoz.com/get/music/20261014/song_45411599.mp3",
            "https://cdn.hitmoz.org/get/music/20261014/song_45411599.mp3",
            "https://statcore.hitmcdn.com/media/track-abc123?token=public",
            "https://cdn21.deliciousoranges.com/get/music/20261014/song_45411599.mp3",
            "https://s3.deliciouspeaches.com/get/music/20261014/song_45411599.mp3",
            "https://s2.deliciouspeaches.com/get/music/20261014/song_45411599.mp3",
            "https://cdn7.deliciouspeaches.com/get/music/20261014/song_45411599.mp3",
            // CDN delivery links may use paths different from HitMoz /get/music/*.mp3.
            "https://cdn12.deliciousoranges.com/files/audio/9b6c77b8?expires=123&signature=abc",
            "https://cdn19.deliciousoranges.com/stream/track-abc123",
            "https://s3.deliciouspeaches.com/path/song.mp3",
            "https://s2.deliciouspeaches.com/media/opaque-signature",
        )
        trusted.forEach { url ->
            assertTrue("Should accept redirect: $url", HitMozDownloadUrls.isTrustedRedirect(url))
        }

        val unsafe = listOf(
            "https://ads.example/get/music/song.mp3",
            "https://cdn21.deliciousoranges.com.evil.example/get/music/song.mp3",
            "https://s3.deliciouspeaches.com.ads.example/get/music/song.mp3",
            "https://deliciousoranges.com/get/music/song.mp3",
            "https://evil.hitmcdn.com/media/track.mp3",
            "https://promo.deliciouspeaches.com/get/music/song.mp3",
            "https://cdn21.deliciousoranges.com/ads/click.mp3",
            "https://cdn12.deliciousoranges.com/promo/ad-track.mp3",
            "https://cdn12.deliciousoranges.com/response.html",
            "https://s3.deliciouspeaches.com/tracking.json",
            "https://cdn19.deliciousoranges.com/",
            "https://s2.deliciouspeaches.com/get/music/tracker.html",
            "https://cdnnnn.deliciousoranges.com/get/music/song.mp3",
            "https://ru.hitmoz.org.evil.example/get/music/song.mp3",
            "http://s3.deliciouspeaches.com/get/music/song.mp3",
            "https://ru.hitmoz.org:8080/get/music/a.mp3",
            "https://ru.hitmoz.org/redirect/../../admin.mp3",
            "https://user:password@s3.deliciouspeaches.com/get/music/file.mp3",
            "https://s3.deliciouspeaches.com/get/music/../ads/file.mp3",
        )
        unsafe.forEach { url ->
            assertFalse("Should reject redirect: $url", HitMozDownloadUrls.isTrustedRedirect(url))
        }
    }

    @Test fun rejectsAdsExternalDownloadsAndUnsafeSchemes() {
        val unsafe = listOf(
            "https://ads.example/get/music/song.mp3",
            "https://ru.hitmoz.org.evil.example/get/music/song.mp3",
            "http://ru.hitmoz.org/get/music/song.mp3",
            "javascript:alert(1)",
            "https://ru.hitmoz.org/song/12345",
            "https://ru.hitmoz.org/search?q=song",
            "https://ru.hitmoz.org/get/music/../../fake.mp3",
            "https://user:secret@ru.hitmoz.org/get/music/song.mp3",
            "https://ru.hitmoz.org:8080/get/music/song.mp3",
        )
        unsafe.forEach { url ->
            assertFalse("Should reject $url", HitMozDownloadUrls.isMp3(url))
        }
    }
}
