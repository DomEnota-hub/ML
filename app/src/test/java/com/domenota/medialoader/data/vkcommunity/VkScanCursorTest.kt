package com.domenota.medialoader.data.vkcommunity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VkScanCursorTest {
    @Test fun crossesFourHundredAndThousandPostsInBoundedPages() {
        var cursor = VkScanCursor(0, 0, false)
        repeat(10) {
            cursor = cursor.advance(VkParsedPage(1_050, 100, emptyList(), false))
            assertEquals((it + 1) * 100, cursor.offset)
            assertTrue(!cursor.done)
        }
        cursor = cursor.advance(VkParsedPage(1_050, 50, emptyList(), false))
        assertEquals(1_050, cursor.offset)
        assertTrue(cursor.done)
    }

    @Test fun emptyOrOldPageStopsWithoutLosingCheckpoint() {
        val previous = VkScanCursor(400, 900, false)
        assertEquals(400, previous.advance(VkParsedPage(900, 0, emptyList(), false)).offset)
        assertTrue(previous.advance(VkParsedPage(900, 0, emptyList(), false)).done)
        assertTrue(previous.advance(VkParsedPage(900, 100, emptyList(), true)).done)
    }
}
