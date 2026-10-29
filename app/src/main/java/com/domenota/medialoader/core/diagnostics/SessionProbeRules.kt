package com.domenota.medialoader.core.diagnostics

/** Conservative interpretation of an authenticated, read-only account request. */
object SessionProbeRules {
    fun notStored() = DiagnosticOutcome(DiagnosticState.UNAVAILABLE,
        DiagnosticEvidence.VERIFIED,
        "Сессия не сохранена; проверка входа на сервере не запускалась.",
        "SESSION_NOT_STORED")

    fun classify(source: DiagnosticSource, status: Int, body: String,
        redirectHost: String? = null, contentType: String? = null,
        redirectPath: String? = null): DiagnosticOutcome {
        if (source == DiagnosticSource.INSTAGRAM && status == 400) {
            val markers = Regex("\"(?:message|error_type)\"\\s*:\\s*\"([A-Za-z_]+)\"",
                RegexOption.IGNORE_CASE).findAll(body.take(16_384))
                .map { it.groupValues[1].lowercase() }.toSet()
            if ("login_required" in markers) return DiagnosticOutcome.unavailable(
                "Instagram запросил повторный вход. Сохранённая сессия не подтверждена.",
                "SESSION_LOGIN_REQUIRED")
            if ("checkpoint_required" in markers || "challenge_required" in markers)
                return DiagnosticOutcome.unavailable(
                    "Instagram запросил подтверждение аккаунта в браузере.",
                    "SESSION_CHALLENGE_REQUIRED")
        }
        if (status == 401 || (status in 300..399 &&
                (redirectHost?.contains("login", ignoreCase = true) == true ||
                    source == DiagnosticSource.INSTAGRAM &&
                    redirectPath?.startsWith("/accounts/login", ignoreCase = true) == true)))
            return DiagnosticOutcome(DiagnosticState.UNAVAILABLE, DiagnosticEvidence.VERIFIED,
                "Сервер запросил повторный вход. Сохранённая сессия не подтверждена.",
                "SESSION_LOGIN_REQUIRED")
        if (status != 200) {
            val message = when (status) {
                403 -> "Сервер ограничил проверку профиля (HTTP 403). Это не доказывает выход из аккаунта."
                429 -> "Сервер ограничил частоту проверки профиля (HTTP 429)."
                in 300..399 -> "Проверка профиля перенаправлена (HTTP $status). Вход не подтверждён."
                else -> "Проверка профиля вернула HTTP $status. Вход не подтверждён."
            }
            return DiagnosticOutcome.unavailable(message, "SESSION_HTTP_$status")
        }
        if (contentType?.contains("text/html", ignoreCase = true) == true &&
            body.contains("login", ignoreCase = true))
            return DiagnosticOutcome.unavailable("Вместо профиля получена страница входа.",
                "SESSION_LOGIN_PAGE")
        val verified = when (source) {
            DiagnosticSource.INSTAGRAM -> Regex("\\\"user\\\"\\s*:\\s*\\{").containsMatchIn(body) &&
                (body.contains("\"pk\"") || body.contains("\"id\""))
            DiagnosticSource.YOUTUBE -> Regex("""\\?"LOGGED_IN\\?"\s*:\s*true""").containsMatchIn(body)
            DiagnosticSource.VK -> body.contains("act=logout", true) ||
                body.contains("/logout", true)
            DiagnosticSource.RUTUBE -> body.contains("\"username\"") &&
                (body.contains("\"id\"") || body.contains("\"user_id\""))
            else -> false
        }
        return if (verified) DiagnosticOutcome(DiagnosticState.OK, DiagnosticEvidence.VERIFIED,
            "Сервер принял сохранённую сессию загрузчика.",
            "SESSION_CONFIRMED")
        else DiagnosticOutcome(DiagnosticState.UNAVAILABLE, DiagnosticEvidence.VERIFIED,
            "Профиль не распознан в ответе сервера (HTTP 200). Доступ к контрольному материалу проверяется отдельно.",
            "SESSION_PROFILE_UNRECOGNIZED")
    }
}
