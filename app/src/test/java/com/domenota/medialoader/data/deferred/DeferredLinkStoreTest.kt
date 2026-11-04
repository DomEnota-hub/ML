package com.domenota.medialoader.data.deferred

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DeferredLinkStoreTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    @Before fun clear() { context.getSharedPreferences("deferred_download_links", Context.MODE_PRIVATE).edit().clear().commit() }
    @Test fun bufferSurvivesRecreationDeduplicatesAndRemovesOnlyChosenLink() {
        val first = "https://www.instagram.com/reel/one/"
        val second = "https://youtu.be/two"
        val store = DeferredLinkStore(context)
        assertTrue(store.add(" $first ")); store.add(first); store.add(second)
        val restored = DeferredLinkStore(context)
        assertEquals(listOf(first, second), restored.links.value)
        restored.remove(first)
        assertEquals(listOf(second), DeferredLinkStore(context).links.value)
    }
    @Test fun rejectsNonWebLinksAndDoesNotMisidentifySimilarHost() {
        val store = DeferredLinkStore(context)
        assertFalse(store.add("file:///tmp/video")); assertFalse(store.add("broken"))
        assertTrue(store.links.value.isEmpty())
        assertEquals("Instagram", DeferredLinkStore.source("https://m.instagram.com/reel/1"))
        assertEquals("instagram.com.example.org", DeferredLinkStore.source("https://instagram.com.example.org/1"))
    }
}
