package com.domenota.medialoader.core.logging

/** Literal event vocabulary from current source emitters, including events not seen in the archive. */
internal object CurrentLogExplanation {
    private val groups = mapOf(
        "InstagramLogin" to "Сеанс Instagram",
        "InstagramSession" to "Сеанс Instagram",
        "VKAuth" to "Сеанс VK",
        "RutubeAuth" to "Сеанс RUTUBE",
        "YouTubeAuth" to "Сеанс YouTube",
        "VK" to "VK",
        "RUTUBE" to "RUTUBE",
        "VkCommunity" to "Сообщества VK",
        "VkFavoriteCheck" to "Сообщества VK",
        "MusicPreview" to "Предпросмотр музыки",
        "RemotePreview" to "Просмотр медиа",
        "PreviewFrame" to "Редактор видео",
        "MUSIC_REPORT_COMMIT" to "Дубликаты музыки"
    )
    fun group(tag: String): String? = groups[tag]
    private val rules: Map<String, List<Pair<String, String>>> = mapOf(
        "TaskCenter" to listOf(
            "History unavailable" to "История задач недоступна; прежние данные сохранены.",
            "History write failed" to "Не удалось записать историю задач.",
            "HISTORY_CLEAR" to "Изменение истории задач применено в памяти."
        ),
        "InstagramLogin" to listOf(
            "Browser session cookie captured" to "Получены данные браузерного сеанса Instagram; вход требует отдельного подтверждения.",
            "Login page network error" to "Не удалось получить страницу входа Instagram.",
            "Login page HTTP" to "Получен HTTP-ответ страницы входа Instagram."
        ),
        "InstagramSession" to listOf(
            "Browser session cookies captured" to "Получены данные браузерного сеанса Instagram; успешность входа проверяется отдельно."
        ),
        "VKAuth" to listOf(
            "VK QR-auth navigation upgraded to HTTPS" to "Для перехода авторизации VK используется защищённое соединение.",
            "Authenticated web session captured" to "Получены данные авторизованного веб-сеанса VK.",
            "Saved cookies offered to WebView" to "Сохранённые данные сеанса переданы браузеру; вход не подтверждён.",
            "Post-app browser session capture" to "Проверяются данные браузерного сеанса после возврата из VK.",
            "Main-frame navigation blocked" to "Переход основной страницы авторизации VK отклонён.",
            "Web session save failed:" to "Не удалось сохранить данные веб-сеанса VK.",
            "Web sign-in opened" to "Открыта страница входа VK.",
            "VK session removed" to "Локальные данные сеанса VK удалены.",
            "Web session saved" to "Данные веб-сеанса VK сохранены; доступность операций проверяется отдельно.",
            "VK app returned" to "Выполнен возврат из приложения VK.",
            "VK QR handoff" to "Запрошен переход к авторизации VK по QR-коду."
        ),
        "RutubeAuth" to listOf(
            "Web session save failed:" to "Не удалось сохранить данные веб-сеанса RUTUBE.",
            "Browser profile visible" to "В браузере обнаружены сведения профиля RUTUBE.",
            "RUTUBE session removed" to "Локальные данные сеанса RUTUBE удалены.",
            "Web sign-in opened" to "Открыта страница входа RUTUBE.",
            "Web session saved" to "Данные веб-сеанса RUTUBE сохранены."
        ),
        "YouTubeAuth" to listOf(
            "Authenticated web session captured" to "Получены данные авторизованного веб-сеанса YouTube.",
            "Web sign-in opened" to "Открыта страница входа YouTube."
        ),
        "VK" to listOf(
            "Runtime initialization failed:" to "Не удалось подготовить механизм анализа VK.",
            "Could not parse yt-dlp JSON:" to "Не удалось разобрать ответ анализатора VK.",
            "Initializing yt-dlp runtime" to "Подготавливается механизм анализа VK.",
            "yt-dlp runtime ready" to "Механизм анализа VK готов.",
            "Download failed:" to "Получение материала VK не выполнено.",
            "Download success" to "Модуль получения материала VK сообщил об успехе; сохранение проверяется отдельно.",
            "Analyze failed:" to "Анализ ссылки VK не выполнен.",
            "Analyze success" to "Получены результаты анализа VK; сохранение файла не подтверждено.",
            "Download start" to "Начато получение материала VK.",
            "Analyze start" to "Начат анализ ссылки VK."
        ),
        "RUTUBE" to listOf(
            "Runtime initialization failed:" to "Не удалось подготовить механизм анализа RUTUBE.",
            "Could not parse yt-dlp JSON:" to "Не удалось разобрать ответ анализатора RUTUBE.",
            "Initializing yt-dlp runtime" to "Подготавливается механизм анализа RUTUBE.",
            "Video format dimensions" to "Определены размеры доступного формата видео RUTUBE.",
            "Saved video dimensions" to "Определены размеры сохранённого видео RUTUBE.",
            "yt-dlp runtime ready" to "Механизм анализа RUTUBE готов.",
            "Download failed:" to "Получение видео RUTUBE не выполнено.",
            "Download success" to "Модуль получения видео RUTUBE сообщил об успехе; сохранение проверяется отдельно.",
            "Analyze failed:" to "Анализ ссылки RUTUBE не выполнен.",
            "Analyze success" to "Получены результаты анализа RUTUBE; сохранение файла не подтверждено.",
            "Download start" to "Начато получение видео RUTUBE.",
            "Analyze start" to "Начат анализ ссылки RUTUBE."
        ),
        "VkCommunity" to listOf(
            "Missing selected files returned to queue" to "Отсутствующие выбранные файлы возвращены в очередь.",
            "Download completed" to "Операция загрузки сообщества VK завершена.",
            "Scan completed" to "Сканирование сообщества VK завершено.",
            "Job cancelled" to "Операция сообществ VK отменена.",
            "VK job failed" to "Операция сообществ VK завершилась ошибкой.",
            "Scan progress" to "Обновлён ход сканирования сообщества VK.",
            "Job started" to "Начата операция сообществ VK."
        ),
        "VkFavoriteCheck" to listOf(
            "Community check failed" to "Не удалось проверить сообщество VK."
        ),
        "MusicPreview" to listOf(
            "Stream failed" to "Не удалось получить аудиопоток для предварительного прослушивания."
        ),
        "RemotePreview" to listOf(
            "Playback failed" to "Не удалось воспроизвести удалённое превью."
        ),
        "PreviewFrame" to listOf(
            "Could not save frame" to "Не удалось сохранить кадр видео."
        ),
        "MUSIC_REPORT_COMMIT" to listOf(
            "stage=" to "Не удалось сохранить отчёт поиска дубликатов; стадия и диагностика сохранены в технической записи."
        ),
        "AudioWaveform" to listOf(
            "Editor detail failed" to "Не удалось построить подробную звуковую волну редактора.",
            "Background scan deferred:" to "Фоновая проверка звуковых волн отложена; условие указано в технической записи.",
            "Could not read frames" to "Не удалось прочитать аудиофрагменты для звуковой волны."
        ),
        "Share" to listOf(
            "Automatic share worker stopped; queue remains in history" to "Автоматическая обработка остановлена; очередь сохранена в истории.",
            "Instagram publication already saved; no queue created" to "Публикация Instagram уже сохранена; новая очередь не создана.",
            "Automatic Instagram download failed" to "Автоматическая загрузка Instagram не выполнена.",
            "Could not persist shared download" to "Не удалось сохранить сведения о полученной загрузке.",
            "Cannot schedule Instagram share" to "Не удалось запланировать обработку ссылки Instagram.",
            "Instagram analysis deferred" to "Анализ публикации Instagram отложен.",
            "Automatic route" to "Определён автоматический маршрут обработки ссылки."
        ),
        "Download" to listOf(
            "Automatic YouTube enqueue failed; manual selection retained" to "Автоматическая постановка YouTube в очередь не выполнена; ручной выбор сохранён.",
            "Foreground restore notification unavailable" to "Уведомление восстановления фоновой службы недоступно.",
            "Queued tasks cancelled for source change" to "Ожидающие задачи отменены после смены источника.",
            "Could not recheck network wait" to "Не удалось повторно проверить условие ожидания сети.",
            "Could not queue selected media" to "Не удалось добавить выбранные материалы в очередь.",
            "Parallel RUTUBE task failed" to "Параллельная загрузка RUTUBE завершилась ошибкой.",
            "Recovered interrupted tasks" to "Прерванные задачи загрузки восстановлены.",
            "Parallel photo task failed" to "Параллельная загрузка фото завершилась ошибкой.",
            "Parallel music task failed" to "Параллельная загрузка музыки завершилась ошибкой.",
            "Completion hook failed" to "Дополнительная обработка после загрузки не выполнена; итог сохранения нужно оценивать отдельно.",
            "Queue worker error" to "Зафиксирована ошибка исполнителя очереди загрузок.",
            "Cancel running" to "Запрошена отмена выполняющейся загрузки.",
            "Cancel queued" to "Запрошена отмена загрузки в очереди.",
            "Cancelled" to "Загрузка отменена."
        ),
        "MissingRecovery" to listOf(
            "Recovery output cleanup remains pending" to "Очистка результата восстановления ещё ожидает выполнения.",
            "Retry transfer" to "Запрошена повторная попытка переноса при восстановлении."
        ),
        "Recognition" to listOf(
            "Acoustic lookup unavailable; keeping source metadata" to "Акустический поиск недоступен; метаданные источника сохранены.",
            "Optional cover normalization exceeded memory budget" to "Подготовка обложки превысила допустимый объём памяти.",
            "Publication music metadata unavailable" to "Метаданные музыки публикации недоступны.",
            "MP3 existing-cover/tag pass rejected" to "Запись тегов с прежней обложкой отклонена.",
            "Optional cover normalization failed" to "Дополнительная подготовка обложки не выполнена.",
            "Alternate audio window unavailable" to "Альтернативный фрагмент аудио для распознавания недоступен.",
            "Preview music recognition failed" to "Распознавание предварительно прослушиваемой музыки не выполнено.",
            "Live music recognition failed:" to "Распознавание музыки в реальном времени не выполнено.",
            "MP3 cover/tag pass rejected" to "Операция записи обложки и тегов отклонена.",
            "MP3 safe fallback rejected" to "Резервная операция записи тегов отклонена.",
            "Full song result ignored" to "Результат поиска полной композиции отклонён.",
            "Preview music cancelled" to "Предварительное распознавание музыки отменено.",
            "Post-processing failed" to "Дополнительная обработка распознанной музыки не выполнена.",
            "Direct ID3 unavailable" to "Прямая запись ID3 недоступна.",
            "MP3 tag write rejected" to "Запись тегов MP3 отклонена.",
            "Preview music failed" to "Предварительное распознавание музыки не выполнено.",
            "No reliable match" to "Надёжное совпадение композиции не найдено.",
            "MP3 remux failed" to "Не удалось выполнить преобразование контейнера MP3."
        ),
        "Library" to listOf(
            "Collection projection cache write deferred" to "Сохранение кеша представления коллекций отложено.",
            "Collection metadata persistence deferred" to "Сохранение метаданных коллекций отложено.",
            "Playlist alias persistence deferred" to "Сохранение связей плейлистов отложено.",
            "Phone metadata cache save deferred" to "Сохранение кеша метаданных телефона отложено.",
            "Video alias persistence deferred" to "Сохранение связей видео отложено.",
            "Collection addresses" to "Обновлены диагностические сведения об адресах коллекций.",
            "RECOVERY_UNCHANGED" to "Каталог после восстановления признан неизменившимся.",
            "Phone scan failed" to "Сканирование файлов телефона завершилось ошибкой."
        ),
        "Diagnostics" to listOf(
            "Instagram account probe" to "Проверяются сведения о сеансе Instagram.",
            "Reserve music probe" to "Выполняется резервная проверка музыкального источника.",
            "QuickJS probe" to "Выполняется проверка механизма JavaScript."
        ),
        "MusicSource" to listOf(
            "Could not persist manual music-source selection" to "Не удалось сохранить ручной выбор источника музыки.",
            "Cleared waiting music tasks" to "Ожидающие задачи поиска музыки очищены.",
            "Saved source mode=" to "Сохранён режим выбора источника музыки."
        ),
        "History" to listOf(
            "History-only cleanup completed" to "Очистка записей истории завершена.",
            "History-only cleanup started" to "Начата очистка записей истории без удаления оригиналов.",
            "Rename completed" to "Переименование завершено.",
            "Delete failed" to "Удаление не выполнено."
        ),
        "InstagramProfile" to listOf(
            "Web feed response format changed" to "Формат ответа ленты профиля изменился.",
            "Fallback feed request failed" to "Резервный запрос ленты профиля не выполнен.",
            "Web feed request failed" to "Запрос ленты профиля через браузер не выполнен.",
            "Selected files queued" to "Выбранные файлы профиля поставлены в очередь.",
            "Post not queued" to "Публикация не поставлена в очередь загрузки.",
            "API feed HTTP" to "Получен HTTP-ответ ленты профиля Instagram.",
            "Web feed HTTP" to "Получен HTTP-ответ ленты профиля через браузер.",
            "Task stopped" to "Операция профиля Instagram остановлена; точная причина указана в технической записи.",
            "Scan paused" to "Сканирование профиля Instagram приостановлено.",
            "Page saved" to "Сохранён результат обработки страницы профиля Instagram."
        ),
        "Editor" to listOf(
            "Video frame export failed" to "Экспорт кадра видео не выполнен.",
            "Audio extraction failed" to "Извлечение звука не выполнено.",
            "Video export failed" to "Экспорт видео не выполнен.",
            "Photo edit failed" to "Обработка фотографии не выполнена."
        ),
        "Instagram" to listOf(
            "Publication media found without caption; trying caption fallback" to "Материалы публикации найдены без подписи; выбран резервный способ её извлечения.",
            "Caption source" to "Определён источник подписи публикации."
        ),
        "VideoRecognition" to listOf(
            "Failed" to "Распознавание музыки из видео не выполнено."
        ),
        "MusicArtwork" to listOf(
            "Muzmo cover saved" to "Обложка музыкального источника сохранена."
        ),
        "AudioReplace" to listOf(
            "Playback refresh deferred:" to "Обновление данных воспроизведения после замены аудио отложено."
        ),
        "Trash" to listOf(
            "Completed entry has no private file; entry preserved" to "Закрытая копия не найдена; запись сохранена.",
            "Restored file, but old private copy cleanup failed" to "Файл восстановлен, но удалить прежнюю закрытую копию не удалось.",
            "Physical delete failed; entry preserved" to "Физическое удаление не удалось; запись сохранена.",
            "Deletion failed; metadata retained" to "Удаление не выполнено; метаданные сохранены.",
            "Permanent deletion started" to "Начато окончательное удаление файла.",
            "Permanent deletion ended" to "Операция окончательного удаления завершена; результат проверяется по её записям.",
            "Permanently removed" to "Зарегистрировано окончательное удаление.",
            "Restore requested" to "Запрошено восстановление из корзины.",
            "Restored" to "Файл восстановлен из корзины."
        ),
        "YouTube" to listOf(
            "Player asks for page reload; trying alternate client without tv_downgraded" to "Плеер запросил повторную загрузку страницы; выбран альтернативный клиент.",
            "Download player requested reload; trying alternate web_safari client" to "Плеер запросил повторное открытие страницы; выбран альтернативный клиент.",
            "Trying anonymous public-video extraction without cookies" to "Выполняется получение сведений о публичном видео без сохранённого сеанса.",
            "Browser Web proof download failed; trying saved session" to "Получение видео через браузер не выполнено; выбран сохранённый сеанс.",
            "Trying anonymous public-video download without cookies" to "Выполняется получение публичного видео без сохранённого сеанса.",
            "Compact format response invalid; retrying full JSON" to "Сокращённый ответ анализа непригоден; запрошен полный ответ.",
            "Alternate player did not provide selected format" to "Альтернативный плеер не предоставил выбранный формат.",
            "HTTP 403 recovery: updating yt-dlp automatically" to "После HTTP 403 запрошено автоматическое обновление анализатора.",
            "Browser Web proof expired; trying saved session" to "Подтверждение браузера устарело; выбран сохранённый сеанс.",
            "Retrying download after automatic yt-dlp update" to "Выполняется повтор получения видео после обновления анализатора.",
            "Saved-session client did not provide formats" to "Клиент с сохранённым сеансом не предоставил форматы видео.",
            "Analyze retrying with web_embedded after 403" to "После HTTP 403 выполняется повтор анализа альтернативным клиентом.",
            "Other client did not provide selected format" to "Другой клиент не предоставил выбранный формат.",
            "Automatic yt-dlp update did not succeed" to "Автоматическое обновление анализатора не выполнено.",
            "Alternate web player was also rejected" to "Альтернативный веб-плеер также отклонён.",
            "Public client did not provide formats" to "Публичный клиент не предоставил форматы видео.",
            "Saved-session audio fallback failed" to "Резервное получение аудио через сохранённый сеанс не выполнено.",
            "Anonymous extraction also failed" to "Получение сведений без сохранённого сеанса не выполнено.",
            "Reload recovery download failed:" to "Повтор загрузки после обновления страницы не выполнен.",
            "Runtime initialization failed:" to "Не удалось подготовить механизм анализа YouTube.",
            "Anonymous download also failed" to "Получение видео без сохранённого сеанса не выполнено.",
            "Download after update failed:" to "Получение видео после обновления анализатора не выполнено.",
            "Could not parse yt-dlp JSON:" to "Не удалось разобрать ответ анализатора YouTube.",
            "Analyze attempt timed out" to "Превышено время ожидания попытки анализа.",
            "Web session save failed:" to "Не удалось сохранить веб-сеанс YouTube.",
            "Local token probe failed" to "Локальная проверка данных запроса YouTube не выполнена.",
            "Analyze fallback failed:" to "Резервный анализ YouTube не выполнен.",
            "Cookies import failed:" to "Не удалось импортировать данные сеанса YouTube.",
            "Analyze got HTTP 403" to "Сервер отклонил запрос анализа YouTube (HTTP 403).",
            "Analyze first result" to "Получен первый результат анализа.",
            "Analyze cache hit" to "Использован кеш анализа ссылки YouTube.",
            "Cookies imported" to "Импортированы данные сеанса YouTube; вход проверяется отдельно.",
            "Download failed:" to "Получение видео YouTube не выполнено.",
            "Cookies removed" to "Локальные данные сеанса YouTube удалены.",
            "Analyze failed:" to "Анализ ссылки YouTube не выполнен."
        ),
        "Logs" to listOf(
            "Log export failed:" to "Не удалось экспортировать журнал приложения."
        ),
        "Settings" to listOf(
            "ADVANCED_DOWNLOAD_TOOLS" to "Изменена настройка расширенных инструментов загрузки."
        ),
        "FloatingVideo" to listOf(
            "Could not validate floating playback" to "Не удалось подтвердить возможность плавающего воспроизведения.",
            "Could not attach floating window" to "Не удалось подключить плавающее окно.",
            "Could not start floating window" to "Не удалось открыть плавающее окно.",
            "HANDOFF_APPLY_FAILED" to "Не удалось применить передачу воспроизведения.",
            "HANDOFF_CANCELLED" to "Передача воспроизведения в плавающее окно отменена.",
            "HANDOFF_REQUEST" to "Запрошена передача воспроизведения в плавающее окно.",
            "HANDOFF_ACK" to "Получено подтверждение передачи воспроизведения."
        ),
        "BrowserWebView" to listOf(
            "VK app returned" to "Выполнен возврат из приложения VK.",
            "VK QR handoff" to "Запрошен переход к авторизации VK по QR-коду.",
            "Network error" to "Встроенный браузер зарегистрировал сетевую ошибку.",
            "Navigation" to "Выполняется переход встроенного браузера.",
            "HTTP error" to "Получен HTTP-ответ об ошибке ресурса браузера."
        ),
        "FolderVisibility" to listOf(
            "ANDROID_APPROVAL_ACTIVITY" to "Зафиксирована ошибка системного подтверждения файловой операции."
        ),
        "VideoEditorNav" to listOf(
            "EDITOR_ENTRY_UNAVAILABLE" to "Вход в видеоредактор недоступен.",
            "EDITOR_OPEN_IGNORED" to "Запрос открытия видеоредактора отклонён."
        ),
        "Viewer" to listOf(
            "Selected visual address check failed" to "Не удалось подтвердить доступность выбранного медиафайла."
        ),
        "VideoPlayer" to listOf(
            "Temporary speed restore failed" to "Не удалось восстановить скорость воспроизведения.",
            "Temporary speed start failed" to "Не удалось включить временное изменение скорости.",
            "PLAYER_ERROR" to "Видеоплеер зарегистрировал ошибку воспроизведения."
        ),
        "HitMoz" to listOf(
            "Cronet initialization unavailable; using platform transport" to "Не удалось подготовить Cronet; выбран системный сетевой механизм.",
            "Duplicate page start retained existing music results" to "Повторное начало загрузки страницы не заменило текущие результаты.",
            "Ignored unexpected WebView download destination" to "Неожиданный адрес загрузки из браузера отклонён.",
            "Ignored page start after search input changed" to "Начало загрузки устаревшей страницы отклонено после изменения запроса.",
            "Download blocked before connection" to "Загрузка не началась: запрос остановлен до подключения.",
            "Fallback search recovered tracks" to "Резервный поиск вернул композиции.",
            "Noncanonical search page start" to "Открыта страница, не совпадающая с ожидаемой страницей поиска.",
            "Could not queue selected MP3" to "Не удалось поставить выбранную композицию в очередь.",
            "HTTP search fallback failed" to "Резервный сетевой поиск не выполнен.",
            "Browser callback ignored" to "Событие браузера отклонено.",
            "Search document verified" to "Проверена страница музыкального поиска.",
            "WebView DownloadListener" to "Встроенный браузер сообщил о запросе загрузки.",
            "No WebView MP3 callback" to "Не получено ожидаемое событие браузера для загрузки MP3.",
            "MP3 attempt diagnostic" to "Записан результат диагностики попытки MP3.",
            "Hidden WebView error:" to "Встроенный браузер музыкального поиска зарегистрировал ошибку.",
            "MP3 network failure" to "Зафиксирован сетевой сбой запроса MP3.",
            "Hidden WebView HTTP" to "Получен HTTP-ответ встроенного музыкального браузера.",
            "MP3 start attempt" to "Начата попытка получения MP3.",
            "MP3 error page" to "Вместо аудиоданных получена страница ошибки."
        ),
        "Muzmo" to listOf(
            "Info page DOM probe returned unreadable JSON" to "Не удалось разобрать данные страницы композиции.",
            "Download blocked before connection" to "Загрузка остановлена до сетевого подключения.",
            "Main-frame network error" to "Не удалось получить основную страницу музыкального браузера.",
            "MP3 transport failed" to "Сетевой механизм получения MP3 не выполнил запрос.",
            "MP3 enqueue failed" to "Не удалось добавить композицию в очередь загрузок.",
            "Navigation" to "Выполняется переход на музыкальную страницу."
        ),
        "DuplicateScanMetrics" to listOf(
            "gallery" to "Зафиксирован ход или результат поиска дубликатов фото и видео."
        )
    )
    fun readable(entry: DisplayLogEntry): String? = rules[entry.tag]?.firstOrNull { (prefix, _) ->
        entry.event == prefix || entry.event.startsWith(prefix + " ") ||
            entry.event.startsWith(prefix + ":") ||
            ((prefix.endsWith(':') || prefix.endsWith('=') || prefix.endsWith(';')) && entry.event.startsWith(prefix))
    }?.second
}
