package com.domenota.medialoader.core.diagnostics

object InstagramAccountProbeRoute {
    const val PRIMARY = "https://www.instagram.com/api/v1/accounts/current_user/?edit=true"
    private const val PLAIN = "https://www.instagram.com/api/v1/accounts/current_user/"

    fun fallbackAfter(status: Int, body: String = ""): String? {
        if (status != 400) return null
        val reason = SessionProbeRules.classify(DiagnosticSource.INSTAGRAM, status, body).reasonCode
        return if (reason == "SESSION_LOGIN_REQUIRED" || reason == "SESSION_CHALLENGE_REQUIRED")
            null else PLAIN
    }
}
