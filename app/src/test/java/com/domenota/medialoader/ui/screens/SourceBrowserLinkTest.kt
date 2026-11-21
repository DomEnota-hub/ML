package com.domenota.medialoader.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SourceBrowserLinkTest {
    @Test fun youtubeShortShareLinkNavigatesAndResolvesCanonicalVideo() {
        val share = "https://youtu.be/dQw4w9WgXcQ?si=one-time"
        assertTrue(BrowserSource.YOUTUBE.isSite(share))
        assertTrue(BrowserSource.YOUTUBE.allowsNavigation(share))
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            BrowserSource.YOUTUBE.acceptedLink(share))
        assertNull(BrowserSource.INSTAGRAM.acceptedLink(share))
    }

    @Test fun youtubeShortShareLinkCannotBeSpoofedByAnotherHostOrScheme() {
        val spoofed = "https://youtu.be.evil.example/dQw4w9WgXcQ"
        assertFalse(BrowserSource.YOUTUBE.isSite(spoofed))
        assertNull(BrowserSource.YOUTUBE.acceptedLink(spoofed))
        assertFalse(BrowserSource.YOUTUBE.allowsNavigation("http://youtu.be/dQw4w9WgXcQ"))
        assertNull(BrowserSource.YOUTUBE.acceptedLink("https://youtu.be/incorrect"))
    }
}
