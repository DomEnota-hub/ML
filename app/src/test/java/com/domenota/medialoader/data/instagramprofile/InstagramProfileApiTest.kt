package com.domenota.medialoader.data.instagramprofile

import com.domenota.medialoader.core.provider.ProviderException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstagramProfileApiTest {
    @Test fun publicRateLimitDoesNotDiscardWorkingSavedSession() = runBlocking {
        val attempts = mutableListOf<Pair<String, Boolean>>()
        val api = InstagramProfileApi { method, url, _, cookies, _ ->
            attempts += method to cookies.isNotBlank()
            if (cookies.isBlank()) throw ProviderException(ProviderException.Reason.RATE_LIMITED,
                "Public page limited", "instagram")
            if (url.contains("web_profile_info"))
                """{"data":{"user":{"pk":"12345","username":"test.profile"}}}"""
            else "<html></html>"
        }
        assertEquals("12345", api.profile("test.profile", "sessionid=saved").id)
        assertTrue(attempts.any { it.second })
    }

    @Test fun rejectedWebFeedTriesAuthorizedFeedBeforeDeclaringSessionInvalid() = runBlocking {
        val routes = mutableListOf<String>()
        val api = InstagramProfileApi { method, url, _, _, _ ->
            routes += "$method ${url.substringAfter("instagram.com")}"
            if (method == "POST") throw ProviderException(ProviderException.Reason.ACCESS_REQUIRED,
                "Web query rejected", "instagram")
            """{"items":[{"code":"Fallback1","taken_at":1720000000,"media_type":1}],"more_available":false}"""
        }
        val profile = InstagramProfileInfo("12345", "test.profile", "Test", null, false)
        assertEquals("Fallback1", api.page(profile, null, reels = false,
            cookies = "sessionid=saved").posts.single().code)
        assertTrue(routes.any { it.contains("/api/v1/feed/user/12345/") })
    }

    @Test fun profileLinksAcceptHandlesAndRejectMediaPaths() {
        assertEquals("some.user", InstagramProfileLink.parse("https://www.instagram.com/some.user/?igsh=abc")?.username)
        assertEquals("some_user", InstagramProfileLink.parse("@some_user")?.username)
        assertNull(InstagramProfileLink.parse("https://instagram.com/p/Ab_12-z/"))
        assertNull(InstagramProfileLink.parse("https://instagram.com.evil.test/user/"))
        assertNull(InstagramProfileLink.parse("https://instagram.com/accounts/login/"))
    }

    @Test fun profileInfoReadsNameAvatarAndId() {
        val body = """{"data":{"user":{"id":"12345","username":"test.profile","full_name":"Test", "profile_pic_url":"https://cdninstagram.com/avatar.jpg","is_private":false}}}"""
        val profile = InstagramProfileApi.parseProfile(body)
        assertEquals("12345", profile.id)
        assertEquals("Test", profile.name)
        assertFalse(profile.private)
    }

    @Test fun publicProfileHtmlUsesEmbeddedProfilePk() {
        val html = """<html><script type="application/json">{"__bbox":{"result":{"data":{"xig_user_by_username":{"id":"page-object","pk":"12345","username":"test.profile","full_name":"Test"}}}}}</script></html>"""
        assertEquals("12345", InstagramProfileApi.parseProfileHtml(html, "test.profile").id)
    }

    @Test fun feedAndReelsPageDeduplicateByShortcodeAndKeepCursor() {
        val feed = """{"data":{"xdt_api__v1__feed__user_timeline_graphql_connection":{"edges":[{"node":{"code":"A12_bc","taken_at":1720000000,"media_type":1,"thumbnail_url":"https://cdninstagram.com/a.jpg"}},{"node":{"code":"A12_bc","taken_at":1720000000,"media_type":1}}],"page_info":{"has_next_page":true,"end_cursor":"next"}}}}"""
        val page = InstagramProfileApi.parsePage(feed, reels = false)
        assertEquals(1, page.posts.size)
        assertEquals("A12_bc", page.posts.single().code)
        assertEquals("next", page.cursor)
        val reel = """{"data":{"xdt_api__v1__clips__user__connection_v2":{"edges":[{"node":{"media":{"code":"R12_bc","taken_at":1720000001,"media_type":2,"image_versions2":{"candidates":[{"url":"https://cdninstagram.com/r.jpg"}]}}}}],"page_info":{"has_next_page":false}}}}"""
        assertEquals("R12_bc", InstagramProfileApi.parsePage(reel, reels = true).posts.single().code)
        assertNull(InstagramProfileApi.parsePage(reel, reels = true).cursor)
    }

    @Test fun malformedPageIsNotTreatedAsFinished() {
        try {
            InstagramProfileApi.parsePage("{\"data\":{}}", reels = false)
            throw AssertionError("Missing connection must fail")
        } catch (expected: InstagramProfileFormatException) {
            assertTrue(expected.message!!.contains("ответ"))
        }
    }

    @Test fun privateFeedFallbackCarriesItsOwnCursor() {
        val body = """{"items":[{"code":"Fallback1","taken_at":1720000000,"media_type":1}],"more_available":true,"next_max_id":"offset-12"}"""
        val page = InstagramProfileApi.parsePrivatePage(body)
        assertEquals("Fallback1", page.posts.single().code)
        assertEquals("api:offset-12", page.cursor)
    }
}
