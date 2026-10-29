package com.domenota.medialoader.data.vkcommunity

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class VkWallParserTest {
    @Test fun collectsMultiplePhotosAndRepostAtLargestResolution() {
        val json = JSONObject("""{"count":101,"items":[{"id":1,"owner_id":-7,"date":1792713600,
          "attachments":[{"type":"photo","photo":{"id":2,"owner_id":-7,"sizes":[
            {"width":100,"height":100,"url":"https://x.test/small.jpg"},
            {"width":800,"height":600,"url":"https://x.test/large.jpg"}]}},
            {"type":"video","video":{"id":9}}],
          "copy_history":[{"id":9,"owner_id":-8,"date":1792713600,"attachments":[
            {"type":"photo","photo":{"id":3,"owner_id":-8,"sizes":[
              {"width":600,"height":600,"url":"https://x.test/repost.jpg"}]}}]}]}]}""")
        val period = VkDatePeriod.of(LocalDate.of(2026, 10, 22), LocalDate.of(2026, 10, 23))
        val page = VkWallParser.parse(json, period, ZoneId.of("UTC"))
        assertEquals(2, page.images.size)
        assertEquals("https://x.test/large.jpg", page.images.first().url)
        assertEquals("-8:3", page.images.last().photoKey)
        assertEquals(1, page.scannedPosts)
    }

    @Test fun olderPageCanStopButPinnedPostAloneCannot() {
        val period = VkDatePeriod.of(LocalDate.of(2026, 10, 22), LocalDate.of(2026, 10, 23))
        val old = 1_700_000_000
        val pinned = JSONObject("""{"count":500,"items":[{"id":1,"owner_id":-7,"date":$old,"is_pinned":1}]}""")
        assertTrue(!VkWallParser.parse(pinned, period, ZoneId.of("UTC")).pastPeriod)
        val normal = JSONObject("""{"count":500,"items":[{"id":1,"owner_id":-7,"date":$old}]}""")
        assertTrue(VkWallParser.parse(normal, period, ZoneId.of("UTC")).pastPeriod)
    }

    @Test fun explicitAdvertisingDoesNotEnterImageResults() {
        val response = JSONObject("""{"items":[{"id":7,"owner_id":-5,"date":1792713600,
          "marked_as_ads":1,"attachments":[
          {"type":"photo","photo":{"id":1,"owner_id":-5,"sizes":[{"width":50,"height":50,"url":"https://x.test/a.jpg"}]}},
          {"type":"photo","photo":{"id":2,"owner_id":-5,"sizes":[{"width":50,"height":50,"url":"https://x.test/b.jpg"}]}}
          ]}]}""")
        val page = VkWallParser.parse(response,
            VkDatePeriod.of(LocalDate.of(2026, 10, 22), LocalDate.of(2026, 10, 23)),
            ZoneId.of("UTC"))
        assertTrue(page.images.isEmpty())
    }

    @Test fun suspectedAdvertisingIsLabeledButNotConfirmed() {
        val response = JSONObject("""{"items":[{"id":8,"owner_id":-5,"date":1792713600,
          "text":"Реклама. Используйте промокод", "attachments":[
          {"type":"photo","photo":{"id":3,"owner_id":-5,"sizes":[{"width":50,"height":50,"url":"https://x.test/c.jpg"}]}}
          ]}]}""")
        val page = VkWallParser.parse(response,
            VkDatePeriod.of(LocalDate.of(2026, 10, 22), LocalDate.of(2026, 10, 23)),
            ZoneId.of("UTC"))
        assertEquals(VkAdStatus.POSSIBLE, page.images.single().adStatus)
    }

    @Test fun ordinaryPostHasNoAdvertisingMark() {
        val response = JSONObject("""{"items":[{"id":9,"owner_id":-5,"date":1792713600,
          "text":"Доброе утро", "attachments":[
          {"type":"photo","photo":{"id":4,"owner_id":-5,"sizes":[{"width":50,"height":50,"url":"https://x.test/d.jpg"}]}}
          ]}]}""")
        val page = VkWallParser.parse(response,
            VkDatePeriod.of(LocalDate.of(2026, 10, 22), LocalDate.of(2026, 10, 23)),
            ZoneId.of("UTC"))
        assertEquals(VkAdStatus.NONE, page.images.single().adStatus)
    }

    @Test fun incrementalWindowSkipsReviewedAndDuringScanPosts() {
        val response = JSONObject("""{"items":[
            {"id":1,"owner_id":-7,"date":100,"attachments":[{"type":"photo","photo":{"id":1,"owner_id":-7,"sizes":[{"width":10,"height":10,"url":"https://x.test/1"}]}}]},
            {"id":2,"owner_id":-7,"date":101,"attachments":[{"type":"photo","photo":{"id":2,"owner_id":-7,"sizes":[{"width":10,"height":10,"url":"https://x.test/2"}]}}]},
            {"id":3,"owner_id":-7,"date":102,"attachments":[{"type":"photo","photo":{"id":3,"owner_id":-7,"sizes":[{"width":10,"height":10,"url":"https://x.test/3"}]}}]}
        ]}""")
        val period = VkDatePeriod.of(LocalDate.of(1970, 1, 1), LocalDate.of(1970, 1, 1))
        val page = VkWallParser.parse(response, period, ZoneId.of("UTC"),
            afterEpochSecond = 100, throughEpochSecond = 101)
        assertEquals(listOf("-7:2"), page.images.map { it.photoKey })
        assertTrue(!page.pastPeriod)
    }

    @Test fun reviewedNormalPostsStopScanEvenWithPinnedPost() {
        val response = JSONObject("""{"count":500,"items":[
            {"id":9,"owner_id":-7,"date":200,"is_pinned":1},
            {"id":8,"owner_id":-7,"date":100},
            {"id":7,"owner_id":-7,"date":99}
        ]}""")
        val period = VkDatePeriod.of(LocalDate.of(1970, 1, 1), LocalDate.of(1970, 1, 1))
        val page = VkWallParser.parse(response, period, ZoneId.of("UTC"),
            afterEpochSecond = 100, throughEpochSecond = 199)
        assertTrue(page.pastPeriod)
        assertTrue(page.images.isEmpty())
    }
}
