package com.domenota.medialoader.core.diagnostics

/** Human-readable labels are intentionally independent of internal source domains. */
object DiagnosticLabels {
    fun check(spec: DiagnosticCheckSpec): String {
        val code = spec.code
        if (code.startsWith("source.")) {
            val id = code.removePrefix("source.").substringBefore('.')
            val source = DiagnosticSource.entries.firstOrNull { it.id == id }
            val base = source?.label ?: "Неизвестный источник"
            val suffix = when (code.substringAfterLast('.')) {
                "auth" -> "Сохранённая сессия"
                "session" -> "Сессия загрузчика"
                "http" -> "Ответ главной страницы"
                "history" -> "История загрузок"
                "workflow" -> "Получение метаданных"
                else -> "Дополнительная проверка"
            }
            return "$base · $suffix"
        }
        return when (code) {
            "runtime.ytdlp.library" -> "Библиотека yt-dlp"
            "runtime.ffmpeg.library" -> "Библиотека FFmpeg"
            "runtime.quickjs.bundle" -> "QuickJS"
            "runtime.ytdlp.active" -> "Запуск yt-dlp"
            "runtime.ffmpeg.active" -> "Запуск FFmpeg"
            "runtime.quickjs.active" -> "Выполнение QuickJS"
            "queue.synthetic" -> "Пробная задача очереди"
            "queue.service.active" -> "Запуск фоновой службы"
            "storage.write" -> "Пробная запись в хранилище"
            "editor.onnx.active" -> "Запуск ONNX Runtime"
            "queue.snapshot" -> "Состояния очереди"
            "queue.service_manifest" -> "Регистрация фоновой службы"
            "queue.foreground" -> "Фоновая служба"
            "storage.selected_folder" -> "Папка загрузок"
            "storage.free_space" -> "Свободное пространство"
            "editor.lama.sha256" -> "Целостность модели LaMa"
            "editor.onnx.library" -> "ONNX Runtime"
            else -> "Дополнительная проверка"
        }
    }

    fun state(state: DiagnosticState): String = when (state) {
        DiagnosticState.OK -> "Исправен"
        DiagnosticState.WARNING -> "Предупреждение"
        DiagnosticState.ERROR -> "Ошибка"
        DiagnosticState.UNCHECKED -> "Не проверено"
        DiagnosticState.RUNNING -> "Проверяется"
        DiagnosticState.UNAVAILABLE -> "Не удалось подтвердить"
    }

    fun finding(finding: DiagnosticFinding): String = when {
        finding.reasonCode == "SOURCE_HISTORY_EMPTY" -> "Нет данных"
        finding.reasonCode == "AUTH_NOT_STORED" -> "Сессия не сохранена"
        finding.reasonCode == "SESSION_NOT_STORED" -> "Сессия не сохранена"
        finding.reasonCode == "SESSION_CONFIRMED" -> "Сессия принята"
        finding.reasonCode == "SESSION_LOGIN_REQUIRED" -> "Требуется вход"
        finding.reasonCode == "HTTP_DOCUMENT_REACHABLE" -> "Страница доступна"
        finding.reasonCode == "SERVICE_DECLARED" -> "Есть в APK"
        finding.reasonCode == "SERVICE_IDLE" -> "Нет активной задачи"
        finding.state != DiagnosticState.UNAVAILABLE -> state(finding.state)
        finding.reasonCode in setOf("CHECK_TIMEOUT", "CHECK_EXCEPTION", "WORKFLOW_UNAVAILABLE") ->
            "Не удалось выполнить"
        finding.evidence == DiagnosticEvidence.HISTORICAL -> "Исторические данные"
        finding.evidence == DiagnosticEvidence.OBSERVED -> "Обнаружено"
        else -> "Проверка ограничена"
    }

    fun overall(status: DiagnosticState): String = when (status) {
        DiagnosticState.OK -> "Обязательные проверки пройдены"
        DiagnosticState.UNAVAILABLE -> "Часть функций не подтверждена"
        else -> state(status)
    }
}
