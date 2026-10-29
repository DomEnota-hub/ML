package com.domenota.medialoader.core.diagnostics

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Explicit allow-list Markdown export. NEVER interpolate raw probe explanations,
 * opaque IDs, operation references, URLs, file paths, cookies, exception messages,
 * user search phrases or raw AppLog lines. This is independent of AppLog's full
 * technical .md export, which intentionally has a different privacy boundary.
 */
object SafeDiagnosticReport {
    data class Input(
        val specs: List<DiagnosticCheckSpec>,
        val lastCompletedRun: DiagnosticRun?,
        val incidents: List<FaultRecord>,
        val generatedAtMillis: Long,
        val versionName: String,
        val versionCode: Int,
        val androidSdk: Int,
        val latestRunReadable: Boolean = true,
        val faultJournalReadable: Boolean = true,
    )

    private val components = listOf(
        DiagnosticComponent.SOURCES,
        DiagnosticComponent.MEDIA_RUNTIME,
        DiagnosticComponent.DOWNLOAD_QUEUE,
        DiagnosticComponent.STORAGE,
        DiagnosticComponent.PHOTO_EDITOR,
    )

    fun render(input: Input): String {
        require(input.generatedAtMillis >= 0)
        require(input.specs.size <= 100 && input.incidents.size <= FaultJournal.MAX_RECORDS)
        require(input.specs.map { it.code }.distinct().size == input.specs.size)
        val safeVersion = input.versionName.takeIf {
            it.matches(Regex("[A-Za-z0-9._-]{1,32}"))
        } ?: "не указана"
        val activeSpecs = DiagnosticModeChecks.visible(input.specs, input.lastCompletedRun?.mode)
        val byCode = input.lastCompletedRun?.findings
            ?.filter { finding -> activeSpecs.any { it.code == finding.spec.code } }
            ?.associateBy { it.spec.code }.orEmpty()
        val visibleFindings = activeSpecs.mapNotNull { byCode[it.code] }
        val summary = DiagnosticAssessment.application(
            activeSpecs, visibleFindings, input.generatedAtMillis)
        val mode = when (input.lastCompletedRun?.mode) {
            DiagnosticMode.LOCAL -> "Только локальная диагностика"
            DiagnosticMode.WITH_NETWORK -> "Локальная и анонимная сетевая диагностика"
            DiagnosticMode.FULL -> "Полная функциональная диагностика"
            null -> if (input.latestRunReadable) "Проверка ещё не выполнялась"
                else "Историю проверок прочитать не удалось"
        }
        val result = StringBuilder(16_384)
        result.appendLine("# Диагностический отчёт MediaLoader")
        result.appendLine()
        result.appendLine("- Сформирован: ${whenUtc(input.generatedAtMillis)}")
        result.appendLine("- Приложение: MediaLoader $safeVersion (${input.versionCode.coerceAtLeast(0)})")
        result.appendLine("- Android SDK: ${input.androidSdk.takeIf { it in 1..200 } ?: 0}")
        result.appendLine("- Режим последней проверки: $mode")
        result.appendLine("- Последняя завершённая проверка: " +
            (input.lastCompletedRun?.finishedAtMillis?.let(::whenUtc)
                ?: if (input.latestRunReadable) "нет" else "данные недоступны"))
        result.appendLine("- Общий статус: ${DiagnosticLabels.overall(summary.state)}")
        result.appendLine("- Выполнено проб: ${summary.freshResults} / ${summary.totalChecks}")
        result.appendLine("- Подтверждено отдельных этапов: ${DiagnosticPresentation.confirmedGoodCount(visibleFindings)}")
        if (input.lastCompletedRun?.mode == DiagnosticMode.FULL) {
            val workflows = visibleFindings.filter { it.spec.code.startsWith("source.") &&
                it.spec.code.endsWith(".workflow") }
            result.appendLine("- Получены метаданные источников: ${workflows.count { it.state == DiagnosticState.OK }} / ${workflows.size}")
        }
        result.appendLine("- Устаревших результатов: ${summary.expiredResults}")
        result.appendLine()
        result.appendLine("> Отчёт содержит только разрешённые диагностические сведения. " +
            "Cookies, токены, адреса файлов, ссылки, имена загрузок, ID операций и " +
            "содержимое технического журнала не включаются. Сам отчёт может " +
            "раскрывать версию приложения и диагностические симптомы.")
        result.appendLine()
        result.appendLine("## Результаты по подсистемам")
        for (component in components) {
            val own = activeSpecs.filter { it.component == component }
            if (own.isEmpty()) continue
            val componentSummary = DiagnosticAssessment.component(
                component, activeSpecs, visibleFindings, input.generatedAtMillis)
            result.appendLine()
            result.appendLine("### ${FaultExplanations.componentTitle(component)} — " +
                DiagnosticLabels.state(componentSummary.state))
            result.appendLine()
            own.forEach { spec ->
                val finding = byCode[spec.code]
                val stale = finding?.checkedAtMillis?.let {
                    finding.isStale(input.generatedAtMillis)
                } ?: false
                val state = if (stale) DiagnosticState.UNCHECKED else
                    finding?.state ?: DiagnosticState.UNCHECKED
                val superseded = !stale && spec.code == "storage.selected_folder" &&
                    byCode["storage.write"]?.state == DiagnosticState.OK
                val label = DiagnosticLabels.check(spec)
                result.append("- **$label:** ")
                    .append(if (stale) "Устарело — нужна повторная проверка"
                        else if (superseded) "Запись подтверждена поздней пробой"
                        else finding?.let(DiagnosticLabels::finding) ?: DiagnosticLabels.state(state))
                    .appendLine(".")
                if (!stale && finding != null) {
                    if (input.lastCompletedRun?.mode != DiagnosticMode.FULL)
                        result.appendLine("  - ${safeEvidence(finding.evidence)}")
                    result.appendLine("  - ${if (superseded) "Позднее пробный файл успешно записан и прочитан в папке загрузок." else safeNote(finding)}")
                } else if (finding == null) {
                    result.appendLine("  - Проверка не выполнялась в последнем завершённом запуске.")
                }
                if (finding?.checkedAtMillis != null)
                    result.appendLine("  - Проверено: ${whenUtc(finding.checkedAtMillis)}" +
                        (finding.durationMillis?.let { " · ${it} мс" } ?: ""))
            }
        }

        result.appendLine()
        result.appendLine("## Журнал неисправностей")
        result.appendLine()
        result.appendLine("Это отдельная история подтверждённых диагностикой отклонений, " +
            "не исходный технический журнал.")
        result.appendLine()
        if (!input.faultJournalReadable) {
            result.appendLine("Историю неисправностей прочитать не удалось. " +
                "Отсутствие записей не подтверждено; сохранённые данные не удалялись.")
        } else if (input.incidents.isEmpty()) {
            result.appendLine("Записей нет. Это не означает, что приложение исправно.")
        } else {
            result.appendLine("- Требуют внимания: ${input.incidents.count {
                it.status != FaultStatus.RESOLVED
            }}")
            result.appendLine("- Всего сохранено: ${input.incidents.size}")
            result.appendLine()
            input.incidents.sortedByDescending { it.lastSeenMillis }.forEach { record ->
                val description = FaultExplanations.forRecord(record)
                result.appendLine("### ${description.title}")
                result.appendLine()
                result.appendLine("- Компонент: ${FaultExplanations.componentTitle(record.component)}")
                result.appendLine("- Статус: ${faultStatus(record.status)}")
                result.appendLine("- Уровень: ${if (record.severity == FaultSeverity.ERROR) "Ошибка" else "Предупреждение"}")
                result.appendLine("- Впервые: ${whenUtc(record.firstSeenMillis)}")
                result.appendLine("- Последний раз: ${whenUtc(record.lastSeenMillis)}")
                result.appendLine("- Повторений: ${record.repetitions}")
                record.resolvedAtMillis?.let {
                    result.appendLine("- Устранено: ${whenUtc(it)}")
                }
                result.appendLine("- Пояснение: ${description.explanation}")
                if (record.status != FaultStatus.RESOLVED) description.advice?.let {
                    result.appendLine("- Рекомендация: $it")
                }
                result.appendLine()
            }
        }
        if (input.lastCompletedRun?.mode != DiagnosticMode.FULL) {
            result.appendLine("---")
            result.appendLine("Ограничение: успешный HTTP-ответ подтверждает лишь доступность " +
                "документа, а не скачивание; сохранённая сессия не доказывает вход, " +
                "а сведения о папке без пробной записи не доказывают доступ к ней. Отказ сайта может зависеть " +
                "от IP/VPN и сам по себе не доказывает неисправность приложения.")
        }
        return result.toString()
    }

    /** A fixed statement is chosen only from a stable validated diagnostic reason. */
    private fun safeNote(finding: DiagnosticFinding): String = when (finding.reasonCode) {
        "HTTP_DOCUMENT_REACHABLE" ->
            "Сайт ответил на анонимный запрос. Поиск, авторизация и загрузка не проверялись."
        "HTTP_ACCESS_DENIED" ->
            "HTTP 403: сервер отказал в доступе. Причина неизвестна; возможны ограничения IP/VPN или сессии."
        "HTTP_AUTH_REQUIRED" -> "HTTP 401: для анонимного запроса требуется авторизация."
        "HTTP_RATE_LIMIT" -> "HTTP 429: сервер ограничил частоту запросов."
        "HTTP_REMOTE_FAILURE" -> "Сервер вернул ответ 5xx. Причина на стороне приложения не доказана."
        "HTTP_REQUEST_REJECTED" -> "Сервер отклонил запрос. Скачивание не проверялось."
        "HTTP_REDIRECT_NOT_FOLLOWED" -> "Сайт предложил перенаправление; переход не проверялся."
        "AUTH_SERVER_NOT_VERIFIED" -> "Локальные данные сессии есть; авторизация сервером не подтверждена."
        "AUTH_NOT_STORED" -> "Сохранённая сессия не найдена; публичные материалы могут оставаться доступны."
        "SESSION_CONFIRMED" -> "Сервер принял сохранённую сессию загрузчика."
        "SESSION_LOGIN_REQUIRED" -> "Сервер запросил повторный вход; сохранённые данные не удалены."
        "SESSION_NOT_STORED" -> "Сессия не сохранена; серверная проверка входа не выполнялась."
        "SESSION_INCONCLUSIVE" ->
            "Ответ сервера не позволил подтвердить вход; сохранённые данные не изменялись."
        "SESSION_PROFILE_UNRECOGNIZED" -> "Профиль не распознан в ответе сервера; доступ к материалу проверяется отдельно."
        "SESSION_LOGIN_PAGE" -> "Вместо профиля получена страница входа."
        "SESSION_HTTP_403" -> "Проверка профиля ограничена сервером (HTTP 403); сессия не удалена."
        "SESSION_HTTP_429" -> "Проверка профиля ограничена сервером (HTTP 429); сессия не удалена."
        "SESSION_TIMEOUT", "SESSION_DNS", "SESSION_TLS", "SESSION_REQUEST_FAILED" ->
            "Сетевая проверка профиля не завершилась; сессия не удалена."
        "SOURCE_HISTORY_ONLY" -> safeHistoryCounts(finding.explanation)
        "SOURCE_HISTORY_EMPTY" -> "Для источника отсутствуют доступные записи истории загрузок."
        "QUEUE_SNAPSHOT_READ" -> safeQueueCounts(finding.explanation)
        "SERVICE_DECLARED" -> "Фоновая служба присутствует в APK; работоспособность не проверена."
        "SERVICE_NOT_DECLARED" -> "Фоновая служба не обнаружена в составе установленного приложения."
        "SERVICE_IDLE" -> "Активных загрузок нет; фоновая служба может не работать."
        "SERVICE_FOREGROUND_OBSERVED" -> "В текущем процессе наблюдался запуск службы; устойчивость не проверена."
        "SERVICE_STATUS_UNKNOWN" -> "Запуск фоновой службы не подтверждён."
        "FOLDER_PERMISSION_MISSING" -> "Нет сохранённого разрешения на чтение или запись выбранной папки."
        "FOLDER_ACCESS_DENIED" -> "Системный поставщик документов не подтвердил доступ к папке."
        "FOLDER_NOT_AVAILABLE", "FOLDER_URI_INVALID" -> "Выбранная папка недоступна."
        "FOLDER_PROVIDER_ERROR" -> "Получить сведения от поставщика документов не удалось."
        "FOLDER_WRITE_UNVERIFIED" -> "Права и метаданные доступны; тестовая запись не выполнялась."
        "MEDIASTORE_WRITE_UNVERIFIED" -> "Запись в системную папку загрузок не проверялась."
        "FREE_SPACE_LOW" -> "Менее 100 МБ доступного места во внутреннем хранилище."
        "FREE_SPACE_AVAILABLE" -> "Свободное место есть; права на запись в папку не проверены."
        "FREE_SPACE_UNKNOWN" -> "Свободное пространство определить не удалось."
        "RUNTIME_LIBRARY_MISSING", "ONNX_LIBRARY_MISSING" ->
            "Ожидаемая библиотека отсутствует в установленной сборке."
        "RUNTIME_EXECUTION_UNVERIFIED", "QUICKJS_UNVERIFIED", "ONNX_SESSION_UNVERIFIED" ->
            "Библиотека найдена, но успешное выполнение не подтверждено."
        "RUNTIME_CLASS_UNAVAILABLE", "ONNX_CLASS_UNAVAILABLE", "QUICKJS_NOT_FOUND" ->
            "Наличие или готовность компонента подтвердить не удалось."
        "LAMA_NOT_INSTALLED" -> "Необязательная модель LaMa не установлена."
        "LAMA_INVALID_SIZE", "LAMA_DIGEST_MISMATCH" ->
            "Файл модели LaMa не прошёл проверку целостности."
        "LAMA_DIGEST_OK" -> "SHA-256 модели LaMa совпал с ожидаемым значением."
        "LAMA_DIGEST_NOT_CHECKED" -> "Контрольную сумму модели получить не удалось."
        "CHECK_TIMEOUT" -> "Диагностическая проверка превысила лимит времени."
        "CHECK_EXCEPTION" -> "Сама диагностическая проверка завершилась с ошибкой."
        "NETWORK_NOT_CONNECTED" -> "На устройстве нет доступного сетевого подключения."
        "NETWORK_DNS_FAILED" -> "Не удалось разрешить доменное имя сервера."
        "NETWORK_TIMEOUT" -> "Сервер не ответил вовремя."
        "NETWORK_TLS_FAILED" -> "Не удалось установить защищённое соединение."
        "NETWORK_PRIVACY_GUARD" -> "Запрос не выполнялся из-за ограничений конфиденциальности."
        "NETWORK_CHECK_UNAVAILABLE" -> "Сетевая проверка не завершилась; причина неизвестна."
        "YTDLP_STARTED" -> "yt-dlp инициализирован и ответил на запрос версии; извлечение проверяется отдельно."
        "YTDLP_VERSION_UNKNOWN" -> "yt-dlp запущен, но версия не определена."
        "YTDLP_START_FAILED" -> "Запустить yt-dlp не удалось."
        "FFMPEG_STARTED" -> "FFmpeg инициализирован; обработка файла не выполнялась."
        "FFMPEG_START_FAILED" -> "FFmpeg не удалось инициализировать."
        "QUICKJS_SCRIPT_UNVERIFIED" -> "Нативная библиотека QuickJS загружена; скрипт не выполнялся."
        "QUICKJS_EXEC_OK" -> "QuickJS выполнил контрольный скрипт; использование в yt-dlp не проверялось."
        "QUICKJS_EXEC_TIMEOUT" -> "Контрольный скрипт QuickJS не завершился вовремя."
        "QUICKJS_EXEC_FAILED" -> "QuickJS не вернул ожидаемый результат."
        "QUICKJS_EXEC_UNAVAILABLE" -> "Android не позволил запустить контрольный скрипт QuickJS."
        "QUICKJS_LOAD_FAILED" -> "QuickJS не удалось загрузить."
        "QUEUE_SYNTHETIC_COMPLETE" -> "Изолированная пробная задача прошла через очередь."
        "QUEUE_SYNTHETIC_FAILED", "QUEUE_SYNTHETIC_UNAVAILABLE" ->
            "Изолированную пробную задачу завершить не удалось."
        "SERVICE_STARTED" -> "Foreground-служба запущена; долгая работа в фоне не проверялась."
        "SERVICE_START_UNAVAILABLE" -> "Запуск foreground-службы не подтверждён."
        "STORAGE_WRITE_OK" -> "Пробный файл записан и прочитан из папки загрузок."
        "STORAGE_READ_FAILED", "STORAGE_WRITE_FAILED" ->
            "Пробная запись или чтение из папки загрузок не удались."
        "STORAGE_CLEANUP_FAILED" -> "Пробная запись прошла, но временный файл удалить не удалось."
        "ONNX_MODEL_MISSING" -> "ONNX Runtime запущен, модель LaMa отсутствует."
        "ONNX_SESSION_STARTED" -> "Сеанс ONNX с моделью LaMa создан; обработка изображения не выполнялась."
        "ONNX_INPUT_MISSING", "ONNX_LOAD_FAILED", "ONNX_SESSION_FAILED" ->
            "Запуск сеанса ONNX не удался."
        "WORKFLOW_METADATA_OK" -> "Получены метаданные контрольного материала."
        "WORKFLOW_EMPTY" -> "Рабочий поиск не вернул результатов; контрольный материал мог измениться."
        "WORKFLOW_ACCESS_LIMITED" -> "Рабочий сценарий ограничен доступом, VPN или сессией."
        "WORKFLOW_AUTH_REQUIRED" -> "Контрольный материал требует входа; сохранённая сессия не подтвердила доступ к нему."
        "WORKFLOW_CONTENT_UNAVAILABLE" -> "Контрольный материал недоступен или не поддерживается."
        "WORKFLOW_RATE_LIMITED" -> "Источник ограничил частоту запросов."
        "MUZMO_PAGE_TIMEOUT" -> "Страница резервного поиска не открылась за 12 секунд."
        "MUZMO_RESULTS_TIMEOUT" -> "Страница открылась, но результаты не появились за 16 секунд."
        "MUZMO_DOM_CALLBACK_TIMEOUT" -> "Страница открылась, но скрипт поиска не ответил за 16 секунд."
        "MUZMO_SCRIPT_PARSE_FAILED" -> "Скрипт поиска вернул данные неизвестного формата."
        "MUZMO_DOM_STRUCTURE_UNKNOWN" -> "Страница загрузилась, но результаты не обнаружены в ожидаемой структуре."
        "WORKFLOW_UNAVAILABLE" -> "Рабочий сценарий не завершился; нужна проверка технического журнала."
        else -> "Проверка не предоставила дополнительных разрешённых для экспорта сведений."
    }

    /** Extract only bounded numeric counters, never append any source text. */
    private fun safeHistoryCounts(raw: String): String {
        val parsed = Regex("""В доступной истории: завершено ([0-9]{1,7}), ошибок ([0-9]{1,7}), отменено ([0-9]{1,7})""")
            .find(raw)?.groupValues
        return if (parsed != null)
            "История: завершено ${parsed[1]}, ошибок ${parsed[2]}, отменено ${parsed[3]}. " +
                "Текущая работоспособность неизвестна."
        else "Исторические записи есть; текущее состояние источника не подтверждено."
    }

    private fun safeQueueCounts(raw: String): String {
        val parsed = Regex("""очереди прочитаны: ожидают ([0-9]{1,7}), выполняются ([0-9]{1,7}), завершены ([0-9]{1,7}), ошибок в истории ([0-9]{1,7}), отменены ([0-9]{1,7})""")
            .find(raw)?.groupValues
        return if (parsed != null)
            "Очередь: ожидают ${parsed[1]}, выполняются ${parsed[2]}, завершены ${parsed[3]}, " +
                "ошибки в истории ${parsed[4]}, отменены ${parsed[5]}. " +
                "Отсутствие зависаний не проверялось."
        else "Состояние очереди прочитано; отсутствие зависаний не проверялось."
    }

    private fun safeEvidence(evidence: DiagnosticEvidence): String = when (evidence) {
        DiagnosticEvidence.VERIFIED -> "Результат подтверждён выполненной проверкой."
        DiagnosticEvidence.OBSERVED -> "Есть только косвенные локальные признаки; функция не испытана."
        DiagnosticEvidence.HISTORICAL -> "Данные исторические, а не текущая проверка."
        DiagnosticEvidence.NONE -> "Подтверждающих данных не получено."
    }

    private fun faultStatus(status: FaultStatus): String = when (status) {
        FaultStatus.ACTIVE -> "Требует внимания"
        FaultStatus.NEEDS_RECHECK -> "Требуется повторная проверка"
        FaultStatus.RESOLVED -> "Устранение подтверждено"
    }

    private fun whenUtc(timestamp: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(timestamp))
}
