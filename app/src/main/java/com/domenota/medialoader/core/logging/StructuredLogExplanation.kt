package com.domenota.medialoader.core.logging

/**
 * Presentation-only rules. Event names and enum labels are allowlisted.
 * Never render arbitrary field values, URLs, file names, paths or IDs.
 * Unknown values do not imply success, failure, or a root cause.
 */
internal object StructuredLogExplanation {
    private val inlineField = Regex("""(?:^|[ ,])([A-Za-z][A-Za-z0-9_]*)=([A-Za-z0-9_-]+)(?=$|[ ,])""")
    private val firstWord = Regex("""^[A-Za-z_][A-Za-z0-9_]*""")
    private val fieldKey = Regex("[A-Za-z][A-Za-z0-9_]*")
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
                if (entry.tag == "Perf" && entry.event == "LIBRARY_WORK") {
                    for (match in inlineField.findAll(segment)) add(match.groupValues[1], match.groupValues[2])
                } else if (key.matches(fieldKey)) add(key, segment.substringAfter('='))
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
        "PUBLISHED" to "Результат опубликован", "PARTIAL" to "Частично завершено", "PAUSED" to "На паузе", "WAITING_CHARGE" to "Ожидание зарядки", "DISCARDED" to "Результат отброшен", "ERROR" to "Ошибка")
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
        "Share" to "Получение ссылки", "Diagnostics" to "Диагностика",
        "GalleryWallpaper" to "Галерея", "VideoEditor" to "Редактор видео", "PhotoEditor" to "Редактор фото",
        "Listening" to "Музыка", "AudioPreview" to "Редактор видео", "AudioSync" to "Редактор видео")
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
                "UI_STALL" -> join("Зафиксирована задержка интерфейса", metric("blockedMs", "Задержка", " мс"), screenLabel(f["screen"]), metric("heapUsedMiB", "Память приложения", " МиБ"))
                "UI_RECOVERED" -> join("Отзывчивость интерфейса восстановлена", metric("blockedMs", "Длительность задержки", " мс"), screenLabel(f["screen"]))
                "FRAME_GAPS" -> join("Измерены интервалы между кадрами интерфейса", metric("windowMs", "Период наблюдения", " мс"), metric("over50ms", "Интервалов более 50 мс"), metric("worstMs", "Максимум", " мс"))
                "DETAIL" -> join("Снимок показателей памяти и работы приложения", metric("heapUsedMiB", "Использовано", " МиБ"), metric("heapMaxMiB", "Лимит", " МиБ"), metric("gcCount", "Сборок мусора"), metric("gcBlockingTimeMs", "Блокировки сборки мусора", " мс"))
                "READ_MODEL_GUARD" -> join("Проверка условий публикации данных", f.label("kind", guards), f.label("reason", guardReasons), f.bool("retained", "Прежние данные сохранены", "Прежние данные не сохранены"), metric("repeatedSuppressed", "Свернутых повторов"))
                "MUSIC_READ_MODEL" -> join("Состояние данных музыкального раздела", f.label("block", blocks), f.bool("ready", "Готово", "Не готово"), f.bool("loading", "Идёт подготовка", nullText), f.bool("error", "Отмечена ошибка", "Ошибок не отмечено"), metric("count", "Записей"))
                "MUSIC_REFRESH" -> join("Обновление данных музыки", f.label("block", blocks), phase(), metric("count", "Записей"), ms())
                "BEGIN", "END" -> join(if (key == "BEGIN") "Начато измерение операции" else "Завершено измерение операции", actionLabel(f["action"]), screenLabel(f["screen"]), ms())
                "LIBRARY_WORK" -> join("Счётчики работы медиатеки", metric("MEDIASTORE_SCAN", "Сканирований"), metric("MEDIASTORE_QUERY", "Запросов"), metric("THUMBNAIL_DECODE", "Миниатюр"), metric("LIBRARY_INDEX_BUILD", "Построений индекса"))
                "LIBRARY_PROJECTIONS" -> projections(entry.metadata)
                "RECOVERY_GATE" -> join("Проверка условий восстановления каталога", metric("activeScans", "Активных сканирований"))
                "RECOVERY_REFRESH_REQUEST" -> join("Запрошено обновление каталога после восстановления", metric("pendingMediaChanges", "Ожидающих изменений"))
                "RECOVERY_SCAN_DECISION" -> join("Определены условия сканирования при восстановлении", mode(), f.bool("force", "Принудительное обновление", "Обычное обновление"))
                else -> null
            }
            "ProcessExit" -> if (event == "Previous process exit") join("Android зарегистрировал завершение предыдущего процесса",
                f.label("type", mapOf("LOW_MEMORY" to "Нехватка памяти", "ANR" to "Приложение не отвечало",
                    "CRASH" to "Аварийное завершение", "JAVA_CRASH" to "Аварийное завершение", "CRASH_NATIVE" to "Сбой нативного кода", "NATIVE_CRASH" to "Сбой нативного кода",
                    "USER_REQUESTED" to "Запрошено пользователем")) ?: "Причина не расшифрована",
                metric("pssKiB", "Память PSS", " КиБ")) else if (event.startsWith("Exit history read unavailable:")) "Android не предоставил историю завершений процесса." else null
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
                key == "WAVEFORM_DURATION_READ_FAILED" -> "Ошибка чтения длительности аудио; звуковая волна не создана. Причина не установлена."
                key == "WAVEFORM_DURATION_MISSING" -> join("Длительность аудио недоступна; трек пропущен при подготовке звуковых волн, это не ошибка чтения", f.bool("versionProven", "Версия источника подтверждена", "Версия источника не подтверждена"))
                else -> null
            }
            "MusicCompare" -> when {
                event.startsWith("frames decoded ") -> join("Декодированы фрагменты аудио для сравнения", metric("count", "Фрагментов"), elapsed())
                event.startsWith("frames cache ") -> join("Получены фрагменты аудио из кеша", metric("count", "Фрагментов"), elapsed())
                event.startsWith("alignment ") -> join("Сопоставление аудиофрагментов", f.bool("aligned", "Выравнивание подтверждено", "Выравнивание не подтверждено"), elapsed())
                event.startsWith("pair ready ") -> join("Пара аудиозаписей подготовлена для сравнения", elapsed())
                event.startsWith("verified pair cache HIT") -> join("Использован кеш проверенной пары аудиозаписей", metric("zones", "Зон"))
                event.startsWith("shortRefine") -> join("Уточнение коротких различий аудиозаписей", metric("candidates", "Кандидатов"), metric("inspected", "Проверено"), metric("spectral", "Спектральных"), metric("pcm", "По аудиоданным"))
                event.startsWith("fullPcm unavailable") -> "Полное декодирование аудио недоступно; используется предварительная спектральная проверка."
                event.startsWith("verified pair cache MISS") -> "Проверенная пара не найдена в кеше сравнения."
                key == "fullPcm" -> join("Сравнение декодированных аудиоданных", metric("firstSamples", "Отсчётов первого"), metric("secondSamples", "Отсчётов второго"))
                else -> null
            }
            "DuplicateScanMetrics" -> when (key) {
                "music" -> join("Этап поиска дубликатов музыки", metric("processed", "Обработано"), metric("skipped", "Пропущено"), elapsed())
                "gallery" -> join("Поиск дубликатов фото и видео", metric("processed", "Обработано"), metric("coverage", "Охват"), metric("cached", "В кеше"), metric("computed", "Вычислено"), metric("failed", "Не обработано"), elapsed())
                else -> null
            }
            "MusicDuplicates" -> if (key == "SCAN_HEAP") join("Оценка памяти для поиска дубликатов", metric("heapMiB", "Память", " МиБ")) else null
            "DuplicatePrivacy" -> if (key == "PRIVATE_MOVE") "Обновление отчётов дубликатов при изменении приватности." else null
            "DuplicateUI" -> join("Состояние интерфейса поиска дубликатов", f.label("event", mapOf("enter" to "Открытие", "leave" to "Выход")))
            "FolderVisibility" -> folder(key, f)
            "NOMEDIA_SAF" -> f.label("stage", mapOf("PHYSICAL_FOLDER_RESOLVED" to "Определена физическая папка (исторический механизм архива)",
                "INVENTORY_START" to "Начата инвентаризация папки", "INVENTORY_READ" to "Прочитан список файлов папки",
                "VISIBILITY_ACTION_FAILED" to "Изменение видимости не выполнено", "MANAGED_LOCAL_ACCESS" to "Проверен локальный доступ",
                "EXACT_GRANT_REUSED" to "Использовано существующее разрешение на папку"))
                ?: if (event.startsWith("stage=")) "Этап исторического механизма доступа к папке; точный код сохранён в технической записи." else null
            "Trash" -> exact("Move requested" to "Запрошен перенос файла в корзину.", "Move completed" to "Перенос в корзину завершён.",
                "Duplicate transfer confirmed" to "Перенос дубликата в корзину подтверждён.", "Verified move completed" to "Проверенный перенос в корзину завершён.",
                "Duplicate transfer not confirmed" to "Перенос дубликата не подтверждён.")
            "Gallery" -> if (event == "Permanently deleted") "Зарегистрировано окончательное удаление файла." else null
            "OldDownloads" -> join("Историческая запись инвентаризации старых загрузок", metric("size", "Размер", " байт"))
            "History" -> when (event) {
                "Open aborted", "Share aborted" -> join(if (event == "Open aborted") "Открытие файла остановлено" else "Отправка файла остановлена",
                    f.label("status", mapOf("MISSING" to "Файл отсутствует", "INACCESSIBLE" to "Нет доступа к файлу", "UNCHECKED" to "Наличие файла не подтверждено", "UNAVAILABLE" to "Доступ к файлу не подтверждён")))
                "Open failed" -> "Не удалось открыть сохранённый файл."
                "Share failed" -> "Не удалось отправить сохранённый файл."
                else -> exact("Open prevented" to "Открытие записи истории не разрешено.", "Deleted missing-file history record" to "Удалена запись истории об отсутствующем файле.",
                "Rename requested" to "Запрошено переименование файла.", "Rename failed" to "Переименование не выполнено.")
            }
            "MissingRecovery" -> when (event) {
                "Candidate verification" -> join("Проверка кандидата на восстановление", metric("candidates", "Кандидатов"), f.bool("matched", "Совпадение подтверждено", "Совпадение не подтверждено"))
                "Restore failed" -> "Восстановление не выполнено."
                else -> null
            }
            "VideoPlayer" -> when (key) {
                "PLAYER_CREATED" -> "Создан экземпляр видеоплеера."
                "PLAYER_STATE" -> join("Состояние видеоплеера", f.label("state", mapOf("1" to "Неактивен", "2" to "Буферизация", "3" to "Готов", "4" to "Воспроизведение завершено")), f.bool("playing", "Воспроизводится", "Не воспроизводится"), metric("positionMs", "Позиция", " мс"))
                "PLAYER_TIMELINE" -> join("Обновлена временная шкала видео", metric("confirmedMs", "Подтверждённая длительность", " мс"))
                "PLAYER_LIFECYCLE" -> join("Изменилось состояние жизненного цикла видеоплеера", f.label("event", mapOf("ON_START" to "Запуск", "ON_RESUME" to "Возврат", "ON_PAUSE" to "Приостановка", "ON_STOP" to "Остановка", "ON_DESTROY" to "Уничтожение компонента")))
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
                event == "Module missing; requesting subject segmentation module" -> "Модуль выделения объекта отсутствует; запрошена установка."
                event == "Subject segmentation module ready" -> "Модуль выделения объекта готов."
                event.startsWith("Module preparation failed:") -> "Не удалось подготовить модуль выделения объекта."
                event == "Cancellation requested; native task remains alive until completion" -> "Запрошена отмена выделения объекта; нативная операция ещё выполняется."
                event.startsWith("ONNX CPU cutout started,") -> join("Начато выделение объекта на фото", metric("width", "Ширина"), metric("height", "Высота"))
                event.startsWith("ONNX CPU cutout completed,") -> join("Выделение объекта на фото завершено", ms())
                else -> null
            }
            "PhotoOCR" -> when {
                event.startsWith("stage=") -> join("Этап распознавания текста изображения",
                    f.label("stage", mapOf("auto" to "Автоматический", "sparse" to "Разреженный текст", "block" to "Блок текста", "contrast-sparse" to "Контрастный разреженный текст"))
                        ?: when { f["stage"]?.startsWith("caption-") == true -> "Область подписи"; f["stage"]?.startsWith("band-") == true -> "Полоса изображения"; else -> null })
                event.startsWith("glyphs_full=") -> join("Сопоставлены варианты распознавания символов", metric("glyphs_full", "Полное изображение"), metric("glyphs_caption", "Область подписи"), metric("accepted", "Принято"))
                event.startsWith("symbol_candidates=") -> join("Получены кандидаты символов", metric("symbol_candidates", "Кандидатов"))
                event.startsWith("selected_glyph_count=") -> join("Выбраны распознанные символы", metric("selected_glyph_count", "Символов"))
                event.startsWith("result") -> "Получен результат распознавания изображения."
                else -> null
            }
            "AiOrtPerf" -> if (event.startsWith("model=")) join("Измерение работы AI-модели", f.label("model", mapOf("LAMA" to "Восстановление фона LaMa", "SAM_ENCODER" to "Кодировщик SAM", "SAM_DECODER" to "Декодировщик SAM", "DEXNET" to "DEXNET")), metric("session_init_ms", "Подготовка сеанса", " мс"), metric("inference_ms", "Вычисление", " мс"), metric("ort_run_ms", "Вычисление ONNX", " мс"), metric("total_ms", "Всего", " мс")) else null
            "VideoEditorNav" -> when (key) {
                "EDITOR_OPEN_REQUEST" -> "Запрошено открытие видеоредактора."
                "EDITOR_NAVIGATE" -> "Выполняется переход в видеоредактор."
                "EDITOR_ENTRY_AUTHORIZED" -> "Вход в видеоредактор разрешён."
                else -> null
            }
            "VideoPreview" -> when {
                key == "EDITOR_FIRST_FRAME" -> "Отображён первый кадр в видеоредакторе."
                event == "Player failure" -> join("Ошибка предварительного просмотра видео", metric("positionMs", "Позиция", " мс"), metric("durationMs", "Длительность", " мс"))
                else -> null
            }
            "VideoEditor" -> if (event == "Operation failed") "Операция видеоредактора не выполнена; причина в технической записи." else null
            "PhotoEditor" -> if (event.startsWith("Preview render failed:")) "Не удалось отобразить предварительный результат фоторедактора." else null
            "AudioPreview" -> if (event == "Player failure") "Ошибка предварительного воспроизведения звука видеоредактора." else null
            "AudioSync" -> when (event) {
                "Drift correction" -> join("Коррекция рассинхронизации звука и видео", metric("projectMs", "Позиция проекта", " мс"), metric("expectedSourceMs", "Ожидаемая позиция аудио", " мс"), metric("actualSourceMs", "Фактическая позиция аудио", " мс"))
                "Audio seek" -> join("Изменена позиция аудио для синхронизации", f.bool("jump", "Переход по шкале", nullText), f.bool("playing", "Воспроизводится", "Не воспроизводится"))
                else -> null
            }
            "GalleryWallpaper" -> when (event) {
                "Wallpaper prepared" -> join("Изображение подготовлено для установки обоев", metric("bytes", "Размер", " байт"))
                "Wallpaper preparation failed" -> "Не удалось подготовить изображение для обоев."
                "System crop intent unavailable, using ACTION_ATTACH_DATA" -> "Системная обрезка обоев недоступна; выбран альтернативный способ открытия изображения."
                "Wallpaper crop activity unavailable; opening image chooser" -> "Системный экран обрезки недоступен; открыт выбор приложения для изображения."
                "Applied directly" -> join("Обои установлены напрямую", f.label("which", mapOf("1" to "Главный экран", "2" to "Экран блокировки", "3" to "Оба экрана")))
                "Direct wallpaper apply failed" -> "Не удалось установить обои напрямую."
                else -> null
            }
            "VideoRecognition" -> exact("Saved video recognition started" to "Начато распознавание музыки из сохранённого видео.", "Audio decoded" to "Декодирован звук видео.", "Finished" to "Операция распознавания из видео завершена.")
            "GapiUsage" -> if (event == "method=gapi") join("Запрос к сервису извлечения медиа", metric("status", "Код HTTP"), metric("cost", "Стоимость в единицах лимита"), metric("remaining", "Остаток лимита")) else null
            "Browser" -> {
                val source = mapOf("instagram" to "Instagram", "youtube" to "YouTube", "vk" to "VK", "rutube" to "RUTUBE")[event]
                if (source == null) null else if (entry.metadata.startsWith("injection=")) {
                    join("Подключение сценария встроенного браузера", source,
                        when (entry.metadata.substringAfter("injection=")) {
                            "true" -> "Сценарий подтвердил установку"
                            "false" -> "Установка не подтверждена"
                            else -> "Результат не расшифрован"
                        })
                } else join("Диагностическое сообщение сценария браузера", source)
            }
            "BrowserWebView" -> if (event.startsWith("source=")) "Диагностическое сообщение сценария браузера." else exact("Page started" to "Начата загрузка страницы браузера.", "Page finished" to "Загрузка страницы браузера завершена; содержимое проверяется отдельно.")
            "HitMozWebView", "MuzmoWebView" -> when (event) {
                "JS console" -> "Диагностическое сообщение сценария встроенного браузера."
                "HTTP error" -> join("Сервер отклонил запрос встроенного браузера музыкального поиска", metric("status", "Код HTTP"), f.bool("mainFrame", "Основная страница", "Вспомогательный ресурс; отказ всей операции не подтверждён"))
                else -> null
            }
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
                "Fetch" -> when (f.number("status")) {
                    200L -> "Сервер изображения ответил успешно; показ миниатюры проверяется отдельно."
                    403L -> "Сервер изображения отказал в доступе (HTTP 403)."
                    else -> join("Получен ответ на запрос миниатюры", metric("status", "Код HTTP"))
                }
                else -> if (event.startsWith("Image host=")) when {
                    entry.metadata == "Thumbnail URL host not supported" -> "Источник изображения не поддерживается правилами приложения."
                    entry.metadata.startsWith("Thumbnail HTTP 403") -> "Сервер изображения отказал в доступе (HTTP 403)."
                    else -> "Зафиксирована проблема получения изображения. Точная причина сохранена в технической записи."
                } else null
            }
            "Download" -> when (event) {
                "Retry queued" -> "Повторная попытка загрузки добавлена в очередь."
                "Completed" -> join("Загрузка завершена, файл сохранён", metric("bytes", "Размер", " байт"))
                "Queued" -> "Загрузка поставлена в очередь."
                "Started" -> "Задача загрузки начата; получение файла ещё не подтверждено."
                "Failed" -> "Загрузка завершилась ошибкой. Причина может быть указана в соседних событиях."
                "Engine transfer begin" -> "Начата передача файла сетевым модулем."
                "Engine transfer finished" -> "Сетевой модуль закончил передачу; сохранение файла подтверждается отдельно."
                else -> null
            }
            "YouTube" -> youtube(event, f)
            "MusicArtwork" -> when {
                event == "Cover load" -> join("Запрос обложки композиции", metric("ms", "Длительность", " мс"))
                event.startsWith("Cover URLs in results:") -> "Проверено наличие адресов обложек в результатах."
                event == "Catalog lookup unavailable" -> "Поиск обложки в каталоге недоступен."
                event == "Catalog cover applied" -> "Применена обложка из каталога."
                else -> null
            }
            "MusicSource" -> when {
                event == "Full-song request accepted" -> "Принят запрос поиска полной композиции."
                event.startsWith("Full-song window launched;") -> "Открыто окно поиска полной композиции."
                event == "Automatic search fallback" -> "Выбран резервный способ поиска музыки."
                else -> null
            }
            "MusicWebView" -> when (event) {
                "Blocked advertising resource" -> "Отфильтрован рекламный ресурс страницы."
                "Resource request" -> "Запрошен ресурс музыкальной страницы; это не обязательно аудиофайл."
                "HTTP error" -> join("Сервер отклонил запрос встроенного браузера", metric("code", "Код HTTP"), f.bool("mainFrame", "Основная страница", "Вспомогательный ресурс"))
                else -> null
            }
            "Recognition" -> when {
                event == "Applied" -> "Результат распознавания музыки применён к записи."
                event == "MP3 metadata saved" -> "Метаданные MP3 сохранены."
                event == "Preview music result" -> "Получен предварительный результат распознавания музыки."
                event.startsWith("Direct ID") && event.contains("unavailable; trying MP3 remux") -> "Прямое распознавание недоступно; выбран резервный способ подготовки аудио."
                event.startsWith("MP3 cover/tag pass failed;") -> "Обработка обложки и тегов не удалась; выполняется повтор только для аудиотегов."
                else -> null
            }
            "HitMoz", "Muzmo" -> music(event, f)
            else -> null
        }
    }
    private const val nullText = "Подготовка не выполняется"
    private fun youtube(event: String, f: Fields): String? = when {
        event == "Analyze start" -> "Начат анализ ссылки YouTube."
        event == "Analyze attempt" -> "Выполняется попытка получения сведений о видео."
        event == "Analyze attempt completed" -> "Попытка анализа завершена; наличие форматов подтверждается отдельно."
        event == "Analyze process" -> "Запущен процесс анализа ссылки."
        event == "Analyze diagnostics" -> "Записаны диагностические показатели анализа видео."
        event == "Analyze format sources" -> "Проверены источники доступных форматов видео."
        event == "Analyze result" || event == "Analyze success" -> join("Получен результат анализа ссылки YouTube", f.metric("count", "Форматов"))
        event == "Analyze bounded enhancement" -> "Выполняется дополнительная ограниченная проверка форматов."
        event == "Browser Web proof did not provide formats" -> "Браузер не предоставил подтверждённых форматов видео."
        event == "Initializing yt-dlp runtime" -> "Подготавливается механизм анализа YouTube."
        event == "yt-dlp runtime ready" -> "Механизм анализа YouTube подготовлен."
        event == "yt-dlp update requested" -> "Запрошено обновление механизма анализа YouTube."
        event.startsWith("yt-dlp update failed:") -> "Обновление механизма анализа YouTube не выполнено."
        event.startsWith("Обновлён:") -> "Механизм анализа YouTube обновлён."
        event.startsWith("Automatic yt-dlp refresh skipped:") -> if (event.contains("cooldown active")) "Автоматическое обновление пропущено: действует интервал между попытками." else "Автоматическое обновление пропущено; причина указана в технической записи."
        event == "Web session saved" -> "Данные браузерного сеанса сохранены; вход в аккаунт этим не подтверждён."
        event == "Local token bridge" -> "Используется локальный механизм подготовки данных запроса YouTube."
        event == "Download start" -> "Начата операция получения видео YouTube."
        event == "Download client" -> "Выбран способ получения видео YouTube."
        event == "Download success" -> "Модуль получения видео сообщил об успехе; сохранение файла подтверждается отдельно."
        event.startsWith("Download got HTTP ") -> "Сервер вернул HTTP-ответ при получении видео."
        event.startsWith("Retrying download with web_embedded") -> "Выполняется повтор получения видео через альтернативный клиент."
        event.startsWith("Download fallback failed:") -> "Резервный способ получения видео не сработал."
        else -> null
    }
    private fun music(event: String, f: Fields): String? = when {
        event == "WebView browser agent synchronized" -> "Параметры сетевых запросов согласованы с браузером."
        event == "MP3 task" -> "Подготовлена задача получения аудиофайла."
        event == "Chromium Cronet MP3 transport initialized" -> "Подготовлен сетевой механизм для получения аудио."
        event == "Music extraction started" -> "Начато извлечение списка композиций."
        event == "Browser navigation" -> "Выполняется переход на музыкальную страницу."
        event == "Browser MP3 handoff" -> "Браузер передал запрос аудиофайла модулю загрузки."
        event == "Browser link action" -> "Обработано действие со ссылкой музыкальной страницы."
        event.startsWith("Cronet network error") -> "Сетевой запрос не выполнен; следующая попытка использует системный механизм Android."
        event.startsWith("CDN temporarily avoided") -> "Один из серверов передачи временно исключён из выбора."
        event.startsWith("MP3 request recovered on attempt ") -> "Повторная попытка сетевого запроса восстановила получение аудиоданных; сохранение ещё не подтверждено."
        event.startsWith("DOM scan found no usable songs") || event.startsWith("DOM yielded no song rows") -> "Не удалось извлечь пригодный список песен; это не доказывает их отсутствие."
        event == "Native music results parsed" || event == "DOM search rows parsed" -> join("Получен список композиций со страницы", f.metric("count", "Записей"))
        event == "MP3 response" -> join("Получен ответ на запрос аудиофайла", f.metric("status", "Код HTTP"), f.metric("length", "Размер ответа", " байт"), f.metric("elapsedMs", "Ожидание", " мс"))
        event == "MP3 transport" -> join("Выбран сетевой механизм получения аудио", f.label("client", mapOf("cronet" to "Cronet", "platform" to "Системный HTTP Android", "platform-retry" to "Повтор через системный HTTP Android")))
        event == "MP3 network attempt" || event.startsWith("MP3 network attempt ") -> "Попытка получения аудиофайла завершилась сетевой ошибкой."
        event == "MP3 stream verified" -> join("Аудиопоток прошёл проверку формата; сохранение подтверждается отдельно", f.metric("bytes", "Размер", " байт"))
        event.startsWith("MP3 HTTP attempt ") -> {
            val m = Regex("""^MP3 HTTP attempt (\d{1,3})/(\d{1,3})$""").matchEntire(event)
            if (m == null) null else "Попытка получения MP3: " + m.groupValues[1] + " из " + m.groupValues[2]
        }
        else -> null
    }
    private fun actionLabel(action: String?): String? = when (action?.substringBefore(':')) {
        "screen_enter" -> "Вход на экран"
        "screen_data_ready" -> "Данные экрана готовы"
        "screen_first_compose_frame" -> "Первый кадр экрана"
        "activity_create" -> "Создание экрана"
        "activity_first_pre_draw" -> "Подготовка первого кадра"
        "mode_switch" -> "Переключение раздела"
        "mode_switch_dispatch" -> "Передача команды переключения раздела"
        "trim_boundary_decode" -> "Декодирование границы обрезки видео"
        "editor_waveform" -> "Звуковая волна редактора"
        "source_switch" -> "Переключение источника файлов"
        "source_switch_dispatch" -> "Передача команды переключения источника"
        "timeline_thumbnail_batch" -> "Подготовка миниатюр временной шкалы"
        "video_trim_gesture" -> "Изменение границы обрезки видео"
        else -> null
    }
    private fun screenLabel(screen: String?): String? = when (screen?.substringBefore(':')) {
        "home" -> "Главный экран"
        "settings" -> "Настройки"
        "downloads" -> "Загрузки"
        "viewer" -> "Просмотр медиа"
        "initial", "startup" -> "Запуск"
        "task-center" -> "Центр задач"
        "music-duplicates" -> "Дубликаты музыки"
        "visual-duplicates" -> "Дубликаты фото и видео"
        "photo-editor", "batch-photo-editor" -> "Редактор фото"
        "video-editor", "video_editor_metadata" -> "Редактор видео"
        "audio-editor" -> "Редактор аудио"
        "private", "vault" -> "Личный раздел"
        "collections" -> "Коллекции"
        "favorites" -> "Избранное"
        "simple-archive" -> "Архив"
        "trash", "trash-viewer" -> "Корзина"
        "equalizer" -> "Эквалайзер"
        "drawer-settings" -> "Настройка меню"
        "browser" -> "Браузер"
        "bulk" -> "Массовая загрузка"
        "vk-community" -> "Сообщество VK"
        "media-passport" -> "Сведения о файле"
        "playlist-add" -> "Добавление в плейлист"
        "music-search" -> "Поиск музыки"
        "diagnostics" -> "Диагностика"
        "media-review" -> "Просмотр изменений медиатеки"
        else -> null
    }
    private val projectionPattern = Regex("""([A-Z_]+)\[initial=\d+,input=\d+,access=\d+,done=(\d+),aborted=(\d+),reuse=(\d+),totalMs=\d+,maxMs=\d+]""")
    private fun projections(metadata: String): String {
        val summaries = projectionPattern.findAll(metadata).mapNotNull { m ->
            val label = guards[m.groupValues[1]] ?: return@mapNotNull null
            val counts = m.groupValues.drop(2).map { it.toLongOrNull() ?: return@mapNotNull null }
            label + ": завершено " + counts[0] + ", прервано " + counts[1] + ", повторно использовано " + counts[2]
        }.toList()
        return (listOf("Показатели подготовки данных медиатеки") + summaries).joinToString(" · ")
    }
    private fun folder(key: String?, f: Fields): String? {
        val direction = f.label("direction", mapOf("HIDE" to "Скрытие", "SHOW" to "Возврат видимости"))
        return when (key) {
            "ATTEMPT_BEGIN" -> "Начата попытка операции с видимостью файлов."
            "MOVE_PORT", "MOVE_PHASE", "MOVE_BIND", "BINDING_STEP", "REFERENCE_READ" ->
                join("Этап операции с файлами", direction, f.bool("success", "Этап успешен", "Этап не подтверждён"), f.metric("elapsedMs", "Длительность", " мс"))
            "MOVE_FILE" -> join("Обработка отдельного файла в операции архива", direction, f.bool("confirmed", "Подтверждено", "Не подтверждено"), f.metric("bytes", "Размер", " байт"))
            "PREFLIGHT_V2" -> join("Предварительная проверка файлов", f.countProgress("ready", "total", "Готово"), f.metric("problems", "Проблем"))
            "PLAN_SIZE_MISMATCH" -> "Обнаружено расхождение размеров плана операции; успешное выполнение не подтверждено."
            "INVENTORY_MIME_FALLBACK" -> "Тип файла определён резервным способом по расширению."
            "INVENTORY_REJECTED" -> "Инвентаризация отклонена; файл не принят в операцию."
            "FOREGROUND_CREATE_FAILED" -> "Не удалось создать фоновую службу файловой операции."
            "FOREGROUND_REENTRANT_REJECT" -> "Повторный запуск фоновой файловой операции отклонён: служба уже работает."
            "FOREGROUND_TIMEOUT" -> "Android принудительно остановил фоновую файловую операцию по лимиту времени; журнал сохранён, контрольная точка не подтверждена."
            "FOREGROUND_REQUEST" -> join("Запрошена фоновая служба файловой операции", f.bool("accepted", "Запрос принят", "Запрос не принят"))
            "FOREGROUND_STOP" -> join("Фоновая служба файловой операции остановлена", f.bool("journalPending", "Журнал операции ещё не закрыт", "Незакрытый журнал не отмечен"))
            "STOP" -> join("Операция видимости остановлена", f.countProgress("confirmed", "total", "Подтверждено"))
            "ANDROID_APPROVAL" -> join("Системное подтверждение файловой операции", f.bool("requested", "Запрошено", "Не запрошено"), f.bool("approved", "Получено", "Не получено"))
            "PROGRESS_OBSERVER_REJECTED" -> "Не удалось передать состояние файловой операции наблюдателю."
            "RECOVERY_SCAN_DECISION" -> join("Определена необходимость обновления каталога после операции", f.bool("force", "Требуется принудительное обновление", "Принудительное обновление не требуется"))
            "PAUSE_REQUESTED" -> "Запрошена безопасная пауза файловой операции."
            "PAUSE_CHECKPOINT" -> join("Контрольная точка паузы", f.countProgress("confirmed", "total", "Подтверждено"), f.bool("journalDurable", "Журнал сохранён", "Сохранение журнала не подтверждено"))
            "RECOVERY_PLAN" -> join("План восстановления файловой операции", direction, f.bool("verified", "Проверен", "Не проверен"))
            "RESTORE_METADATA_PENDING" -> "Дата или размер восстановленного файла ещё не подтверждены; исходник сохранён."
            "PROOF_GUARD" -> "Не подтверждена привязка файлового хранилища; операция ограничена проверкой безопасности."
            "ROOT_GUARD" -> join("Не пройдена проверка корневого хранилища", f.bool("ownerVerified", "Принадлежность хранилища подтверждена", "Принадлежность хранилища не подтверждена"))
            "ROOT_DETAIL" -> "Записаны подробности проверки корневого хранилища."
            "ROOT_PREFLIGHT" -> "Предварительная проверка корневой папки."
            "MOVE_BATCH_LATENCY" -> join("Измерена длительность пакета файлов", f.metric("samples", "Измерений"), f.metric("p50Ms", "Медиана", " мс"), f.metric("p95Ms", "95-й процентиль", " мс"))
            else -> null
        }
    }
}
