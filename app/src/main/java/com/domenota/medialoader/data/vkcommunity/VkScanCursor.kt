package com.domenota.medialoader.data.vkcommunity

/** Checkpoint after a bounded page, used by the worker and by restart tests. */
data class VkScanCursor(val offset: Int, val total: Int, val done: Boolean) {
    fun advance(page: VkParsedPage): VkScanCursor {
        require(page.scannedPosts in 0..100)
        val next = Math.addExact(offset, page.scannedPosts)
        return VkScanCursor(next, page.totalPosts,
            page.scannedPosts == 0 || next >= page.totalPosts || page.pastPeriod)
    }
}
