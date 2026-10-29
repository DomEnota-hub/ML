package com.domenota.medialoader.data.vkcommunity

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.time.ZoneId

data class VkImageCandidate(
    val photoKey: String,
    val postKey: String,
    val postEpochSecond: Long,
    val url: String,
    val width: Int,
    val height: Int,
    val adStatus: VkAdStatus = VkAdStatus.NONE,
)

data class VkParsedPage(
    val totalPosts: Int,
    val scannedPosts: Int,
    val images: List<VkImageCandidate>,
    val pastPeriod: Boolean,
)

/** Parses one bounded wall.get page; downloaded bytes are validated separately. */
object VkWallParser {
    fun parse(response: JSONObject, period: VkDatePeriod, zone: ZoneId,
        afterEpochSecond: Long? = null, throughEpochSecond: Long? = null): VkParsedPage {
        val posts = response.optJSONArray("items") ?: JSONArray()
        val found = ArrayList<VkImageCandidate>()
        var normalPosts = 0
        var oldNormalPosts = 0
        for (index in 0 until posts.length()) {
            val post = posts.optJSONObject(index) ?: continue
            val date = post.optLong("date")
            if (post.optInt("is_pinned") != 1) {
                normalPosts++
                if (date < period.startEpochSecond(zone) ||
                    afterEpochSecond != null && date <= afterEpochSecond) oldNormalPosts++
            }
            if (!period.contains(date, zone) ||
                afterEpochSecond != null && date <= afterEpochSecond ||
                throughEpochSecond != null && date > throughEpochSecond) continue
            val postKey = "${post.optLong("owner_id")}:${post.optLong("id")}" 
            val adStatus = VkAdClassifier.classify(post)
            if (adStatus != VkAdStatus.CONFIRMED)
                collect(post, postKey, date, adStatus, found, depth = 0)
        }
        return VkParsedPage(
            response.optInt("count", posts.length()),
            posts.length(), found,
            normalPosts > 0 && oldNormalPosts == normalPosts,
        )
    }

    private fun collect(post: JSONObject, postKey: String, date: Long, adStatus: VkAdStatus,
        output: MutableList<VkImageCandidate>, depth: Int) {
        if (depth > 3) return
        post.optJSONArray("attachments")?.let { attachments ->
            for (index in 0 until attachments.length()) {
                val attachment = attachments.optJSONObject(index) ?: continue
                if (attachment.optString("type") != "photo") continue
                val photo = attachment.optJSONObject("photo") ?: continue
                val photoId = photo.optLong("id")
                val ownerId = photo.optLong("owner_id")
                if (photoId == 0L || ownerId == 0L) continue
                val sizes = photo.optJSONArray("sizes") ?: continue
                var selected: JSONObject? = null
                var area = -1L
                for (sizeIndex in 0 until sizes.length()) {
                    val size = sizes.optJSONObject(sizeIndex) ?: continue
                    val width = size.optInt("width")
                    val height = size.optInt("height")
                    val address = size.optString("url")
                    val uri = runCatching { URI(address) }.getOrNull()
                    if (width <= 0 || height <= 0 || uri?.scheme != "https" ||
                        uri.host == null || uri.userInfo != null || uri.port !in listOf(-1, 443)) continue
                    if (width.toLong() * height > area) {
                        selected = size
                        area = width.toLong() * height
                    }
                }
                selected?.let { size -> output += VkImageCandidate(
                    "$ownerId:$photoId", postKey, date,
                    size.getString("url"), size.getInt("width"), size.getInt("height"), adStatus,
                ) }
            }
        }
        post.optJSONArray("copy_history")?.let { copies ->
            for (index in 0 until copies.length()) {
                val copy = copies.optJSONObject(index) ?: continue
                collect(copy, postKey, date, adStatus, output, depth + 1)
            }
        }
    }
}
