package com.domenota.medialoader.ui.model

import org.junit.Assert.assertEquals
import org.junit.Test

class VkDownloadGroupTest {
    private fun item(id: String, group: String?, provider: String = "vk") = DownloadUiItem(
        id = id, title = "image.jpg", subtitle = "", sizeLabel = null,
        state = DownloadUiState.COMPLETED, groupId = group, providerId = provider)

    @Test fun oldPostGroupsFromSameVkJobBecomeOneDownloadGroup() {
        val job = "12345678-1234-1234-1234-123456789abc"
        val first = item("vk-community-$job--7:1", "vk-community-$job--7:101")
        val second = item("vk-community-$job--7:2", "vk-community-$job--7:102")
        val other = item("vk-community-87654321-1234-1234-1234-123456789abc--7:3", null)
        assertEquals(VkDownloadGroup.key(first), VkDownloadGroup.key(second))
        assertEquals("vk-community-$job", VkDownloadGroup.key(first))
        assertEquals("vk-community-87654321-1234-1234-1234-123456789abc", VkDownloadGroup.key(other))
    }

    @Test fun unrelatedDownloadsKeepTheirExistingGroups() {
        val instagram = item("inst-1", "post-1", provider = "instagram")
        val ordinaryVk = item("vk-ordinary", "ordinary-post")
        assertEquals("post-1", VkDownloadGroup.key(instagram))
        assertEquals("ordinary-post", VkDownloadGroup.key(ordinaryVk))
    }
}
