package com.domenota.medialoader.ui
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class RutubeAuthNavigationTest {
    @Test fun providerSchemesAndHttpsFallbackWorkWithoutPassingIntentCommands() {
        assertEquals("sberid",RutubeAuthNavigation.appUri("sberid://login")?.scheme)
        assertEquals("yandexauth",RutubeAuthNavigation.appUri("yandexauth://login")?.scheme)
        assertNull(RutubeAuthNavigation.appUri("javascript:alert(1)"))
        assertNull(RutubeAuthNavigation.appUri("file:///private"))
        assertEquals("https://id.vk.ru/login",RutubeAuthNavigation.webFallback("intent://id.vk.ru/login#Intent;scheme=https;end"))
        assertNull(RutubeAuthNavigation.webFallback("intent://login#Intent;scheme=provider;S.browser_fallback_url=javascript%3Aalert(1);end"))
    }
}
