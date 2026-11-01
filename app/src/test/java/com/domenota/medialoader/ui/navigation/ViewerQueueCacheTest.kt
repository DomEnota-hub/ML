package com.domenota.medialoader.ui.navigation

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class ViewerQueueCacheTest {
    @After fun reset() { ViewerQueueCache.clearForTesting() }

    @Test fun largeGalleryQueueUsesOnlySmallSavedStateToken() {
        val ids = (0 until 11_200).map { "phone:image:$it" }
        val token = requireNotNull(ViewerQueueCache.store(ids))
        assertTrue("Only the token should enter SavedStateHandle", token.length <= 64)
        assertEquals(ids, ViewerQueueCache.resolve(token))
    }

    @Test fun processLossFallsBackSafelyToOpenedItem() {
        val token = ViewerQueueCache.store(listOf("a", "b", "c"))
        ViewerQueueCache.clearForTesting() // process death
        assertTrue(ViewerQueueCache.resolve(token).isEmpty())
        assertNull(ViewerQueueCache.store(emptyList()))
        assertTrue(ViewerQueueCache.resolve(null).isEmpty())
    }

    @Test fun cacheKeepsAtMostThreeRecentQueues() {
        val a = ViewerQueueCache.store(listOf("a"))
        val b = ViewerQueueCache.store(listOf("b"))
        val c = ViewerQueueCache.store(listOf("c"))
        assertEquals(listOf("a"), ViewerQueueCache.resolve(a))
        val d = ViewerQueueCache.store(listOf("d"))
        assertEquals(listOf("a"), ViewerQueueCache.resolve(a))
        assertEquals(listOf("d"), ViewerQueueCache.resolve(d))
        assertEquals(emptyList<String>(), ViewerQueueCache.resolve(b))
        assertEquals(listOf("c"), ViewerQueueCache.resolve(c))
    }
}
