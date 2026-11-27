package com.domenota.medialoader.core.logging

/**
 * Presentation-only rules. Event names and enum labels are allowlisted.
 * Never render arbitrary field values, URLs, file names, paths or IDs.
 * Unknown values do not imply success, failure, or a root cause.
 */
internal object StructuredLogExplanation {
    private val inlineField = Regex("""(?:^|[ ,])([A-Za-z][A-Za-z0-9_]*)=([A-Za-z0-9_-]+)(?=$|[ ,])""")
    private val firstWord = Regex("""^[A-Za-z_][A-Za-z0-9_]*""")
    private val inlineTags = setOf("Perf", "OCR", "Settings", "VideoPlayer", "GallerySearch",
        "MediaNotes", "DuplicateScanMetrics", "DuplicateUI", "FolderVisibility", "NOMEDIA_SAF",
        "VideoEditorNav", "MusicCompare", "MusicDuplicates", "PhotoOCR", "PhotoCutout", "AiOrtPerf",
        "AudioWaveform", "App")
    private class Fields(entry: DisplayLogEntry) {
        private val values = mutableMapOf<String, String>()
        private val ambiguous = mutableSetOf<String>()
        init {
            fun add(key: String, value: String) {
                if (key in ambiguous) return
                if (values.putIfAbsent(key, value) != null) {
                    values.remove(key)
                    ambiguous.add(key)
                }
            }
            for (segment in entry.metadata.split(" · ")) {
                val key = segment.substringBefore('=', "")
                if (key.matches(Regex("[A-Za-z][A-Za-z0-9_]*"))) add(key, segment.substringAfter('='))
            }
            if (entry.tag in inlineTags) {
                for (match in inlineField.findAll(entry.event)) add(match.groupValues[1], match.groupValues[2])
            }
        }
        operator fun get(key: String): String? = values[key]
        fun number(key: String): Long? = values[key]?.takeIf { it.length <= 19 && it.all(Char::isDigit) }
            ?.toLongOrNull()?.takeIf { it >= 0 }
        fun label(key: String, labels: Map<String, String>): String? = labels[values[key]]
        fun metric(key: String, label: String, unit: String = ""): String? =
            number(key)?.let { label + ": " + it + unit }
        fun bool(key: String, yes: String, no: String): String? = when (values[key]) {
            "true" -> yes
            "false" -> no
            else -> null
        }
    }
    private fun join(vararg parts: String?): String = parts.filterNotNull().joinToString(" · ")
    private fun Fields.countProgress(doneKey: String, totalKey: String, title: String): String? {
        val done = number(doneKey) ?: return null
        val total = number(totalKey)?.takeIf { it >= done }
        return title + ": " + done + (total?.let { " из " + it } ?: "")
    }
    private val modes = mapOf("GALLERY" to "Галерея", "MUSIC" to "Музыка", "DOWNLOADER" to "Загрузки")
    private val scanReasons = mapOf("REQUEST" to "По запросу", "MEDIASTORE_CHANGE" to "Изменение файлов телефона",
        "ACCESS_CHANGED" to "Изменение доступа", "RECOVERY" to "Восстановление каталога")
    private val phases = mapOf("RUNNING" to "Выполняется", "CANCELLED" to "Сеанс отменён",
        "COMPLETE" to "Завершено", "COMPLETED" to "Завершено", "FAILED" to "Ошибка",
        "WAITING" to "Ожидание", "START" to "Начато", "CACHE" to "Получены данные кеша",
        "PUBLISHED" to "Результат опубликован", "PARTIAL" to "Частично завершено", "PAUSED" to "На паузе")
    private val blocks = mapOf("CATALOG" to "Каталог", "RECENT" to "Недавние", "BROWSE" to "Просмотр", "RANK" to "Рейтинг")
    private val guards = mapOf("ALIASES" to "Связи файлов", "PUBLIC_ROWS" to "Открытая медиатека",
        "AUDIO_SESSION" to "Аудиосессия", "ASSIGNMENTS" to "Связи коллекций",
        "GALLERY_BROWSE" to "Галерея", "MUSIC_BROWSE" to "Музыка", "OTHER" to "Другие данные")
    private val guardReasons = mapOf("DISABLED" to "Механизм отключён", "ACCESS" to "Проверка доступа",
        "PRIVACY" to "Проверка приватности", "GUARD_TRANSITION" to "Изменение условий доступа")
    private val groups = mapOf(
        "Perf" to "Производительность", "Library" to "Медиатека", "OCR" to "Распознавание текста",
        "GallerySearch" to "Поиск в галерее", "PhotoOCR" to "Распознавание текста",
        "ProcessExit" to "Система", "Audio" to "Музыка", "AudioRoute" to "Музыка",
        "AudioWaveform" to "Звуковые волны", "AudioReplace" to "Музыка",
        "MusicCompare" to "Дубликаты музыки", "MusicDuplicates" to "Дубликаты музыки",
        "DuplicateScanMetrics" to "Поиск дубликатов", "DuplicateUI" to "Поиск дубликатов",
        "DuplicatePrivacy" to "Защита данных", "Duplicate" to "Дубликаты",
        "FolderVisibility" to "Архив", "NOMEDIA_SAF" to "Архив",
        "OldDownloads" to "История загрузок", "History" to "История загрузок",
        "MissingRecovery" to "Восстановление", "Trash" to "Корзина", "Gallery" to "Галерея",
        "VideoPlayer" to "Видеоплеер", "FloatingVideo" to "Видеоплеер", "Viewer" to "Просмотр медиа",
        "VideoRecognition" to "Распознавание музыки", "Editor" to "Редактор",
        "VideoEditorNav" to "Редактор видео", "VideoPreview" to "Редактор видео",
        "PhotoCutout" to "Редактор фото", "AiOrtPerf" to "AI-модели", "MediaNotes" to "Заметки",
        "Browser" to "Браузер", "BrowserWebView" to "Браузер", "HitMozWebView" to "Поиск музыки",
        "MuzmoWebView" to "Поиск музыки", "YouTube" to "YouTube", "Instagram" to "Instagram",
        "InstagramProfile" to "Instagram", "GapiUsage" to "Сетевой запрос", "Resolve" to "Обработка ссылки",
        "Share" to "Получение ссылки", "Diagnostics" to "Диагностика")
    fun group(tag: String): String = groups[tag] ?: "Другие события"

    fun readable(entry: DisplayLogEntry): String? {
        val f = Fields(entry)
        val event = entry.event
        val key = firstWord.find(event)?.value
        fun metric(k: String, label: String, unit: String = "") = f.metric(k, label, unit)
        fun mode() = f.label("mode", modes)
        fun ms() = metric("ms", "Длительность", " мс")
        fun elapsed() = metric("elapsedMs", "Длительность", " мс")
        fun phase() = f.label("phase", phases)
        fun exact(vararg pairs: Pair<String, String>) = pairs.firstOrNull { it.first == event }?.second
        return when (entry.tag) {
            "DownloadProgress" -> if (event.startsWith("id=") || event == "Progress")
                join("Получение данных файла", f.countProgress("bytes", "total", "Загружено байт")) else null
            "Library" -> when (event) {
                "Phone scan started" -> join("Начато сканирование файлов телефона", mode(), f.label("reason", scanReasons), metric("retained", "Предыдущий каталог"))
                "Phone scan finished" -> join("Сканирование файлов телефона завершено", mode(), f.label("reason", scanReasons), metric("count", "Найдено файлов"), ms())
                "Phone snapshot unchanged" -> join("Список файлов телефона не изменился", mode())
                "Phone scan skipped" -> join("Повторное сканирование пропущено", mode(), f.label("reason", scanReasons), if (f["stamp"] == "unchanged") "Отметка изменений прежняя" else null)
                "Phone scan cancelled" -> join("Сканирование файлов отменено", mode(), f.label("reason", scanReasons))
                "Audio catalog ready" -> join("Каталог аудиофайлов подготовлен", metric("count", "Записей"))
                "App identity index ready" -> join("Индекс связей файлов приложения подготовлен", metric("count", "Записей"))
                else -> if (key == "RECOVERY_CATALOG_RESULT") join("Результат проверки каталога после восстановления", f.bool("verified", "Проверка подтверждена", "Проверка не подтверждена")) else null
            }
            "OCR" -> if (key == "OCR_RUN") join("Индексация текста на фотографиях", phase(),
                f.countProgress("indexed", "total", "Проиндексировано"), metric("failed", "Не обработано"),
                metric("unverified", "Не проверено"), ms()) else null
            "Perf" -> when (key) {
                "UI_STALL" -> join("Зафиксирована задержка интерфейса", metric("blockedMs", "Задержка", " мс"), metric("heapUsedMiB", "Память приложения", " МиБ"))
                "UI_RECOVERED" -> join("Отзывчивость интерфейса восстановлена", metric("blockedMs", "Длительность задержки", " мс"))
                "FRAME_GAPS" -> join("Измерены интервалы между кадрами интерфейса", metric("windowMs", "Период наблюдения", " мс"), metric("over50ms", "Интервалов более 50 мс"), metric("worstMs", "Максимум", " мс"))
                "DETAIL" -> join("Снимок показателей памяти и работы приложения", metric("heapUsedMiB", "Использовано", " МиБ"), metric("heapMaxMiB", "Лимит", " МиБ"), metric("gcCount", "Сборок мусора"), metric("gcBlockingTimeMs", "Блокировки сборки мусора", " мс"))
                "READ_MODEL_GUARD" -> join("Проверка условий публикации данных", f.label("kind", guards), f.label("reason", guardReasons), f.bool("retained", "Прежние данные сохранены", "Прежние данные не сохранены"), metric("repeatedSuppressed", "Свернутых повторов"))
                "MUSIC_READ_MODEL" -> join("Состояние данных музыкального раздела", f.label("block", blocks), f.bool("ready", "Готово", "Не готово"), f.bool("loading", "Идёт подготовка", nullText), f.bool("error", "Отмечена ошибка", "Ошибок не отмечено"), metric("count", "Записей"))
                "MUSIC_REFRESH" -> join("Обновление данных музыки", f.label("block", blocks), phase(), metric("count", "Записей"), ms())
                "BEGIN", "END" -> join(if (key == "BEGIN") "Начато измерение операции" else "Завершено измерение операции", actionLabel(f["action"]), ms())
                "LIBRARY_WORK" -> join("Счётчики работы медиатеки", metric("MEDIASTORE_SCAN", "Сканирований"), metric("MEDIASTORE_QUERY", "Запросов"), metric("THUMBNAIL_DECODE", "Миниатюр"), metric("LIBRARY_INDEX_BUILD", "Построений индекса"))
                "LIBRARY_PROJECTIONS" -> "Снимок показателей подготовки данных медиатеки. Подробные счётчики сохранены в технической записи."
                "RECOVERY_GATE" -> join("Проверка условий восстановления каталога", metric("activeScans", "Активных сканирований"))
                "RECOVERY_REFRESH_REQUEST" -> join("Запрошено обновление каталога после восстановления", metric("pendingMediaChanges", "Ожидающих изменений"))
                "RECOVERY_SCAN_DECISION" -> join("Определены условия сканирования при восстановлении", mode(), f.bool("force", "Принудительное обновление", "Обычное обновление"))
                else -> null
            }
            "ProcessExit" -> if (event == "Previous process exit") join("Android зарегистрировал завершение предыдущего процесса",
                f.label("type", mapOf("LOW_MEMORY" to "Нехватка памяти", "ANR" to "Приложение не отвечало",
                    "CRASH" to "Аварийное завершение", "CRASH_NATIVE" to "Сбой нативного кода",
                    "USER_REQUESTED" to "Запрошено пользователем")) ?: "Причина не расшифрована",
                metric("pssKiB", "Память PSS", " КиБ")) else null
            "Settings" -> when (key) {
                "THEME" -> join("Изменено оформление приложения", f.label("selected", mapOf("dark" to "Тёмное", "light" to "Светлое", "system" to "Системное")))
                "OCR_ENABLED" -> join("Настройка распознавания текста", f.bool("value", "Включено", "Выключено"))
                "OCR_CHARGE_ONLY" -> join("Условие фоновой индексации", f.bool("value", "Только при зарядке", "Не зависит от зарядки"))
                "TASK_OPTIONS" -> "Изменена настройка Центра задач"
                "RECOVERY_LAB" -> join("Инструмент восстановления", f.bool("open", "Открыт", "Закрыт"))
                else -> null
            }
            "GallerySearch" -> if (key == "SEARCH") join("Поиск по фото и видео", metric("filename", "По имени"), metric("notes", "По заметкам"), metric("ocr", "По распознанному тексту"), metric("total", "Всего"), f.label("ocrStatus", phases)) else null
            "MediaNotes" -> if (key == "NOTE_SAVE") join("Операция сохранения заметки", f.label("status", phases), f.label("action", mapOf("CREATE" to "Создание", "UPDATE" to "Изменение", "DELETE" to "Удаление")), ms()) else null
            "AudioWaveform" -> when {
                event.startsWith("Could not decode peaks") -> "Не удалось декодировать данные для звуковой волны."
                key == "BOOKMARK_WAVEFORM" -> join("Звуковая волна в редакторе закладок", f.label("cache", mapOf("HIT" to "Использован кеш", "MISS" to "В кеше не найдено")))
                else -> null
            }
            "MusicCompare" -> when {
                event.startsWith("frames decoded ") -> join("Декодированы фрагменты аудио для сравнения", metric("count", "Фрагментов"), elapsed())
                event.startsWith("frames cache ") -> join("Получены фрагменты аудио из кеша", metric("count", "Фрагментов"), elapsed())
                event.startsWith("alignment ") -> join("Сопоставление аудиофрагментов", f.bool("aligned", "Выравнивание подтверждено", "Выравнивание не подтверждено"), elapsed())
                event.startsWith("pair ready ") -> join("Пара аудиозаписей подготовлена для сравнения", elapsed())
                event.startsWith("verified pair cache MISS") -> "Проверенная пара не найдена в кеше сравнения."
                key == "fullPcm" -> join("Сравнение декодированных аудиоданных", metric("firstSamples", "Отсчётов первого"), metric("secondSamples", "Отсчётов второго"))
                else -> null
            }
            "DuplicateScanMetrics" -> if (key == "music") join("Этап поиска дубликатов музыки", metric("processed", "Обработано"), metric("skipped", "Пропущено"), elapsed()) else null
            "MusicDuplicates" -> if (key == "SCAN_HEAP") join("Оценка памяти для поиска дубликатов", metric("heapMiB", "Память", " МиБ")) else null
            "DuplicatePrivacy" -> if (key == "PRIVATE_MOVE") "Обновление отчётов дубликатов при изменении приватности." else null
            "DuplicateUI" -> join("Состояние интерфейса поиска дубликатов", f.label("event", mapOf("enter" to "Открытие", "leave" to "Выход")))
            "FolderVisibility" -> folder(key, f)
            "NOMEDIA_SAF" -> f.label("stage", mapOf("PHYSICAL_FOLDER_RESOLVED" to "Определена физическая папка (исторический механизм архива)",
                "INVENTORY_START" to "Начата инвентаризация папки", "INVENTORY_READ" to "Прочитан список файлов папки",
                "VISIBILITY_ACTION_FAILED" to "Изменение видимости не выполнено", "MANAGED_LOCAL_ACCESS" to "Проверен локальный доступ",
                "EXACT_GRANT_REUSED" to "Использовано существующее разрешение на папку"))
            "Trash" -> exact("Move requested" to "Запрошен перенос файла в корзину.", "Move completed" to "Перенос в корзину завершён.",
                "Duplicate transfer confirmed" to "Перенос дубликата в корзину подтверждён.", "Verified move completed" to "Проверенный перенос в корзину завершён.",
                "Duplicate transfer not confirmed" to "Перенос дубликата не подтверждён.")
            "Gallery" -> if (event == "Permanently deleted") "Зарегистрировано окончательное удаление файла." else null
            "OldDownloads" -> join("Историческая запись инвентаризации старых загрузок", metric("size", "Размер", " байт"))
            "History" -> exact("Open prevented" to "Открытие записи истории не разрешено.", "Deleted missing-file history record" to "Удалена запись истории об отсутствующем файле.",
                "Rename requested" to "Запрошено переименование файла.", "Rename failed" to "Переименование не выполнено.")
            "MissingRecovery" -> when (event) {
                "Candidate verification" -> join("Проверка кандидата на восстановление", metric("candidates", "Кандидатов"), f.bool("matched", "Совпадение подтверждено", "Совпадение не подтверждено"))
                "Restore failed" -> "Восстановление не выполнено."
                else -> null
            }
            "VideoPlayer" -> when (key) {
                "PLAYER_CREATED" -> "Создан экземпляр видеоплеера."
                "PLAYER_STATE" -> join("Состояние видеоплеера", f.label("state", mapOf("1" to "Неактивен", "2" to "Буферизация", "3" to "Готов", "4" to "Воспроизведение завершено")), f.bool("playing", "Воспроизводится", "Не воспроизводится"), metric("positionMs", "Позиция", " мс"))
                "PLAYER_TIMELINE" -> join("Обновлена временная шкала видео", metric("confirmedMs", "Подтверждённая длительность", " мс"))
                "PLAYER_LIFECYCLE" -> "Изменилось состояние жизненного цикла видеоплеера."
                "PLAYER_RELEASE" -> join("Запрошено освобождение видеоплеера", metric("positionMs", "Позиция", " мс"))
                "PLAYER_COMPOSE_REMOVED" -> "Компонент видеоплеера удалён с экрана."
                "PLAYER_RELEASE_FINISHED" -> join("Завершена операция освобождения видеоплеера", f.bool("success", "Успешно", "Не подтверждено"), metric("durationMs", "Длительность", " мс"))
                else -> null
            }
            "Viewer" -> exact("Opened" to "Открыт просмотр медиафайла.", "Closed" to "Просмотр медиафайла закрыт.")
            "FloatingVideo" -> exact("Floating window closed" to "Плавающее окно видео закрыто.")
            "Audio" -> exact("Playback failed" to "Не удалось воспроизвести аудиозапись.")
            "AudioRoute" -> if (event == "Preview route") join("Подготовлен способ предварительного воспроизведения", metric("embedded", "Встроенных"), metric("separate", "Отдельных")) else null
            "AudioReplace" -> if (event.startsWith("MediaStore metadata not confirmed;")) "Метаданные Android не подтверждены; проверенные данные распознавания и история сохранены." else null
            "Editor" -> exact("Audio edit failed" to "Обработка аудио в редакторе не выполнена.")
            "PhotoCutout" -> when {
                event.startsWith("ONNX CPU cutout started,") -> join("Начато выделение объекта на фото", metric("width", "Ширина"), metric("height", "Высота"))
                event.startsWith("ONNX CPU cutout completed,") -> join("Выделение объекта на фото завершено", ms())
                else -> null
            }
            "PhotoOCR" -> if (event.startsWith("stage=") || event.startsWith("glyphs_full=") || event.startsWith("symbol_candidates=") || event.startsWith("selected_glyph_count=") || event.startsWith("result")) "Диагностический этап распознавания текста на изображении." else null
            "AiOrtPerf" -> if (event.startsWith("model=")) join("Измерение работы AI-модели", metric("session_init_ms", "Подготовка сеанса", " мс"), metric("elapsedMs", "Длительность", " мс")) else null
            "VideoEditorNav" -> when (key) {
                "EDITOR_OPEN_REQUEST" -> "Запрошено открытие видеоредактора."
                "EDITOR_NAVIGATE" -> "Выполняется переход в видеоредактор."
                "EDITOR_ENTRY_AUTHORIZED" -> "Вход в видеоредактор разрешён."
                else -> null
            }
            "VideoPreview" -> if (key == "EDITOR_FIRST_FRAME") "Отображён первый кадр в видеоредакторе." else null
            "VideoRecognition" -> exact("Saved video recognition started" to "Начато распознавание музыки из сохранённого видео.", "Audio decoded" to "Декодирован звук видео.", "Finished" to "Операция распознавания из видео завершена.")
            "GapiUsage" -> if (event == "method=gapi") join("Запрос к сервису извлечения медиа", metric("status", "Код HTTP"), metric("cost", "Стоимость в единицах лимита"), metric("remaining", "Остаток лимита")) else null
            "Browser" -> if (event == "youtube") "Диагностика поиска карточек видео на странице YouTube." else null
            "BrowserWebView" -> exact("Page started" to "Начата загрузка страницы браузера.", "Page finished" to "Загрузка страницы браузера завершена; содержимое проверяется отдельно.")
            "HitMozWebView", "MuzmoWebView" -> if (event == "JS console") "Диагностическое сообщение сценария встроенного браузера." else null
            "Instagram" -> exact("Media resolved" to "Получены сведения о материалах Instagram.", "GAPI unavailable" to "Сервис извлечения Instagram недоступен.", "Using existing extractor for this request" to "Для запроса выбран имеющийся механизм извлечения.")
            "Resolve" -> exact("Begin" to "Начата обработка ссылки.", "Provider error" to "Источник вернул ошибку при обработке ссылки.")
            "Share" -> exact("Share route" to "Определён способ обработки полученной ссылки.", "Instagram visual auto-download scheduled" to "Запланирована автоматическая загрузка материалов Instagram.", "Instagram auto-download queued" to "Автоматическая загрузка Instagram поставлена в очередь.", "Instagram automatic share done" to "Завершена обработка автоматически полученной ссылки Instagram.")
            "Duplicate" -> exact("Existing downloads found" to "Обнаружены ранее загруженные материалы.")
            "App" -> when {
                key == "ACTIVITY_CREATE" -> join("Создан экран приложения", f.bool("restored", "Восстановлено состояние", "Новый экземпляр"))
                event == "Received shared text intent" -> "Приложение получило текст через системное меню «Поделиться»."
                else -> null
            }
            "Logs" -> exact("Detailed performance diagnostics enabled" to "Включена подробная диагностика производительности.", "Detailed performance diagnostics disabled" to "Подробная диагностика производительности выключена.")
            "Thumbnail" -> when (event) {
                "Local preview unavailable" -> join("Локальная миниатюра недоступна", f.bool("remoteFallback", "Допускается удалённый источник", "Удалённый источник не используется"))
                "Local thumbnail decode failed" -> "Не удалось декодировать локальную миниатюру."
                else -> null
            }
            "Download" -> when (event) {
                "Retry queued" -> "Повторная попытка загрузки добавлена в очередь."
                "Completed" -> join("Загрузка завершена, файл сохранён", metric("bytes", "Размер", " байт"))
                else -> null
            }
            else -> null
        }
    }
    private const val nullText = "Подготовка не выполняется"
    private fun actionLabel(action: String?): String? = when (action?.substringBefore(':')) {
        "screen_enter" -> "Вход на экран"
        "screen_data_ready" -> "Данные экрана готовы"
        "screen_first_compose_frame" -> "Первый кадр экрана"
        "activity_create" -> "Создание экрана"
        "activity_first_pre_draw" -> "Подготовка первого кадра"
        "mode_switch" -> "Переключение раздела"
        "mode_switch_dispatch" -> "Передача команды переключения раздела"
        else -> null
    }
    private fun folder(key: String?, f: Fields): String? {
        val direction = f.label("direction", mapOf("HIDE" to "Скрытие", "SHOW" to "Возврат видимости"))
        return when (key) {
            "ATTEMPT_BEGIN" -> "Начата попытка операции с видимостью файлов."
            "MOVE_PORT", "MOVE_PHASE", "MOVE_BIND", "BINDING_STEP", "REFERENCE_READ" ->
                join("Этап исторической операции с файлами", direction, f.bool("success", "Этап успешен", "Этап не подтверждён"), f.metric("elapsedMs", "Длительность", " мс"))
            "MOVE_FILE" -> join("Обработка отдельного файла в исторической операции архива", direction, f.bool("confirmed", "Подтверждено", "Не подтверждено"), f.metric("bytes", "Размер", " байт"))
            "PREFLIGHT_V2" -> join("Предварительная проверка файлов", f.countProgress("ready", "total", "Готово"), f.metric("problems", "Проблем"))
            "PLAN_SIZE_MISMATCH" -> "Обнаружено расхождение размеров плана операции; успешное выполнение не подтверждено."
            "INVENTORY_REJECTED" -> "Инвентаризация отклонена; файл не принят в операцию."
            "FOREGROUND_REQUEST" -> join("Запрошена фоновая служба файловой операции", f.bool("accepted", "Запрос принят", "Запрос не принят"))
            "FOREGROUND_STOP" -> "Фоновая служба файловой операции остановлена."
            "PAUSE_REQUESTED" -> "Запрошена безопасная пауза файловой операции."
            "PAUSE_CHECKPOINT" -> join("Контрольная точка паузы", f.countProgress("confirmed", "total", "Подтверждено"), f.bool("journalDurable", "Журнал сохранён", "Сохранение журнала не подтверждено"))
            "RECOVERY_PLAN" -> join("План восстановления файловой операции", direction, f.bool("verified", "Проверен", "Не проверен"))
            "ROOT_PREFLIGHT" -> "Предварительная проверка корневой папки."
            "MOVE_BATCH_LATENCY" -> join("Измерена длительность пакета файлов", f.metric("samples", "Измерений"), f.metric("p50Ms", "Медиана", " мс"), f.metric("p95Ms", "95-й процентиль", " мс"))
            else -> null
        }
    }
}
