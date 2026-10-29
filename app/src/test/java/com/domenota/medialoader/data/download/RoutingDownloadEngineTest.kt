package com.domenota.medialoader.data.download

import com.domenota.medialoader.core.model.DownloadTask
import com.domenota.medialoader.core.model.MediaItem
import com.domenota.medialoader.core.model.MediaType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class RoutingDownloadEngineTest {
    @Test fun providersKeepTheirDedicatedDownloadPaths() = runBlocking {
        val calls = mutableListOf<String>()
        fun engine(name: String) = object : DownloadEngine {
            override suspend fun download(
                task: DownloadTask,
                onProgress: suspend (Long, Long?) -> Unit,
            ): DownloadResult {
                calls += name
                return DownloadResult("content://saved/$name", 1)
            }
        }

        val routing = RoutingDownloadEngine(
            media = engine("media"), audio = engine("audio"),
            youtube = engine("youtube"), vk = engine("vk"),
            rutube = engine("rutube"), hitmoz = engine("hitmoz"), muzmo = engine("muzmo"),
        )
        suspend fun route(provider: String, type: MediaType) {
            val item = MediaItem("test", provider, type, "file.mp4", "https://example.com/file")
            routing.download(DownloadTask("test", item, "file.mp4")) { _, _ -> }
        }

        route("instagram", MediaType.VIDEO)
        route("instagram", MediaType.AUDIO)
        route("youtube", MediaType.VIDEO)
        route("vk", MediaType.AUDIO)
        route("rutube", MediaType.VIDEO)
        route("hitmoz", MediaType.AUDIO)
        route("muzmo", MediaType.AUDIO)

        assertEquals(listOf("media", "audio", "youtube", "vk", "rutube", "hitmoz", "muzmo"), calls)
    }
}
