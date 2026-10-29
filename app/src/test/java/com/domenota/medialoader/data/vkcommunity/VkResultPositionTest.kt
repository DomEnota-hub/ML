package com.domenota.medialoader.data.vkcommunity

import org.junit.Assert.assertEquals
import org.junit.Test

class VkResultPositionTest {
    @Test fun swipingAcrossPageBoundaryKeepsAbsoluteIndex() {
        assertEquals(60, VkResultPosition.move(59, 1, 341))
        assertEquals(1, VkResultPosition.page(60))
        assertEquals(0, VkResultPosition.offset(60))
        assertEquals(59, VkResultPosition.move(60, -1, 341))
    }

    @Test fun firstAndLastImagesDoNotWrap() {
        assertEquals(0, VkResultPosition.move(0, -1, 341))
        assertEquals(340, VkResultPosition.move(340, 1, 341))
    }
}
