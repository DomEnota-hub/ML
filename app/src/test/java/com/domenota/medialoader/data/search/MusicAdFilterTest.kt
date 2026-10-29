package com.domenota.medialoader.data.search

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicAdFilterTest {
    @Test fun filtersKnownAdvertisingHostsAndSubdomains() {
        assertTrue(MusicAdFilter.shouldBlockResource("https://an.yandex.ru/system/context.js"))
        assertTrue(MusicAdFilter.shouldBlockResource("https://x.doubleclick.net/tag.js"))
        assertTrue(MusicAdFilter.shouldBlockResource("https://ads.example.com/adserver/prebid.js"))
        assertTrue(MusicAdFilter.shouldBlockResource("https://ads.example.com/path/ads/video.mp4"))
    }

    @Test fun cannotBlockSongSiteCdnOrNativeMusicSources() {
        listOf(
            "https://ru.hitmoz.org/get/music/track.mp3",
            "https://cdn19.deliciousoranges.com/files/music?id=123",
            "https://s3.deliciouspeaches.com/signed/abc?token=789",
            "https://www.youtube.com/watch?v=test",
            "https://vk.com/video-1_2",
            "https://rutube.ru/video/12345/",
        ).forEach { address ->
            assertFalse("Media source must not be blocked: $address",
                MusicAdFilter.shouldBlockResource(address))
        }
    }

    @Test fun neverBlocksMainFrameOrSpoofedHosts() {
        assertFalse(MusicAdFilter.shouldBlockResource("https://an.yandex.ru/ad.js", true))
        assertFalse(MusicAdFilter.isBlockedHost("notdoubleclick.net"))
        assertFalse(MusicAdFilter.isBlockedHost("googleadservices.com.safe.example"))
        assertFalse(MusicAdFilter.shouldBlockResource("file:///sdcard/page.html"))
        assertFalse(MusicAdFilter.shouldBlockResource("javascript:alert(1)"))
    }
}
