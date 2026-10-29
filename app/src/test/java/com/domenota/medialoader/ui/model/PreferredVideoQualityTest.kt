package com.domenota.medialoader.ui.model

import com.domenota.medialoader.core.model.*
import org.junit.Assert.assertEquals
import org.junit.Test

class PreferredVideoQualityTest {
    private fun variants(provider: String = "youtube", page: String = "https://example.org/video") =
        listOf(2160, 1080, 720, 360).map { height -> MediaItem("${provider}_${page}_$height", provider,
            MediaType.VIDEO, "$height.mp4", page, qualityLabel = "${height}p60", group = "video", preselected = height == 1080) }
    private fun chosen(items: List<MediaItem>, limit: Int?) = PreferredVideoQuality.apply(items, limit).filter { it.preselected }
    @Test fun exactAndClosestLowerAndMinimumFallbackAreChosenForEverySupportedProvider() {
        for (provider in listOf("youtube", "vk", "rutube")) {
            val items = variants(provider)
            assertEquals("720p60", chosen(items, 720).single().qualityLabel)
            assertEquals("360p60", chosen(items, 480).single().qualityLabel)
            assertEquals("360p60", chosen(items, 144).single().qualityLabel)
            assertEquals("2160p60", chosen(items, null).single().qualityLabel)
        }
    }
    @Test fun separateVideosGetTheirOwnChoiceWhileInstagramAndAudioRemainUnchanged() {
        val instagram = variants("instagram").map { it.copy(id = it.id.replace("instagram", "youtube")) }
        val audio = MediaItem("audio", "youtube", MediaType.AUDIO, "a.mp3", "https://example.org/a", preselected = false)
        val videos = variants() + variants(page = "https://example.org/second")
        val result = PreferredVideoQuality.apply(videos + instagram + audio, 720)
        assertEquals(2, result.count { it.providerId == "youtube" && it.preselected })
        assertEquals(instagram, result.filter { it.providerId == "instagram" })
        assertEquals(audio, result.last())
    }
    @Test fun missingHeightsKeepTheProviderChoiceAndAudioOnlyIsUnchanged() {
        val unknown = variants().map { it.copy(qualityLabel = "Лучшее") }
        assertEquals(unknown, PreferredVideoQuality.apply(unknown, 480))
        val audio = listOf(MediaItem("a", "vk", MediaType.AUDIO, "a.mp3", "https://example.org/a"))
        assertEquals(audio, PreferredVideoQuality.apply(audio, 720))
    }
}
