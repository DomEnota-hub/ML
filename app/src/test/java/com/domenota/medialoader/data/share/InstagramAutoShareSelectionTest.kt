package com.domenota.medialoader.data.share

import com.domenota.medialoader.core.model.MediaItem
import com.domenota.medialoader.core.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InstagramAutoShareSelectionTest {
    private fun media(id: String, type: MediaType, provider: String = "instagram") =
        MediaItem(id, provider, type, "$id.jpg", "https://example.org/$id")

    @Test fun selectsAllCarouselVisualsInOriginalOrderButNoSeparateMusic() {
        val items = listOf(
            media("first", MediaType.PHOTO),
            media("music", MediaType.AUDIO),
            media("second", MediaType.VIDEO),
            media("third", MediaType.PHOTO),
            media("foreign", MediaType.VIDEO, "youtube"),
        )
        assertEquals(listOf("first", "second", "third"),
            InstagramAutoShareSelection.visuals(items).map { it.id })
    }

    @Test fun canonicalizesSupportedInstagramPublicationOnly() {
        assertEquals("https://www.instagram.com/p/C7YnGRuqHhA/",
            InstagramAutoShareSelection.canonicalUrl("https://instagram.com/p/C7YnGRuqHhA/?igsh=test"))
        assertNull(InstagramAutoShareSelection.canonicalUrl("https://youtube.com/watch?v=ADlGkXAz1D0"))
        assertNull(InstagramAutoShareSelection.canonicalUrl("https://instagram.com/explore/"))
    }
}
