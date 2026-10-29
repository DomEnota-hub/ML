package com.domenota.medialoader.core.diagnostics

/**
 * These facts are NOT interchangeable:
 * - saved credentials (presence, not validity);
 * - anonymous HTTP reachability (not authentication or media extraction);
 * - old download records (not current health of a source).
 *
 * Source display labels are deliberately independent from internal identifiers.
 */
enum class DiagnosticSource(val id: String, val label: String, val requiresAccountCheck: Boolean) {
    INSTAGRAM("instagram", "Instagram", true),
    YOUTUBE("youtube", "YouTube", true),
    VK("vk", "VK", true),
    RUTUBE("rutube", "RUTUBE", true),
    PRIMARY_MUSIC("hitmoz", "Основной музыкальный источник", false),
    RESERVE_MUSIC("muzmo", "Резервный музыкальный источник", false),
}

object SourceDiagnosticRules {
    /** A saved session does not establish server-side validity or access permissions. */
    fun storedSession(present: Boolean): DiagnosticOutcome =
        if (present) DiagnosticOutcome(
            DiagnosticState.UNAVAILABLE, DiagnosticEvidence.OBSERVED,
            "Данные входа сохранены локально. Действительность авторизации на сервере не проверялась.",
            "AUTH_SERVER_NOT_VERIFIED",
        ) else DiagnosticOutcome(
            DiagnosticState.UNAVAILABLE, DiagnosticEvidence.VERIFIED,
            "Сохранённая сессия не найдена. Часть материалов может быть доступна без входа.",
            "AUTH_NOT_STORED",
        )

    /** Query of an anonymous public document; no media, tokens or search terms. */
    fun http(statusCode: Int): DiagnosticOutcome = when (statusCode) {
        in 200..299 -> DiagnosticOutcome(
            DiagnosticState.OK, DiagnosticEvidence.VERIFIED,
            "Сервер ответил на анонимный запрос. Поиск, авторизация и скачивание не проверены.",
            "HTTP_DOCUMENT_REACHABLE",
        )
        in 300..399 -> DiagnosticOutcome(
            DiagnosticState.UNAVAILABLE, DiagnosticEvidence.VERIFIED,
            "Сервер предложил перенаправление. Переход по нему не выполнялся.",
            "HTTP_REDIRECT_NOT_FOLLOWED",
        )
        401 -> DiagnosticOutcome(
            DiagnosticState.UNAVAILABLE, DiagnosticEvidence.VERIFIED,
            "Сервер требует авторизацию для анонимного запроса. Сохранённая сессия не проверялась.",
            "HTTP_AUTH_REQUIRED",
        )
        403 -> DiagnosticOutcome(
            DiagnosticState.UNAVAILABLE, DiagnosticEvidence.VERIFIED,
            "Сервер ограничил доступ к документу (HTTP 403). Причина не установлена: " +
                "возможны ограничения текущего IP или VPN, сессии либо защита ресурса.",
            "HTTP_ACCESS_DENIED",
            "Можно повторить проверку с другим VPN-сервером. Это не доказательство поломки загрузчика.",
        )
        429 -> DiagnosticOutcome(
            DiagnosticState.UNAVAILABLE, DiagnosticEvidence.VERIFIED,
            "Сервер ограничивает частоту запросов (HTTP 429). Повторные проверки лучше отложить.",
            "HTTP_RATE_LIMIT",
        )
        in 500..599 -> DiagnosticOutcome(
            DiagnosticState.UNAVAILABLE, DiagnosticEvidence.VERIFIED,
            "Сервер ответил ошибкой 5xx. Причина на стороне приложения не подтверждена.",
            "HTTP_REMOTE_FAILURE",
        )
        in 400..499 -> DiagnosticOutcome(
            DiagnosticState.UNAVAILABLE, DiagnosticEvidence.VERIFIED,
            "Сервер отклонил анонимный запрос. Работа поиска и скачивания не проверялась.",
            "HTTP_REQUEST_REJECTED",
        )
        else -> DiagnosticOutcome.unavailable(
            "Не удалось интерпретировать ответ сервера без выполнения других запросов.",
            "HTTP_UNEXPECTED_RESPONSE")
    }

    fun transportFailed(category: NetworkIssue): DiagnosticOutcome {
        val (message, reason) = when (category) {
            NetworkIssue.NO_NETWORK -> "На устройстве нет доступной сети." to "NETWORK_NOT_CONNECTED"
            NetworkIssue.NAME_RESOLUTION -> "Не удалось определить адрес сервера. Причина может быть в DNS или сети." to "NETWORK_DNS_FAILED"
            NetworkIssue.TIMEOUT -> "Сервер не ответил до истечения времени ожидания." to "NETWORK_TIMEOUT"
            NetworkIssue.TLS -> "Не удалось установить защищённое соединение." to "NETWORK_TLS_FAILED"
            NetworkIssue.COOKIE_HANDLER -> "Анонимная проверка не запускалась: обнаружен общий обработчик cookies. Безопасность передачи данных входа не гарантирована." to "NETWORK_PRIVACY_GUARD"
            NetworkIssue.OTHER -> "Сетевая проверка не завершилась. Точная причина не определена." to "NETWORK_CHECK_UNAVAILABLE"
        }
        return DiagnosticOutcome.unavailable(message, reason)
    }

    enum class NetworkIssue { NO_NETWORK, NAME_RESOLUTION, TIMEOUT, TLS, COOKIE_HANDLER, OTHER }

    /** Historical results from existing Room metadata; never an active probe. */
    fun history(completed: Int, failed: Int, cancelled: Int): DiagnosticOutcome {
        require(completed >= 0 && failed >= 0 && cancelled >= 0)
        return if (completed == 0 && failed == 0 && cancelled == 0)
            DiagnosticOutcome(
                DiagnosticState.UNAVAILABLE, DiagnosticEvidence.NONE,
                "В доступной истории загрузок нет данных по этому источнику.",
                "SOURCE_HISTORY_EMPTY")
        else DiagnosticOutcome(
            DiagnosticState.UNAVAILABLE, DiagnosticEvidence.HISTORICAL,
            "В доступной истории: завершено $completed, ошибок $failed, отменено $cancelled. " +
                "Это не подтверждает текущую работоспособность источника.",
            "SOURCE_HISTORY_ONLY")
    }
}
