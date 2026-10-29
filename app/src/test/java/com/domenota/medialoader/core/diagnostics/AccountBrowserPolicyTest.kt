package com.domenota.medialoader.core.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountBrowserPolicyTest {
    @Test fun rutubeAllowsOfficialIdentityRedirect() {
        assertTrue(AccountBrowserPolicy.allowsRutubeNavigation("https://auth.gid.ru/login"))
        assertTrue(AccountBrowserPolicy.allowsRutubeNavigation("https://rutube.ru/"))
        assertFalse(AccountBrowserPolicy.allowsRutubeNavigation("https://auth.gid.ru.evil.test/"))
        assertFalse(AccountBrowserPolicy.allowsRutubeNavigation("http://auth.gid.ru/login"))
    }

    @Test fun rutubeCookieNamesAreLimitedToItsOwnHosts() {
        assertTrue(AccountBrowserPolicy.rutubeCookieNames("sessionid=abc; other=xyz").contains("sessionid"))
        assertTrue(AccountBrowserPolicy.rutubeCookieNames("sessionid=abc; other=xyz").contains("other"))
        assertFalse(AccountBrowserPolicy.rutubeCookieNames("broken; sessionid=abc").contains("broken"))
    }

    @Test fun rutubeBrowserMustShowLogoutControlBeforeCapturingSession() {
        assertTrue(AccountBrowserPolicy.rutubeProfileConfirmed("true"))
        assertFalse(AccountBrowserPolicy.rutubeProfileConfirmed("false"))
        assertFalse(AccountBrowserPolicy.rutubeProfileConfirmed("null"))
    }
}
