package com.domenota.medialoader.core.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YtDlpThumbnailFallbackTest {
    @Test fun usesExtractedThumbnailWhenAvailable() {
        val info = YtDlpJson.parse(
            """{"id":"r1","title":"Video","thumbnail":"https://pic.rutubelist.ru/a.jpg",
                "thumbnails":[{"url":"https://pic.rutubelist.ru/b.jpg"}],"formats":[]}""",
        )
        assertEquals("https://pic.rutubelist.ru/a.jpg", info.thumbnailUrl)
    }

    @Test fun fallsBackToValidHttpsThumbnailFromList() {
        val info = YtDlpJson.parse(
            """{"id":"r2","title":"Video",
                "thumbnails":[{"url":"http://untrusted.example/cover.jpg"},
                {"url":"https://pic.rutubelist.ru/video/a.jpg"},
                {"url":"https://pic.rutubelist.ru/video/b.jpg"}],"formats":[]}""",
        )
        assertEquals("https://pic.rutubelist.ru/video/b.jpg", info.thumbnailUrl)
    }

    @Test fun returnsNullWhenNoThumbnailExists() {
        val info = YtDlpJson.parse("""{"id":"r3","title":"No cover","formats":[]}""")
        assertNull(info.thumbnailUrl)
    }
}
