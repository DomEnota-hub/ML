package com.domenota.medialoader.core.provider

import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.core.model.PreferredVideoQuality
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GapiVkClientTest {
    private val page = "https://vkvideo.ru/video-122033519_456246913"
    private fun response(heights: List<Int> = listOf(144, 240, 360, 480, 720, 1080)) = JSONObject()
        .put("platform", "vk").put("type", "video").put("title", "Я тебя отыщу")
        .put("author", JSONObject.NULL).put("duration", JSONObject.NULL)
        .put("thumbnail", "https://sun9-1.userapi.com/cover.jpg")
        .put("media", JSONArray().apply { heights.forEach { height ->
            put(JSONObject().put("type", "video").put("quality", "${height}p").put("ext", "mp4")
                .put("url", "https://vk6-11.vkuser.net/movie.${height}.mp4?signature=example"))
        } })
    private fun client(root: JSONObject = response()) = GapiVkClient { _, _ -> GapiResponse(200, root.toString()) }

    @Test fun documentedQualitiesRemainAlternativesWithOneMp3AndPageHistory() = runBlocking {
        val items = GapiVkClient { path, key ->
            assertEquals("/v2/vk?url=https%3A%2F%2Fvkvideo.ru%2Fvideo-122033519_456246913", path)
            assertEquals("fake-key", key)
            GapiResponse(200, response().toString())
        }.resolve(page, "fake-key")
        val videos = items.filter { it.type == MediaType.VIDEO }
        assertEquals(listOf("1080p", "720p", "480p", "360p", "240p", "144p"), videos.map { it.qualityLabel })
        assertEquals(listOf("1080p"), videos.filter { it.preselected }.map { it.qualityLabel })
        assertTrue(videos.all { it.group == VkProvider.VIDEO_GROUP && it.position == null && it.formatSelector == null })
        assertTrue(items.all { it.sourcePageUrl == page && it.providerId == "vk" && VkDirectMedia.isSafeUrl(it.downloadUrl) })
        assertEquals("Я тебя отыщу_1080p.mp4", videos.first().originalName)
        val mp3 = items.single { it.type == MediaType.AUDIO }
        assertEquals("Я тебя отыщу.mp3", mp3.originalName)
        assertTrue(mp3.downloadUrl.contains("144.mp4"))
        assertFalse(mp3.preselected)
        val preferred = PreferredVideoQuality.apply(items, 720)
        assertEquals(listOf("720p"), preferred.filter { it.preselected }.map { it.qualityLabel })
    }

    @Test fun defaultHeightStaysAt1080AndMissingTitleUsesStableVideoId() = runBlocking {
        val root = response(listOf(2160, 360, 1080, 1080)).put("title", JSONObject.NULL)
        val items = client(root).resolve(page, "fake-key")
        assertEquals(3, items.count { it.type == MediaType.VIDEO })
        assertEquals("1080p", items.single { it.preselected }.qualityLabel)
        assertEquals("-122033519_456246913_2160p.mp4", items.first().originalName)
        assertEquals(items.map { it.id }.distinct().size, items.size)
    }

    @Test fun clipsAndWrappedLinksUseCanonicalVideoWithoutAlbumPositions() = runBlocking {
        val clip = "https://m.vk.com/clips?z=clip-12_345%2Ffeed"
        val items = GapiVkClient { path, _ ->
            assertEquals("/v2/vk?url=https%3A%2F%2Fm.vk.com%2Fclip-12_345", path)
            GapiResponse(200, response(listOf(720)).toString())
        }.resolve(clip, "fake-key")
        assertEquals("-12_345_720p", items.first().id)
        assertEquals("https://m.vk.com/clip-12_345", items.first().sourcePageUrl)
        assertTrue(items.all { it.position == null })
    }

    @Test fun unsupportedSourcesNeverSpendAnApiCall() = runBlocking {
        var calls = 0
        val api = GapiVkClient { _, _ -> calls++; GapiResponse(200, response().toString()) }
        for (url in listOf("https://vk.com/wall-1_2", "https://vk.com/album-1_2", "https://vk.com/videos-1",
            "https://vk.com/video_ext.php?oid=-1&id=2", "https://evil.test/video-1_2", "https://vk.com:8443/video-1_2")) {
            assertEquals(GapiFailureKind.UNSUPPORTED, failure { api.resolve(url, "fake-key") }.kind)
        }
        assertEquals(0, calls)
    }

    @Test fun malformedOrPartlyUnsafeResponseFallsBackAsAWhole() = runBlocking {
        for (root in listOf(response().put("platform", "instagram"), response().put("type", "album"),
            response().put("media", JSONArray()),
            response().apply { getJSONArray("media").getJSONObject(2).put("quality", "unknown") },
            response().apply { getJSONArray("media").getJSONObject(2).put("url", "https://evil.test/movie.mp4") },
            response().apply { getJSONArray("media").getJSONObject(2).put("ext", "m3u8") })) {
            assertEquals(GapiFailureKind.UNSUPPORTED, failure { client(root).resolve(page, "fake-key") }.kind)
        }
    }

    @Test fun directMediaAllowsOnlyHttpsVkCdnWithoutCredentialsOrUnusualPorts() {
        for (url in listOf("https://vk6-11.vkuser.net/movie.mp4", "https://sun9-1.userapi.com/cover.jpg")) {
            assertTrue(url, VkDirectMedia.isSafeUrl(url))
        }
        for (url in listOf("http://vk6-11.vkuser.net/movie.mp4", "https://vkuser.net.evil.test/movie.mp4",
            "https://secret@vk6-11.vkuser.net/movie.mp4", "https://vk6-11.vkuser.net:444/movie.mp4",
            "https://vk.com/video-1_2", "https://127.0.0.1/movie.mp4", "file:///tmp/movie.mp4")) {
            assertFalse(url, VkDirectMedia.isSafeUrl(url))
        }
    }

    @Test fun responseErrorsAndTransportFailuresNeverExposeCredentials() = runBlocking {
        for ((status, body, kind) in listOf(
            Triple(401, "secret-key", GapiFailureKind.INVALID_KEY),
            Triple(429, """{"detail":"Monthly token balance exhausted"}""", GapiFailureKind.QUOTA_EXHAUSTED),
            Triple(429, """{"detail":"Too many requests"}""", GapiFailureKind.RATE_LIMITED),
            Triple(404, "secret-key", GapiFailureKind.NOT_FOUND),
            Triple(502, "secret-key", GapiFailureKind.TEMPORARY))) {
            val error = failure { GapiVkClient { _, _ -> GapiResponse(status, body, 2000) }.resolve(page, "secret-key") }
            assertEquals(kind, error.kind)
            assertEquals(2000L, error.retryAfterMillis)
            assertFalse(error.toString().contains("secret-key"))
        }
        val error = failure { GapiVkClient { _, _ -> throw IOException("secret-key") }.resolve(page, "secret-key") }
        assertEquals(GapiFailureKind.TEMPORARY, error.kind)
        assertNull(error.cause)
    }

    @Test fun cancellationNeverBecomesFallbackFailure() = runBlocking {
        val cancelled = CancellationException()
        try { GapiVkClient { _, _ -> throw cancelled }.resolve(page, "fake-key"); fail("Expected cancellation") }
        catch (e: CancellationException) { assertSame(cancelled, e) }
    }

    private suspend fun failure(block: suspend () -> Any): GapiException {
        try { block() } catch (e: GapiException) { return e }
        throw AssertionError("Expected GapiException")
    }
}
