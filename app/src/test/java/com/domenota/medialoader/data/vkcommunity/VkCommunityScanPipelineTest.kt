package com.domenota.medialoader.data.vkcommunity

import java.time.LocalDate
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic wall responses exercise the same parser/cursor sequence used by both workers. */
class VkCommunityScanPipelineTest {
    private val zone = ZoneId.of("UTC")
    private val period = VkDatePeriod.of(LocalDate.of(2026, 10, 22), LocalDate.of(2026, 10, 23))
    private val inPeriod = period.startEpochSecond(zone) + 60

    @Test fun scansBeyondOneThousandImagesAndDoesNotStopAtAnOldPinnedPost() {
        val posts = listOf(post(-7, 999, period.startEpochSecond(zone) - 60, 1)
            .put("is_pinned", 1)) + (1..192).map { index ->
            post(-7, index, inPeriod, if (index == 192) 3 else 10)
        }
        var cursor = VkScanCursor(0, 0, false)
        val images = mutableListOf<VkImageCandidate>()
        posts.chunked(100).forEachIndexed { pageIndex, items ->
            assertFalse(cursor.done)
            assertEquals(pageIndex * 100, cursor.offset)
            val page = VkWallParser.parse(response(posts.size, items), period, zone)
            images += page.images
            cursor = cursor.advance(page)
        }
        assertTrue(cursor.done)
        assertEquals(193, cursor.offset)
        assertEquals(1_913, images.size)
        assertEquals(1_913, images.map { it.photoKey }.distinct().size)
        assertTrue(images.all { it.postKey.startsWith("-7:") })
    }

    @Test fun pageWithoutPhotosStillAdvancesAndFindsPhotosOnFollowingPage() {
        val first = response(101, (1..100).map { post(-7, it, inPeriod, 0) })
        val parsedFirst = VkWallParser.parse(first, period, zone)
        var cursor = VkScanCursor(0, 0, false).advance(parsedFirst)
        assertTrue(parsedFirst.images.isEmpty())
        assertEquals(100, cursor.offset)
        assertFalse(cursor.done)

        val second = VkWallParser.parse(response(101, listOf(post(-7, 101, inPeriod, 2))), period, zone)
        cursor = cursor.advance(second)
        assertEquals(2, second.images.size)
        assertTrue(cursor.done)
    }

    @Test fun sameDatesDoNotSharePhotoKeysOrCountsBetweenCommunities() {
        val first = VkWallParser.parse(response(1, listOf(post(-7, 1, inPeriod, 2))), period, zone)
        val second = VkWallParser.parse(response(1, listOf(post(-8, 1, inPeriod, 5))), period, zone)
        assertEquals(2, first.images.size)
        assertEquals(5, second.images.size)
        assertTrue(first.images.map { it.photoKey }.toSet()
            .intersect(second.images.map { it.photoKey }.toSet()).isEmpty())
    }

    private fun response(total: Int, posts: List<JSONObject>) = JSONObject()
        .put("count", total).put("items", JSONArray(posts))

    private fun post(owner: Long, id: Int, date: Long, photos: Int): JSONObject = JSONObject()
        .put("owner_id", owner).put("id", id).put("date", date)
        .put("attachments", JSONArray((1..photos).map { index ->
            JSONObject().put("type", "photo").put("photo", JSONObject()
                .put("owner_id", owner).put("id", id * 100 + index)
                .put("sizes", JSONArray().put(JSONObject()
                    .put("width", 100).put("height", 100)
                    .put("url", "https://x.test/$owner/$id/$index.jpg"))))
        }))
}
