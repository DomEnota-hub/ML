package com.domenota.medialoader.data.playback

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class LrclibLyricsProviderTest {
    private val track=LyricsTrackSignature("Test & tune","Test artist","Test album",120000)
    private fun row(id:Int,album:String="Test album",duration:Int=120)=
        """{"id":$id,"trackName":"Test & tune","artistName":"Test artist","albumName":"$album","duration":$duration,"instrumental":false,"plainLyrics":"Invented fixture","syncedLyrics":"[00:01.00]Invented fixture"}"""
    @Test fun strictMatchingAndSyncedLyrics() {
        val hits=LrclibProtocol.parse("[${row(1)},${row(2,"Different album")}]")
        val found=LrclibProtocol.automatic(track,hits)
        assertEquals(1L,found?.candidate?.id)
        assertEquals(LyricsOrigin.LRCLIB,found?.lyrics?.origin)
        assertEquals(1000L,found?.lyrics?.timed?.single()?.atMs)
        assertNull(LrclibProtocol.automatic(track.copy(album=null),hits))
        assertNull(LrclibProtocol.automatic(track,LrclibProtocol.parse("[${row(1)},${row(2)}]")))
        assertNull(LrclibProtocol.automatic(track,LrclibProtocol.parse("[${row(1,duration=123)}]")))
    }
    @Test fun queryUsesOnlyEncodedTitleAndArtist() {
        val url=LrclibProtocol.url(track)
        assertEquals("https://lrclib.net/api/search?track_name=Test+%26+tune&artist_name=Test+artist",url)
        assertFalse(url.contains("album"))
    }
    @Test fun retryAfterStopsRequestsAndRevokedAccessNeverSends()=runBlocking {
        var now=1000L;var requests=0
        val provider=LrclibLyricsProvider({_,_->requests++;LyricsHttpResponse(429,"text/plain","120",null)}, {now}, {now+=it})
        assertTrue(provider.search(track){false} is LyricsLookup.Revoked)
        assertEquals(0,requests)
        assertEquals(LyricsLookup.RetryLater(120),provider.search(track){true})
        now+=100000
        assertTrue(provider.search(track){true} is LyricsLookup.RetryLater)
        assertEquals(1,requests)
    }
    @Test fun nonJsonAndMalformedPayloadAreNotNotFound()=runBlocking {
        val bad=LrclibLyricsProvider({_,_->LyricsHttpResponse(200,"text/html",null,"[]")},{1000L},{})
        assertEquals(LyricsLookup.Unavailable,bad.search(track){true})
        val malformed=LrclibLyricsProvider({_,_->LyricsHttpResponse(200,"application/json",null,"broken")},{1000L},{})
        assertEquals(LyricsLookup.Unavailable,malformed.search(track){true})
    }
}
