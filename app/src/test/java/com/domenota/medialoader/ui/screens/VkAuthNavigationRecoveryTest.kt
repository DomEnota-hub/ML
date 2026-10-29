package com.domenota.medialoader.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Test

class VkAuthNavigationRecoveryTest {
    @Test fun officialVkQrIntentCanOpenOnlyTheVkApp() {
        assertEquals("vk://qr.vk.ru/login?step=1",
            VkAuthNavigationRecovery.officialAppTarget(
                "intent://qr.vk.ru/login?step=1#Intent;scheme=vk;package=com.vkontakte.android;end"))
        assertEquals("vk://qr.vk.ru/login",
            VkAuthNavigationRecovery.officialAppTarget(
                "intent://qr.vk.ru/login#Intent;scheme=vk;end"))
        assertEquals("https://qr.vk.ru/login?step=1",
            VkAuthNavigationRecovery.officialAppTarget(
                "intent://qr.vk.ru/login?step=1#Intent;scheme=https;package=com.vkontakte.android;S.browser_fallback_url=https%3A%2F%2Fm.vk.ru%2F;end"))
        assertNull(VkAuthNavigationRecovery.officialAppTarget(
            "intent://qr.vk.ru/login#Intent;scheme=vk;package=com.evil.app;end"))
        assertNull(VkAuthNavigationRecovery.officialAppTarget(
            "intent://qr.vk.ru.evil.test/login#Intent;scheme=vk;end"))
        assertNull(VkAuthNavigationRecovery.officialAppTarget(
            "intent://qr.vk.ru/login#Intent;scheme=http;end"))
    }

    @Test fun qrIntentDiagnosticsNeverIncludeItsPathOrQuery() {
        val summary = VkAuthNavigationRecovery.safeIntentSummary(
            "intent://qr.vk.ru/private-secret?token=secret#Intent;scheme=vk;package=com.vkontakte.android;end")
        assertEquals("declaredScheme=vk · package=official · fallback=false", summary)
        assertFalse(summary.contains("secret"))
    }
    @Test fun upgradesOnlyTrustedQrHop() {
        assertEquals("https://qr.vk.ru/login?step=1",
            VkAuthNavigationRecovery.secureTarget("http://qr.vk.ru/login?step=1"))
        assertNull(VkAuthNavigationRecovery.secureTarget("http://qr.vk.ru.evil.test/login"))
        assertNull(VkAuthNavigationRecovery.secureTarget("http://user@qr.vk.ru/login"))
        assertEquals("https://qr.vk.ru/login",
            VkAuthNavigationRecovery.secureTarget("intent://qr.vk.ru/login#Intent;scheme=https;end"))
        assertEquals("https://id.vk.ru/continue",
            VkAuthNavigationRecovery.secureTarget("intent://qr.vk.ru/login#Intent;scheme=vk;S.browser_fallback_url=https%3A%2F%2Fid.vk.ru%2Fcontinue;end"))
        assertNull(VkAuthNavigationRecovery.secureTarget("intent://qr.vk.ru/login#Intent;scheme=vk;end"))
    }
}
