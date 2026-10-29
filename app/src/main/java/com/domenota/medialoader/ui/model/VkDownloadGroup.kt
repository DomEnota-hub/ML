package com.domenota.medialoader.ui.model

/** Keeps all photos from one VK community scan together, including old per-post rows. */
object VkDownloadGroup {
    private val communityId = Regex(
        "^vk-community-([0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12})-",
    )

    fun isCommunity(item: DownloadUiItem): Boolean = isCommunity(item.id, item.providerId)

    private fun isCommunity(id: String, providerId: String?): Boolean =
        providerId.equals("vk", ignoreCase = true) && communityId.containsMatchIn(id)

    fun key(item: DownloadUiItem): String = key(item.id, item.providerId, item.groupId)

    fun key(id: String, providerId: String?, groupId: String?): String {
        if (isCommunity(id, providerId)) {
            val jobId = communityId.find(id)!!.groupValues[1]
            return "vk-community-$jobId"
        }
        return groupId ?: id
    }
}
