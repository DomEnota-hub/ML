package com.domenota.medialoader.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstagramSessionCookiePolicyTest {
    @Test fun browserHeaderNeedsAnActualNonemptySessionCookie() {
        assertTrue(InstagramSessionCookiePolicy.hasSession("csrftoken=abc; sessionid=123%3Asecret; ds_user_id=123"))
        assertFalse(InstagramSessionCookiePolicy.hasSession("csrftoken=abc; sessionid=; ds_user_id=123"))
        assertFalse(InstagramSessionCookiePolicy.hasSession("other_sessionid=123%3Asecret"))
    }
}
