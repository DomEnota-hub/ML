package com.domenota.medialoader.core.diagnostics

/**
 * Platform-independent local-check decisions. Each verified status describes
 * only the exact property observed; no inference about actual downloading.
 */
object LocalDiagnosticRules {
    fun runtimeLibrary(present: Boolean, title: String): DiagnosticOutcome =
        if (present) DiagnosticOutcome(
            DiagnosticState.UNAVAILABLE, DiagnosticEvidence.OBSERVED,
            "Библиотека $title включена в приложение. Запуск исполняемых компонентов не проверялся.",
            reasonCode = "RUNTIME_EXECUTION_UNVERIFIED",
        ) else DiagnosticOutcome(
            DiagnosticState.ERROR, DiagnosticEvidence.VERIFIED,
            "Библиотека $title отсутствует в установленной сборке.",
            reasonCode = "RUNTIME_LIBRARY_MISSING",
            recommendation = "Переустановите подписанную сборку приложения.",
        )

    fun bundledQuickJs(present: Boolean): DiagnosticOutcome =
        if (present) DiagnosticOutcome(
            DiagnosticState.UNAVAILABLE, DiagnosticEvidence.OBSERVED,
            "Библиотека QuickJS обнаружена, но её запуск не проверялся.",
            reasonCode = "QUICKJS_UNVERIFIED",
        ) else DiagnosticOutcome(
            DiagnosticState.WARNING, DiagnosticEvidence.VERIFIED,
            "Нативная библиотека QuickJS не обнаружена по ожидаемому пути.",
            reasonCode = "QUICKJS_NOT_FOUND",
        )

    fun model(present: Boolean, validSize: Boolean, digestMatches: Boolean?): DiagnosticOutcome =
        when {
            !present -> DiagnosticOutcome(
                DiagnosticState.WARNING, DiagnosticEvidence.VERIFIED,
                "Необязательная модель LaMa не установлена.",
                reasonCode = "LAMA_NOT_INSTALLED")
            !validSize -> DiagnosticOutcome(
                DiagnosticState.ERROR, DiagnosticEvidence.VERIFIED,
                "Файл модели LaMa слишком мал и не соответствует условиям установки.",
                reasonCode = "LAMA_INVALID_SIZE")
            digestMatches == null -> DiagnosticOutcome.unavailable(
                "Проверку целостности модели завершить не удалось.", "LAMA_DIGEST_NOT_CHECKED")
            !digestMatches -> DiagnosticOutcome(
                DiagnosticState.ERROR, DiagnosticEvidence.VERIFIED,
                "Контрольная сумма LaMa не совпала с закреплённым значением.",
                reasonCode = "LAMA_DIGEST_MISMATCH",
                recommendation = "Потребуется отдельно подтвердить повторную установку модели.")
            else -> DiagnosticOutcome(
                DiagnosticState.OK, DiagnosticEvidence.VERIFIED,
                "SHA-256 файла LaMa проверен и совпадает с ожидаемым значением.",
                reasonCode = "LAMA_DIGEST_OK")
        }

    data class QueueSnapshot(
        val queued: Int,
        val running: Int,
        val completed: Int,
        val failed: Int,
        val cancelled: Int,
    ) {
        init { require(listOf(queued, running, completed, failed, cancelled).all { it >= 0 }) }
        val active: Int get() = queued + running
    }

    fun queue(snapshot: QueueSnapshot): DiagnosticOutcome =
        DiagnosticOutcome(
            DiagnosticState.OK, DiagnosticEvidence.VERIFIED,
            "Состояния очереди прочитаны: ожидают ${snapshot.queued}, " +
                "выполняются ${snapshot.running}, завершены ${snapshot.completed}, " +
                "ошибок в истории ${snapshot.failed}, отменены ${snapshot.cancelled}. " +
                "Это не проверка скорости или отсутствия зависаний.",
            reasonCode = "QUEUE_SNAPSHOT_READ",
        )

    /** Local service observation describes only this process; Android OS can still revoke it. */
    fun foreground(activeTasks: Int, foregroundObserved: Boolean): DiagnosticOutcome =
        when {
            activeTasks <= 0 -> DiagnosticOutcome(
                DiagnosticState.OK, DiagnosticEvidence.VERIFIED,
                "Активных загрузок нет, служба фоновой передачи сейчас не требуется.",
                reasonCode = "SERVICE_IDLE")
            foregroundObserved -> DiagnosticOutcome(
                DiagnosticState.UNAVAILABLE, DiagnosticEvidence.OBSERVED,
                "В текущем процессе зарегистрирован запуск foreground-службы. " +
                    "Её состояние в Android и устойчивость фоновой работы не подтверждены.",
                reasonCode = "SERVICE_FOREGROUND_OBSERVED")
            else -> DiagnosticOutcome.unavailable(
                "Есть ожидающие или активные задания, но запуск фоновой службы " +
                    "не подтверждён наблюдениями текущего процесса.",
                "SERVICE_STATUS_UNKNOWN")
        }

    fun selectedFolder(
        hasPersistedRead: Boolean,
        hasPersistedWrite: Boolean,
        exists: Boolean,
        isDirectory: Boolean,
        canRead: Boolean,
        canWrite: Boolean,
    ): DiagnosticOutcome = when {
        !hasPersistedRead || !hasPersistedWrite -> DiagnosticOutcome(
            DiagnosticState.ERROR, DiagnosticEvidence.VERIFIED,
            "Для выбранной папки отсутствует сохранённое разрешение на чтение или запись.",
            "FOLDER_PERMISSION_MISSING",
            "Повторно выберите папку в настройках.")
        !exists || !isDirectory -> DiagnosticOutcome(
            DiagnosticState.ERROR, DiagnosticEvidence.VERIFIED,
            "Выбранная папка недоступна или более не существует.",
            "FOLDER_NOT_AVAILABLE",
            "Повторно выберите папку в настройках.")
        !canRead || !canWrite -> DiagnosticOutcome(
            DiagnosticState.ERROR, DiagnosticEvidence.VERIFIED,
            "Поставщик документов не подтверждает чтение и запись в выбранную папку.",
            "FOLDER_ACCESS_DENIED",
            "Проверьте доступ к папке в системном файловом менеджере.")
        else -> DiagnosticOutcome(
            DiagnosticState.UNAVAILABLE, DiagnosticEvidence.OBSERVED,
            "Метаданные папки и разрешения доступны. Реальная запись файла не проверялась.",
            "FOLDER_WRITE_UNVERIFIED")
    }

    fun standardStorage(): DiagnosticOutcome = DiagnosticOutcome.unavailable(
        "Используется системная папка загрузок. " +
            "Без создания тестового файла невозможно подтвердить возможность записи.",
        "MEDIASTORE_WRITE_UNVERIFIED")

    fun freeSpace(availableBytes: Long): DiagnosticOutcome = when {
        availableBytes < 0L -> DiagnosticOutcome.unavailable(
            "Система не сообщила доступный объём хранилища.", "FREE_SPACE_UNKNOWN")
        availableBytes < 100L * 1024 * 1024 -> DiagnosticOutcome(
            DiagnosticState.WARNING, DiagnosticEvidence.VERIFIED,
            "Свободно менее 100 МБ. Большие загрузки и обработка файлов могут не завершиться.",
            "FREE_SPACE_LOW")
        else -> DiagnosticOutcome(
            DiagnosticState.OK, DiagnosticEvidence.VERIFIED,
            "Система сообщает доступное место в хранилище. " +
                "Это не подтверждает разрешение записи в папку загрузок.",
            "FREE_SPACE_AVAILABLE")
    }
}
