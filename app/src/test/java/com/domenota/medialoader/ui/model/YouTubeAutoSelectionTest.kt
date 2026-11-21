package com.domenota.medialoader.ui.model

import com.domenota.medialoader.core.model.*
import com.domenota.medialoader.core.provider.YouTubeFormats
import org.junit.Assert.*
import org.junit.Test

class YouTubeAutoSelectionTest {
    private val url = "https://www.youtube.com/watch?v=abcdefghijk"
    private fun video(height: Int, chosen: Boolean = true) = MediaItem(
        "abcdefghijk_${height}p", "youtube", MediaType.VIDEO, "${height}.mp4", url,
        qualityLabel = "${height}p60", formatSelector = YouTubeFormats.videoSelector(height),
        group = "video-quality", preselected = chosen
    )
    @Test fun selectsSingleKnownVideoWithExistingExactMuxSelectorAndIgnoresAudio() {
        val chosen = video(720)
        val audio = MediaItem("abcdefghijk_audio", "youtube", MediaType.AUDIO, "song.mp3", url)
        assertEquals(chosen, YouTubeAutoSelection.singleVideo("https://youtu.be/abcdefghijk",
            listOf(chosen, video(1080, false), audio)))
    }
    @Test fun rejectsPlaylistAmbiguousAndUnknownOrForeignResults() {
        val item = video(720)
        assertNull(YouTubeAutoSelection.canonicalUrl("$url&list=PL123"))
        assertNull(YouTubeAutoSelection.canonicalUrl("$url&v=xyzabcdefgh"))
        assertNull(YouTubeAutoSelection.canonicalUrl("https://youtube.com.evil.test/watch?v=abcdefghijk"))
        assertNull(YouTubeAutoSelection.singleVideo(url, listOf(item, video(1080))))
        assertNull(YouTubeAutoSelection.singleVideo(url, listOf(item, item)))
        assertNull(YouTubeAutoSelection.singleVideo(url, listOf(item.copy(qualityLabel = "Лучшее"))))
        assertNull(YouTubeAutoSelection.singleVideo(url, listOf(item.copy(formatSelector = "best"))))
        assertNull(YouTubeAutoSelection.singleVideo(url, listOf(item.copy(providerId = "vk"))))
        assertNull(YouTubeAutoSelection.singleVideo(url, listOf(item.copy(id = "foreign_720p"))))
        assertNull(YouTubeAutoSelection.singleVideo(url, listOf(item.copy(downloadUrl = "https://youtu.be/zzzzzzzzzzz"))))
        assertNull(YouTubeAutoSelection.singleVideo(url, listOf(item.copy(type = MediaType.AUDIO))))
    }
}
