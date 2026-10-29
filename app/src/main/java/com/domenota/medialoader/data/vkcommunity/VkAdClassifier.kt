package com.domenota.medialoader.data.vkcommunity

import org.json.JSONObject

enum class VkAdStatus { NONE, POSSIBLE, CONFIRMED }

/** Only VK's explicit flag can automatically remove a post from selection. */
object VkAdClassifier {
    private val clues = Regex("(?iu)(?:^|\\W)(?:реклама|рекламный|промокод|erid)(?:\\W|$)")

    fun classify(post: JSONObject): VkAdStatus {
        if (post.optInt("marked_as_ads") == 1) return VkAdStatus.CONFIRMED
        var possible = clues.containsMatchIn(post.optString("text"))
        val copies = post.optJSONArray("copy_history")
        if (copies != null) for (index in 0 until minOf(copies.length(), 10)) {
            when (copies.optJSONObject(index)?.let(::classify)) {
                VkAdStatus.CONFIRMED -> return VkAdStatus.CONFIRMED
                VkAdStatus.POSSIBLE -> possible = true
                else -> Unit
            }
        }
        return if (possible) VkAdStatus.POSSIBLE else VkAdStatus.NONE
    }
}
