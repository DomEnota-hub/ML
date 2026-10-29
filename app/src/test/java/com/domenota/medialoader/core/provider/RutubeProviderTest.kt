package com.domenota.medialoader.core.provider

import com.domenota.medialoader.core.model.MediaType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RutubeProviderTest {
    private class FakeExtractor(private val result: suspend (String) -> StreamInfo) : StreamExtractor {
        val requested = mutableListOf<String>()
        override suspend fun extract(url: String): StreamInfo {
            requested += url
            return result(url)
        }
    }

    private val id = "3eac3b4561676c17df9132a9a1e62e3e"
    private val sampleInfo = StreamInfo(
        id = id,
        title = "RUTUBE test",
        uploader = "Author",
        durationSeconds = 42,
        thumbnailUrl = "https://pic.rutubelist.ru/video/thumb.jpg",
        formats = listOf(
            StreamFormat("1080", 1080, true, true, "mp4", 20_000_000, 30),
            StreamFormat("720", 720, true, true, "mp4", 10_000_000, 30),
            StreamFormat("audio", null, false, true, "m4a", 2_000_000, null),
        ),
    )

    @Test fun recognizesSingleVideoForms() {
        assertTrue(RutubeLinkParser.parse("https://rutube.ru/video/$id/") != null)
        assertTrue(RutubeLinkParser.parse("https://rutube.ru/live/video/$id/") != null)
        assertTrue(RutubeLinkParser.parse("https://rutube.ru/video/private/$id/?p=token") != null)
        assertTrue(RutubeLinkParser.parse("https://rutube.ru/play/embed/$id") != null)
        assertFalse(RutubeLinkParser.parse("https://rutube.ru/channel/12345/") != null)
        assertFalse(RutubeLinkParser.parse("https://youtube.com/watch?v=x") != null)
    }

    @Test fun keepsPrivateAccessTokenAndExposesFormats() = runBlocking {
        val extractor = FakeExtractor { sampleInfo }
        val url = "https://rutube.ru/video/private/$id/?p=token#ignored"
        val items = RutubeProvider(extractor).resolve(url)
        assertEquals(listOf("${id}_1080p", "${id}_720p", "${id}_audio"), items.map { it.id })
        assertEquals(listOf(MediaType.VIDEO, MediaType.VIDEO, MediaType.AUDIO), items.map { it.type })
        assertTrue(items.all { it.providerId == RutubeProvider.ID })
        assertTrue(items.all { it.previewUrl == sampleInfo.thumbnailUrl })
        assertEquals(listOf(true, false, false), items.map { it.preselected })
        assertEquals("RUTUBE test.mp3", items.last().originalName)
        assertEquals("MP3 · 192 kbps", items.last().qualityLabel)
        assertEquals("https://rutube.ru/video/private/$id/?p=token", extractor.requested.single())
    }

    @Test fun authErrorBecomesAccessRequired() {
        val extractor = FakeExtractor {
            throw ExtractionException("RUTUBE requires sign in", ExtractionException.Kind.AUTH_REQUIRED)
        }
        val error = try {
            runBlocking { RutubeProvider(extractor).resolve("https://rutube.ru/video/$id/") }
            throw AssertionError("Expected ProviderException")
        } catch (error: ProviderException) {
            error
        }
        assertEquals(ProviderException.Reason.ACCESS_REQUIRED, error.reason)
        assertEquals(RutubeProvider.ID, error.providerId)
    }
}
