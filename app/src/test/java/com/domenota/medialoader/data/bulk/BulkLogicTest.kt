package com.domenota.medialoader.data.bulk

import com.domenota.medialoader.core.model.MediaItem
import com.domenota.medialoader.core.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BulkLogicTest {
    @Test fun `Bufer tracking parameters do not duplicate a post`() {
        val input = """
            https://www.instagram.com/reel/DcRUeqaRvZp/?vrfl=abc
            note https://www.instagram.com/reel/DcRUeqaRvZp/?cplk=def #tag
            https://www.youtube.com/watch?v=UhhfcKk00u0
        """.trimIndent()
        val parsed = BulkLinks.parse(input)
        assertEquals(2, parsed.urls.size)
        assertEquals("https://www.instagram.com/reel/DcRUeqaRvZp/", parsed.urls[0])
    }

    @Test fun `rejects unsupported links and over limit`() {
        val parsed = BulkLinks.parse("https://example.com/file.mp4 https://youtu.be/UhhfcKk00u0")
        assertEquals(1, parsed.urls.size)
        assertEquals(1, parsed.unsupported)
        assertTrue(runCatching { BulkLinks.parse("x".repeat(1_048_577)) }.isFailure)
    }

    @Test fun `one quality is selected and audio follows rules`() {
        fun video(id: String, quality: String) = MediaItem(id, "youtube", MediaType.VIDEO,
            "$id.mp4", "https://www.youtube.com/watch?v=UhhfcKk00u0", qualityLabel = quality,
            group = "video")
        val items = listOf(video("a", "720p"), video("b", "1080p"), video("c", "1440p"),
            MediaItem("audio", "youtube", MediaType.AUDIO, "a.mp3", "https://www.youtube.com/watch?v=UhhfcKk00u0"))
        val selected = BulkSelection.select(items, BulkRules(maxHeight = 1080))
        assertEquals(listOf("b"), selected.map { it.id })
        assertTrue(BulkSelection.select(items, BulkRules(maxHeight = 720, audio = true)).any { it.id == "audio" })
        assertFalse(BulkSelection.select(items, BulkRules(video = false)).any { it.type == MediaType.VIDEO })
    }

    @Test fun quality480AcceptsOnlyProvenHeights() {
        fun variant(id: String, quality: String?) = MediaItem(
            id, "youtube", MediaType.VIDEO, "$id.mp4",
            "https://www.youtube.com/watch?v=UhhfcKk00u0",
            qualityLabel = quality, group = "same-video",
        )
        val options = listOf(variant("low", "360p"), variant("ideal", "480p"),
            variant("high", "720p"), variant("unknown", null))
        val result = BulkSelection.withReport(options, BulkRules(maxHeight = 480))
        assertEquals(listOf("ideal"), result.items.map { it.id })
        assertEquals(0, result.videosWithoutSuitableQuality)
        assertEquals(480, BulkSelection.knownHeight("640x480 mp4"))
        assertEquals(1080, BulkSelection.knownHeight("1920×1080"))
    }

    @Test fun strictCeilingSkipsHigherVideosAndUnknownQualities() {
        fun variant(id: String, quality: String?) = MediaItem(
            id, "youtube", MediaType.VIDEO, "$id.mp4",
            "https://www.youtube.com/watch?v=UhhfcKk00u0",
            qualityLabel = quality, group = "same-video",
        )
        val highOnly = BulkSelection.withReport(
            listOf(variant("high", "1080p")), BulkRules(maxHeight = 480))
        assertTrue(highOnly.items.isEmpty())
        assertEquals(1, highOnly.videosWithoutSuitableQuality)
        assertEquals(0, highOnly.videosWithoutKnownQuality)
        val unknownOnly = BulkSelection.withReport(
            listOf(variant("unknown", null)), BulkRules(maxHeight = 480))
        assertTrue(unknownOnly.items.isEmpty())
        assertEquals(1, unknownOnly.videosWithoutKnownQuality)
        assertEquals("unknown", BulkSelection.select(
            listOf(variant("unknown", null)), BulkRules(maxHeight = null)).single().id)
    }

    @Test fun instagramVideoWithUnknownQualityIsNeverDroppedByForeignCeiling() {
        val reel = MediaItem(
            "reel_1", "instagram", MediaType.VIDEO, "reel.mp4",
            "https://scontent.fbcdn.net/video.mp4", sourceGroupId = "reel",
            qualityLabel = null)
        for (limit in listOf(480, 720, 1080, 2160)) {
            val report = BulkSelection.withReport(listOf(reel), BulkRules(maxHeight = limit))
            assertEquals(listOf("reel_1"), report.items.map { it.id })
            assertEquals(0, report.videosWithoutSuitableQuality)
            assertEquals(0, report.videosWithoutKnownQuality)
        }
    }

    @Test fun instagramHigherResolutionWinsEvenIfSharedCeilingIs480() {
        val low = MediaItem("post", "instagram", MediaType.VIDEO, "low.mp4",
            "https://scontent.fbcdn.net/low.mp4", qualityLabel = "640×480")
        val portrait = low.copy(downloadUrl = "https://scontent.fbcdn.net/high.mp4",
            qualityLabel = "1080×1920")
        val result = BulkSelection.withReport(
            listOf(low, portrait), BulkRules(maxHeight = 480))
        assertEquals(1, result.items.size)
        assertEquals(portrait.downloadUrl, result.items.single().downloadUrl)
        assertEquals(0, result.videosWithoutSuitableQuality)
    }

    @Test fun mixedSourcesKeepStrictRulesForNonInstagramVideo() {
        val reel = MediaItem("reel", "instagram", MediaType.VIDEO, "reel.mp4",
            "https://scontent.fbcdn.net/reel.mp4")
        val youtube = MediaItem("yt", "youtube", MediaType.VIDEO, "yt.mp4",
            "https://youtube.com/watch?v=AbcdEf01234", qualityLabel = "1080p")
        val report = BulkSelection.withReport(
            listOf(reel, youtube), BulkRules(maxHeight = 720))
        assertEquals(listOf("reel"), report.items.map { it.id })
        assertEquals(1, report.videosWithoutSuitableQuality)
        assertEquals(0, report.videosWithoutKnownQuality)
    }

    @Test fun qualityGroupingPreservesInstagramCarouselItems() {
        val posts = listOf(1, 2).map { pos ->
            MediaItem("part_$pos", "instagram", MediaType.VIDEO, "part_$pos.mp4",
                "https://cdn.instagram.com/video$pos", sourceGroupId = "carousel",
                position = pos, qualityLabel = "480p")
        }
        assertEquals(2, BulkSelection.select(posts, BulkRules(maxHeight = 480)).size)
    }

    @Test fun rulesForbidBlankSourcesOrBlankMediaTypes() {
        assertFalse(BulkRules(instagram = false, youtube = false, vk = false, rutube = false).isUsable())
        assertFalse(BulkRules(photo = false, video = false, audio = false).isUsable())
        assertTrue(BulkRules(maxHeight = 480).isUsable())
    }

    @Test fun `carousel audio keeps every video track`() {
        val items = (1..2).map { position ->
            MediaItem("post_${position}_audio", "instagram", MediaType.AUDIO,
                "track$position.mp3", "https://cdn.instagram.com/$position",
                sourceGroupId = "post", position = position)
        }
        assertEquals(2, BulkSelection.select(items, BulkRules(audio = true)).size)
    }

    @Test fun `Instagram identity survives changing CDN links`() {
        val item = MediaItem("post_1", "instagram", MediaType.VIDEO, "clip.mp4",
            "https://cdn.instagram.com/first", sourceGroupId = "post", position = 1)
        assertEquals(BulkIdentity.key(item),
            BulkIdentity.key(item.copy(downloadUrl = "https://cdn.instagram.com/second")))
    }

    @Test fun `Instagram cooldown survives elapsed time and restart timestamp`() {
        assertEquals(0L, BulkPacing.waitMillis(0L, 1_000L))
        assertEquals(15_000L, BulkPacing.waitMillis(1_000L, 6_000L))
        assertEquals(0L, BulkPacing.waitMillis(1_000L, 21_000L))
        assertEquals(0L, BulkPacing.waitMillis(1_000L, 1_002L, disableAnalysisPause = true))
        assertEquals(15_000L, BulkPacing.waitMillis(1_000L, 6_000L, disableAnalysisPause = false))
        assertFalse(BulkRules().disableAnalysisPause)
    }

    @Test fun `selection action changes every preview candidate`() {
        val candidates = listOf(
            BulkCandidate("first", MediaItem("first", "instagram", MediaType.PHOTO, "a.jpg", "https://a")),
            BulkCandidate("second", MediaItem("second", "instagram", MediaType.PHOTO, "b.jpg", "https://b"), selected = false),
        )
        assertTrue(BulkCandidateSelection.setAll(candidates, true).all { it.selected })
        assertTrue(BulkCandidateSelection.setAll(candidates, false).none { it.selected })
    }

    @Test fun `bulk files from one source share a download group`() {
        val first = MediaItem("post_1", "instagram", MediaType.PHOTO, "a.jpg", "https://cdn/a",
            sourcePageUrl = "https://instagram.com/p/post/")
        val second = first.copy(id = "post_2", downloadUrl = "https://cdn/b")
        assertEquals(BulkDownloadGroup.id("job", first), BulkDownloadGroup.id("job", second))
    }
}
