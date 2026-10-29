package com.domenota.medialoader.core.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InstagramLinkParserTest {
    @Test fun keepsUrlKind() {
        assertEquals(
            InstagramLink(InstagramLinkType.POST, "Ab_12-z"),
            InstagramLinkParser.parse("https://www.instagram.com/p/Ab_12-z/"),
        )
        assertEquals(
            InstagramLink(InstagramLinkType.REEL, "Ab_12-z"),
            InstagramLinkParser.parse("https://www.instagram.com/reel/Ab_12-z/?utm_source=share"),
        )
        assertEquals(
            InstagramLink(InstagramLinkType.TV, "Ab_12-z"),
            InstagramLinkParser.parse("https://instagram.com/tv/Ab_12-z"),
        )
    }

    @Test fun acceptsUsernamePrefix() {
        assertEquals(
            InstagramLink(InstagramLinkType.REEL, "Ab_12-z"),
            InstagramLinkParser.parse("https://www.instagram.com/some.user/reel/Ab_12-z/"),
        )
        assertEquals(
            InstagramLink(InstagramLinkType.POST, "Ab_12-z"),
            InstagramLinkParser.parse("https://www.instagram.com/some.user/p/Ab_12-z/"),
        )
    }

    @Test fun parsesStories() {
        assertEquals(
            InstagramLink(InstagramLinkType.STORY, "3456789012345"),
            InstagramLinkParser.parse("https://www.instagram.com/stories/some.user/3456789012345/?igsh=x"),
        )
        assertNull(InstagramLinkParser.parse("https://www.instagram.com/stories/highlights/1789/"))
        assertNull(InstagramLinkParser.parse("https://www.instagram.com/stories/user/12/"))
    }

    @Test fun rejectsOtherHostsSchemesAndPaths() {
        assertNull(InstagramLinkParser.parse("https://instagram.com.evil.test/p/Ab_12-z/"))
        assertNull(InstagramLinkParser.parse("https://instagram.com.evil.test/stories/u/3456789012345/"))
        assertNull(InstagramLinkParser.parse("https://www.instagram.com/accounts/login/"))
        assertNull(InstagramLinkParser.parse("ftp://www.instagram.com/p/Ab_12-z/"))
        assertNull(InstagramLinkParser.parse("https://www.instagram.com/p/"))
        assertNull(InstagramLinkParser.parse("просто текст"))
        assertNull(InstagramLinkParser.parse(""))
    }

    @Test fun buildsPublicPagesInTheSameKind() {
        val reel = InstagramLink(InstagramLinkType.REEL, "Ab_12-z")
        assertEquals("https://www.instagram.com/reel/Ab_12-z/", reel.pageUrl())
        assertEquals("https://www.instagram.com/reel/Ab_12-z/embed/captioned/", reel.embedUrl())
        assertEquals("https://www.instagram.com/p/Ab_12-z/", InstagramLink(InstagramLinkType.POST, "Ab_12-z").pageUrl())
    }
}
