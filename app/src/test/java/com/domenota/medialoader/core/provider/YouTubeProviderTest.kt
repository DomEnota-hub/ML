package com.domenota.medialoader.core.provider

import com.domenota.medialoader.core.model.MediaType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeProviderTest {
    private val url = "https://youtu.be/dQw4w9WgXcQ?si=share"

    private class FakeExtractor(private val result: suspend (String) -> StreamInfo) : StreamExtractor {
        val requested = mutableListOf<String>()
        override suspend fun extract(url: String): StreamInfo { requested += url; return result(url) }
    }

    private fun provider(extractor: StreamExtractor = FakeExtractor { YtDlpJson.parse(YtDlpJsonTest.SAMPLE) }) =
        YouTubeProvider(extractor)

    private fun expectError(block: suspend () -> Any?): ProviderException {
        try { runBlocking { block() } } catch (error: ProviderException) { return error }
        throw AssertionError("Expected ProviderException")
    }

    @Test fun supportsOnlyYouTubeLinks() {
        assertTrue(provider().supports(url))
        assertFalse(provider().supports("https://www.instagram.com/p/Ab_12-z/"))
    }

    @Test fun asksTheExtractorForTheCanonicalUrl() {
        runBlocking {
            val extractor = FakeExtractor { YtDlpJson.parse(YtDlpJsonTest.SAMPLE) }
            provider(extractor).resolve("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PL1&index=3")
            assertEquals(listOf("https://www.youtube.com/watch?v=dQw4w9WgXcQ"), extractor.requested)
        }
    }

    @Test fun offersEachQualityBestFirstThenAudioOnly() {
        runBlocking {
            val items = provider().resolve(url)
            assertEquals(
                listOf("dQw4w9WgXcQ_2160p", "dQw4w9WgXcQ_1080p", "dQw4w9WgXcQ_360p", "dQw4w9WgXcQ_audio"),
                items.map { it.id },
            )
            assertEquals(listOf(MediaType.VIDEO, MediaType.VIDEO, MediaType.VIDEO, MediaType.AUDIO), items.map { it.type })
            assertEquals(listOf("2160p", "1080p60", "360p", "MP3"), items.map { it.qualityLabel })
        }
    }

    @Test fun bestQualityUpTo1080pIsPreselectedAndVideosShareAGroup() {
        runBlocking {
            val items = provider().resolve(url)
            assertEquals(listOf(false, true, false, false), items.map { it.preselected })
            assertEquals(setOf(YouTubeProvider.VIDEO_GROUP), items.filter { it.type == MediaType.VIDEO }.map { it.group }.toSet())
            assertNull(items.last().group)
        }
    }

    @Test fun namesComeFromTheTitleAndCarryTheQuality() {
        runBlocking {
            val items = provider().resolve(url)
            assertEquals("Test Video A B quote_1080p.mp4", items[1].originalName)
            assertEquals("Test Video A B quote.mp3", items.last().originalName)
        }
    }

    @Test fun sizeAddsTheAudioStreamOnlyForVideoOnlyFormats() {
        runBlocking {
            val items = provider().resolve(url).associateBy { it.id }
            assertEquals(90_000_000L + 3_000_000L, items.getValue("dQw4w9WgXcQ_2160p").sizeBytes)
            assertEquals(30_000_000L + 3_000_000L, items.getValue("dQw4w9WgXcQ_1080p").sizeBytes)
            assertEquals(5_000_000L, items.getValue("dQw4w9WgXcQ_360p").sizeBytes) // progressive: audio already inside
            assertEquals(3_000_000L, items.getValue("dQw4w9WgXcQ_audio").sizeBytes)
        }
    }

    @Test fun everyItemPointsAtTheVideoPageAndVideosCarryAFormatSelector() {
        runBlocking {
            val items = provider().resolve(url)
            assertTrue(items.all { it.downloadUrl == "https://www.youtube.com/watch?v=dQw4w9WgXcQ" })
            assertTrue(items.all { it.providerId == YouTubeProvider.ID })
            assertEquals(YouTubeFormats.videoSelector(1080), items[1].formatSelector)
            assertNull(items.last().formatSelector)
        }
    }

    @Test fun onlyTallerThan1080pStillPicksOneDefault() {
        runBlocking {
            val info = StreamInfo(
                "dQw4w9WgXcQ", "T", null, null, null,
                listOf(StreamFormat("1", 2160, true, false, "webm", null, 30), StreamFormat("2", 1440, true, false, "webm", null, 30)),
            )
            val items = provider(FakeExtractor { info }).resolve(url)
            assertEquals(listOf("dQw4w9WgXcQ_2160p" to false, "dQw4w9WgXcQ_1440p" to true), items.filter { it.type == MediaType.VIDEO }.map { it.id to it.preselected })
        }
    }

    @Test fun titleWithNothingUsableFallsBackToTheVideoId() {
        runBlocking {
            val info = StreamInfo(
                "dQw4w9WgXcQ", "???", null, null, null,
                listOf(StreamFormat("140", null, false, true, "m4a", 1000, null)),
            )
            val items = provider(FakeExtractor { info }).resolve(url)
            assertEquals(listOf("dQw4w9WgXcQ.mp3"), items.map { it.originalName })
        }
    }

    @Test fun noFormatsAtAllIsUnsupported() {
        val extractor = FakeExtractor { StreamInfo("dQw4w9WgXcQ", "T", null, null, null, emptyList()) }
        assertEquals(ProviderException.Reason.UNSUPPORTED, expectError { provider(extractor).resolve(url) }.reason)
    }

    @Test fun offersAudioOnlyWhenExtractorConfirmsAnAudioTrack() = runBlocking {
        val videoOnly = StreamInfo("dQw4w9WgXcQ", "Artist - Song", "Artist", null, null,
            listOf(StreamFormat("137", 1080, true, false, "mp4", null, null)))
        assertEquals(1, provider(FakeExtractor { videoOnly }).resolve(url).size)
        val muxed = videoOnly.copy(formats = listOf(StreamFormat("18", 360, true, true, "mp4", null, null)))
        val audio = provider(FakeExtractor { muxed }).resolve(url).last()
        assertEquals(MediaType.AUDIO, audio.type)
        assertEquals("Artist", audio.audioArtist)
        assertEquals("Song", audio.audioTitle)
        val official = provider(FakeExtractor { muxed.copy(title = "LITTLE BIG - GO BANANAS (Official Music Video)",
            uploader = "Little Big") }).resolve(url).last()
        assertEquals("LITTLE BIG", official.audioArtist)
        assertEquals("GO BANANAS", official.audioTitle)
        assertEquals(null, provider(FakeExtractor { muxed.copy(uploader = "Unrelated channel") })
            .resolve(url).last().audioArtist)
    }

    @Test fun extractorErrorsBecomeProviderErrors() {
        val unavailable = FakeExtractor { throw ExtractionException("Private video", ExtractionException.Kind.UNAVAILABLE) }
        assertEquals(ProviderException.Reason.UNSUPPORTED, expectError { provider(unavailable).resolve(url) }.reason)

        val failed = FakeExtractor { throw ExtractionException("network") }
        assertEquals(ProviderException.Reason.TEMPORARY_FAILURE, expectError { provider(failed).resolve(url) }.reason)

        val crashed = FakeExtractor { throw IllegalStateException("boom") }
        assertEquals(ProviderException.Reason.TEMPORARY_FAILURE, expectError { provider(crashed).resolve(url) }.reason)
    }

    @Test fun resolverDoesNotAddAnotherAudioTrackToYouTube() {
        runBlocking {
            val items = DefaultMediaResolver(listOf(provider())).resolve(url)
            assertEquals(1, items.count { it.type == MediaType.AUDIO })
        }
    }

    @Test fun videoSelectorsAreExactHeightWithFallbacks() {
        assertEquals(
            "bv*[height=720][vcodec^=avc1]+ba[ext=m4a]/bv*[height=720]+ba/b[height=720]",
            YouTubeFormats.videoSelector(720),
        )
        assertTrue(YouTubeFormats.AUDIO_SELECTOR.startsWith("bestaudio[ext=m4a]"))
        assertTrue(YouTubeFormats.AUDIO_SELECTOR.endsWith("/best"))
    }
}
