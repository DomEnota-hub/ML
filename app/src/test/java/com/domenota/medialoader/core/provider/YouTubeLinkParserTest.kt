package com.domenota.medialoader.core.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeLinkParserTest {
    private val id = "dQw4w9WgXcQ"

    @Test fun parsesCommonForms() {
        listOf(
            "https://www.youtube.com/watch?v=$id",
            "https://youtube.com/watch?v=$id&t=42s",
            "https://m.youtube.com/watch?feature=share&v=$id",
            "https://music.youtube.com/watch?v=$id&list=RDAMVM$id",
            "https://youtu.be/$id?si=abc",
            "https://www.youtube.com/shorts/$id",
            "https://www.youtube.com/live/$id?feature=share",
            "https://www.youtube.com/embed/$id",
        ).forEach { assertEquals(it, YouTubeLink(id), YouTubeLinkParser.parse(it)) }
    }

    @Test fun playlistParametersAreDroppedFromTheCanonicalUrl() {
        val link = YouTubeLinkParser.parse("https://www.youtube.com/watch?v=$id&list=PL123&index=4")
        assertEquals("https://www.youtube.com/watch?v=$id", link?.canonicalUrl())
    }

    @Test fun rejectsForeignHostsBadIdsAndOtherPages() {
        assertNull(YouTubeLinkParser.parse("https://youtube.com.evil.test/watch?v=$id"))
        assertNull(YouTubeLinkParser.parse("https://www.youtube.com/watch?v=short"))
        assertNull(YouTubeLinkParser.parse("https://www.youtube.com/playlist?list=PL123"))
        assertNull(YouTubeLinkParser.parse("https://www.youtube.com/@channel"))
        assertNull(YouTubeLinkParser.parse("ftp://www.youtube.com/watch?v=$id"))
        assertNull(YouTubeLinkParser.parse("https://www.instagram.com/p/Ab_12-z/"))
        assertNull(YouTubeLinkParser.parse("просто текст"))
    }
}
