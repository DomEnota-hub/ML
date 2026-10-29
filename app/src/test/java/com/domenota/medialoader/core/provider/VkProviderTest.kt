package com.domenota.medialoader.core.provider

import com.domenota.medialoader.core.model.MediaType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VkProviderTest {
    private class FakeExtractor(private val result: suspend (String) -> StreamInfo) : StreamExtractor {
        val requested = mutableListOf<String>()
        override suspend fun extract(url: String): StreamInfo {
            requested += url
            return result(url)
        }
    }

    private val sampleInfo = StreamInfo(
        id = "-123_456",
        title = "VK test clip",
        uploader = "Author",
        durationSeconds = 42,
        thumbnailUrl = "https://sun.example/vk-thumb.jpg",
        formats = listOf(
            StreamFormat("1080", 1080, true, true, "mp4", 20_000_000, 30),
            StreamFormat("720", 720, true, true, "mp4", 10_000_000, 30),
            StreamFormat("audio", null, false, true, "m4a", 2_000_000, null),
        ),
    )

    @Test fun recognizesVkVideoAndClips() {
        assertTrue(VkLinkParser.parse("https://vk.com/video-123_456") != null)
        assertTrue(VkLinkParser.parse("https://vkvideo.ru/clip-123_456") != null)
        assertTrue(VkLinkParser.parse("https://vk.com/video_ext.php?oid=-123&id=456&hash=x") != null)
        assertTrue(VkLinkParser.parse("https://vk.com/video?z=video-123_456") != null)
        assertFalse(VkLinkParser.parse("https://vk.com/feed") != null)
        assertFalse(VkLinkParser.parse("https://youtube.com/watch?v=x") != null)
    }

    @Test fun normalizesMobileSearchClipWithoutTrackingParameters() {
        val url = "https://m.vk.ru/clips/search/Little%20big/feed/clip-214271600_456239031?search_track_code=very-long-private-value&from=search"
        assertEquals("https://m.vk.ru/clip-214271600_456239031", VkLinkParser.parse(url)?.canonicalUrl())
        assertEquals("https://m.vk.ru/clip263710839_456239838", VkLinkParser.parse(
            "https://m.vk.ru/clips/search/query/feed/clip263710839_456239838",
        )?.canonicalUrl())
    }

    @Test fun wallPostsNeedExplicitVideoAttachment() {
        assertEquals(null, VkLinkParser.parse("https://vk.com/wall-123_456"))
        assertEquals(null, VkLinkParser.parse("https://vk.com/wall-123_456?w=wall-123_456"))
        assertEquals("https://vk.com/video-123_789", VkLinkParser.parse(
            "https://vk.com/wall-123_456?z=video-123_789%2Fabc&from=feed",
        )?.canonicalUrl())
    }

    @Test fun exposesVideoQualitiesAudioAndThumbnail() = runBlocking {
        val extractor = FakeExtractor { sampleInfo }
        val items = VkProvider(extractor).resolve("https://vkvideo.ru/video-123_456#fragment")
        assertEquals(listOf("-123_456_1080p", "-123_456_720p", "-123_456_audio"), items.map { it.id })
        assertEquals(listOf(MediaType.VIDEO, MediaType.VIDEO, MediaType.AUDIO), items.map { it.type })
        assertTrue(items.all { it.providerId == VkProvider.ID })
        assertTrue(items.all { it.previewUrl == sampleInfo.thumbnailUrl })
        assertEquals(listOf(true, false, false), items.map { it.preselected })
        assertEquals("VK test clip.mp3", items.last().originalName)
        assertEquals("MP3 · 192 kbps", items.last().qualityLabel)
        assertEquals("https://vkvideo.ru/video-123_456", extractor.requested.single())
    }

    @Test fun authErrorBecomesAccessRequired() {
        val extractor = FakeExtractor { throw ExtractionException("VK requires sign in", ExtractionException.Kind.AUTH_REQUIRED) }
        val error = try {
            runBlocking { VkProvider(extractor).resolve("https://vk.com/video-123_456") }
            throw AssertionError("Expected ProviderException")
        } catch (error: ProviderException) {
            error
        }
        assertEquals(ProviderException.Reason.ACCESS_REQUIRED, error.reason)
        assertEquals(VkProvider.ID, error.providerId)
    }
}
