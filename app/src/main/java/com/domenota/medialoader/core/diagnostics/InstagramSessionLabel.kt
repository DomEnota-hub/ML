package com.domenota.medialoader.core.diagnostics

enum class InstagramSessionTone { SUCCESS, NEUTRAL, WARNING, ERROR }

data class InstagramSessionLabel(val text: String, val tone: InstagramSessionTone) {
    companion object {
        fun from(saved: Boolean, result: DiagnosticOutcome?, workingMedia: Boolean = false): InstagramSessionLabel = when {
            !saved -> InstagramSessionLabel("Не подключён", InstagramSessionTone.ERROR)
            result?.reasonCode == "SESSION_LOGIN_REQUIRED" || result?.reasonCode == "SESSION_LOGIN_PAGE" ->
                InstagramSessionLabel("Требуется повторный вход", InstagramSessionTone.ERROR)
            result?.reasonCode == "SESSION_CHALLENGE_REQUIRED" ->
                InstagramSessionLabel("Подтвердите вход в Instagram", InstagramSessionTone.WARNING)
            workingMedia -> InstagramSessionLabel("Подключён", InstagramSessionTone.SUCCESS)
            result == null -> InstagramSessionLabel("Проверяем сессию", InstagramSessionTone.NEUTRAL)
            result.state == DiagnosticState.OK && result.reasonCode == "SESSION_CONFIRMED" ->
                InstagramSessionLabel("Подключён · проверено", InstagramSessionTone.SUCCESS)
            else -> InstagramSessionLabel("Сессия сохранена · не проверена", InstagramSessionTone.NEUTRAL)
        }
    }
}

object InstagramLoginFailure {
    fun explain(status: Int): String = when (status) {
        429 -> "Instagram временно ограничил вход (HTTP 429). Подождите и попробуйте позже; при VPN можно сменить соединение."
        403 -> "Instagram отклонил страницу входа (HTTP 403). Попробуйте позже или через другое соединение."
        else -> "Instagram не открыл страницу входа (HTTP $status). Попробуйте ещё раз позже."
    }
}
