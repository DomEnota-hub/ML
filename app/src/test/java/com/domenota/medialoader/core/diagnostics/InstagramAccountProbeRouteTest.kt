package com.domenota.medialoader.core.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InstagramAccountProbeRouteTest {
    @Test fun badRequestOnEditRouteTriesPlainAccountRoute() {
        assertEquals("https://www.instagram.com/api/v1/accounts/current_user/",
            InstagramAccountProbeRoute.fallbackAfter(400))
        assertNull(InstagramAccountProbeRoute.fallbackAfter(401))
        assertNull(InstagramAccountProbeRoute.fallbackAfter(429))
        assertNull(InstagramAccountProbeRoute.fallbackAfter(400,
            "{\"message\":\"login_required\"}"))
    }
}
