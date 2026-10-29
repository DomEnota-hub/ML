package com.domenota.medialoader.core.logging

/**
 * Offline explanations for stable diagnostic event signatures.
 * Deliberately conservative: a status code alone does not establish root cause.
 * Never copy raw log contents, URLs, media names or tokens into an explanation.
 */
object LogExplanation {
    data class Detail(val meaning: String, val advice: String? = null)

    /** Crash headers are generic; the exception attached to the same record supplies the cause. */
    fun forRecord(line: String, stack: List<String>): Detail? {
        if ("[ERROR] [Crash]" in line) {
            return if (stack.any { "Session ID must be unique" in it } &&
                stack.any { "MediaSession" in it }) {
                Detail("Приложение аварийно завершилось из-за повторного идентификатора медиасессии видеоплеера.",
                    "Установите актуальную версию приложения. Если сбой повторится, сохраните новый журнал.")
            } else Detail("Приложение аварийно завершилось. Тип исключения и место сбоя сохранены в техническом журнале.",
                "Если это повторяется, приложите технический журнал к сообщению об ошибке.")
        }
        return forLine(line)
    }

    /** Preserve the original log and add explanations using the following stack lines. */
    fun annotate(raw: String): String {
        val lines = raw.lines()
        return buildString {
            lines.forEachIndexed { index, line ->
                if (index > 0) append('\n')
                append(line)
                if (!line.contains(" [") || line.startsWith("    ")) return@forEachIndexed
                val stack = mutableListOf<String>()
                var next = index + 1
                while (next < lines.size && lines[next].startsWith("    ")) {
                    stack += lines[next]
                    next++
                }
                forRecord(line, stack)?.let { detail ->
                    append("\n    ↳ Пояснение: ").append(detail.meaning)
                    detail.advice?.let { append("\n    ↳ Совет: ").append(it) }
                }
            }
        }
    }

    /**
     * Search watchdog expiry is a terminal search failure, even though its
     * historical technical severity is WARN. Other warnings stay amber.
     */
    fun isCriticalSearchTimeout(line: String): Boolean =
        "[HitMoz]" in line && "[WARN]" in line && "Search watchdog expired" in line

    fun forLine(line: String): Detail? {
        if (!line.contains(" [") || line.startsWith("    ")) return null
        return when {
            "[InstagramProfile]" in line && "Task stopped" in line &&
                "reason=RATE_LIMITED" in line ->
                Detail("Проверка ленты профиля Instagram остановилась: сервис ограничил запросы. Одиночные публикации могут оставаться доступными.",
                    "Повторите позже; частые повторы могут продлить ограничение.")
            "[InstagramProfile]" in line && "API feed HTTP 400" in line &&
                "\"spam\":true" in line ->
                Detail("Instagram отклонил запрос ленты профиля как подозрительный. Это не подтверждает ошибку входа или одиночной загрузки.")
            "[BrowserWebView]" in line && "Network error" in line &&
                "mainFrame=false" in line ->
                Detail("Встроенный браузер не загрузил вспомогательный ресурс страницы. Основная страница могла продолжить работу.",
                    "Если не отображается изображение, обновите страницу или проверьте соединение.")
            "[BrowserWebView]" in line && "Network error" in line &&
                "mainFrame=true" in line ->
                Detail("Встроенный браузер не смог загрузить основную страницу.",
                    "Проверьте соединение и повторите открытие страницы.")
            "[Diagnostics]" in line && "Session probe" in line &&
                "reason=SESSION_HTTP_400" in line ->
                Detail("Проверочный запрос не подтвердил вход: сервис вернул HTTP 400. Работу сессии в браузере и загрузку по ссылке нужно оценивать отдельно.")
            "[Crash]" in line && "[ERROR]" in line ->
                Detail("Приложение аварийно завершилось. Тип исключения и место сбоя сохранены в техническом журнале.",
                    "Если это повторяется, приложите технический журнал к сообщению об ошибке.")
            "[Muzmo]" in line &&
                ("Search document HTTP failure" in line || "Info page HTTP failure" in line) &&
                "status=403" in line ->
                Detail("Музыкальный поиск не смог открыть страницу: сервер отклонил запрос (HTTP 403). Причина не установлена — возможны ограничения IP/VPN, сессии или ресурса.",
                    "Попробуйте другой сервер VPN или повторите поиск позже. Это не доказывает поломку приложения.")
            "[Muzmo]" in line &&
                ("Search document HTTP failure" in line || "Info page HTTP failure" in line) &&
                "status=429" in line ->
                Detail("Музыкальный поиск не смог открыть страницу: сервер ограничил частоту запросов (HTTP 429).",
                    "Подождите перед повторным поиском.")
            "[Muzmo]" in line &&
                ("Search document HTTP failure" in line || "Info page HTTP failure" in line) ->
                Detail("Страница музыкального поиска или композиции отклонена сервером. Текущая операция не завершилась.",
                    "Проверьте доступность сервиса и при необходимости повторите позже.")
            "[Muzmo]" in line &&
                ("Search document network failure" in line || "Info page network failure" in line) ->
                Detail("Встроенный браузер не смог загрузить страницу для музыкального поиска или выбранной композиции. Текущая операция прервалась из-за соединения.",
                    "Проверьте подключение или VPN и повторите попытку. Точная причина сетевой ошибки не установлена.")
            "[Muzmo]" in line && "Main-frame HTTP rejected outside active operation" in line ->
                Detail("Сервер отклонил загрузку страницы, но активная операция поиска в этот момент не была подтверждена. Это не доказанная ошибка скачивания.")
            "[MuzmoWebView]" in line && "HTTP error" in line &&
                "mainFrame=false" in line ->
                Detail("Встроенный браузер не смог получить вспомогательный ресурс. Сам музыкальный поиск из-за этого необязательно завершился ошибкой.")
            "[App]" in line && "Session started" in line ->
                Detail("Приложение запущено. Начался новый сеанс работы.")
            "[Settings]" in line && "Developer section enabled" in line ->
                Detail("Раздел инструментов разработчика включён.")
            "[Settings]" in line && "Developer section disabled" in line ->
                Detail("Раздел инструментов разработчика скрыт.")
            "[Logs]" in line && "Extended diagnostics enabled" in line ->
                Detail("Включена подробная запись технических событий.")
            "[Logs]" in line && "Extended diagnostics disabled" in line ->
                Detail("Подробная запись технических событий выключена. Уже записанные строки сохраняются.")
            "[MusicSource]" in line && "Saved manual source=" in line ->
                Detail("Пользователь изменил источник поиска полной композиции.")
            "[MusicSource]" in line && "History full-song launch" in line ->
                Detail("Из истории загрузок открыт поиск полной версии композиции.")
            "WebView created" in line ->
                Detail("Подготовлено скрытое окно браузера для работы музыкального поиска.")
            "WebView destroyed" in line ->
                Detail("Скрытое окно музыкального поиска закрыто, его ресурсы освобождены.")
            "Page started" in line || "Search document started" in line ->
                Detail("Началась загрузка страницы поиска или выбранной композиции.")
            "Page finished" in line || "WebView page finished" in line ->
                Detail("Браузер закончил загрузку страницы. Наличие результатов проверяется отдельно.")
            "WebView progress" in line ->
                Detail("Браузер сообщает о ходе загрузки страницы. Проценты не относятся к скачиванию MP3.")
            "Initial browser search requested" in line || "Full-song search requested" in line ||
                "Native music search submitted" in line ->
                Detail("Запущен поиск по введённым исполнителю и названию.")
            "DOM search rows parsed" in line || "Native music results parsed" in line ->
                Detail("Приложение извлекло список доступных композиций из страницы поиска.")
            "Search returned explicit empty state" in line ->
                Detail("Страница явно сообщила, что результатов по этому запросу нет.")
            "DOM probe returned unreadable JSON" in line ||
                "Settled search DOM has no usable rows" in line ->
                Detail("Страница открылась, но данные списка композиций не удалось надёжно прочитать. Это не доказывает отсутствие песни.",
                    "Повторите запрос позднее или выберите другой источник поиска.")
            "Search DOM awaiting rows" in line || "Music extraction awaiting rows" in line ->
                Detail("Страница ещё не предоставила пригодный список композиций. Приложение ожидает данные.")
            "Search duration structure" in line || "Late durations parsed" in line ->
                Detail("Приложение дополнительно проверяет продолжительность найденных композиций.")
            "Track selected" in line || "Native download pressed" in line ->
                Detail("Пользователь выбрал найденную композицию для загрузки.")
            "Info page awaits trusted MP3 link" in line ->
                Detail("Ожидается подтверждённая ссылка на аудиофайл.")
            "Settled info DOM has no trusted MP3 link" in line ->
                Detail("На странице композиции не обнаружена надёжная ссылка для скачивания MP3.",
                    "Попробуйте другую композицию или повторите запрос позже.")
            "Selected MP3 queued" in line || "Browser-selected MP3 queued" in line ->
                Detail("Выбранная композиция добавлена в очередь загрузки.")
            "MP3 redirect" in line ->
                Detail("Запрос аудиофайла перенаправлен на другой сервер. Успех загрузки ещё не подтверждён.")
            "MP3 response" in line && "status=302" in line ->
                Detail("Сервер предложил перенаправление аудиозапроса.")
            "MP3 response" in line && "status=200" in line ->
                Detail("Сервер принял запрос и начал отдавать аудиоданные. Проверка файла ещё впереди.")
            "Engine transfer begin" in line ->
                Detail("Модуль передачи приступил к получению файла.")
            "Engine transfer finished" in line ->
                Detail("Сетевой модуль закончил передачу. Итог сохранения проверьте по соседнему событию.")
            "[DownloadProgress]" in line ->
                Detail("Идёт получение данных файла. Число принятых байтов и общий размер указаны в технической записи.")
            "MP3 stream verified" in line ->
                Detail("Получен поток MP3, пройдена проверка аудиоданных. Завершение сохранения подтверждается отдельно.")
            "[Recognition]" in line && "Audio window" in line ->
                Detail("Выбран очередной фрагмент аудио для распознавания по звуковому отпечатку.")
            "Alternative match retained for user review" in line ->
                Detail("Повторная проверка нашла другое совпадение. Оно сохранено для выбора, без автоматической замены прежнего названия.")
            "[Thumbnail]" in line && "status=200" in line ->
                Detail("Сервер изображения успешно ответил. Отображение миниатюры проверяется отдельно.")
            "[MusicArtwork]" in line && "Cover URLs in results" in line ->
                Detail("Проверено наличие ссылок на обложки у найденных композиций.")
            "[MusicAds]" in line && "network filter enabled" in line ->
                Detail("Для музыкального браузера включена фильтрация сторонних запросов.")
            "[MusicWebView]" in line && "Resource request" in line ->
                Detail("Встроенный браузер запросил ресурс страницы; это не обязательно аудиофайл.")
            "MP3 HTTP attempt" in line ->
                Detail("Сетевая подсистема выполняет попытку получить MP3.")
            "MP3 transport" in line && "client=cronet" in line ->
                Detail("Для получения MP3 выбран сетевой механизм Cronet.")
            "Thumbnail URL host not supported" in line ->
                Detail("Источник изображения отклонён правилами приложения, даже если адрес технически доступен.",
                    "Проверьте поддержку данного источника изображений.")
            "Thumbnail HTTP 403" in line || ("[Thumbnail]" in line && "status=403" in line) ->
                Detail("Сервер изображения отказал в доступе. Причина может быть связана с устаревшей ссылкой или условиями доступа.",
                    "Если файл уже сохранён, попробуйте использовать локальную миниатюру.")
            "HTTP 405" in line || "code=405" in line ->
                Detail("Сервер не принимает выбранный метод HTTP-запроса. Это не обязательно означает, что весь поиск недоступен.")
            "HTTP 429" in line || "code=429" in line ->
                Detail("Сервис ограничил частоту запросов. Длительность ограничения неизвестна.",
                    "Не повторяйте запросы слишком часто; попробуйте позже.")
            "HTTP 500" in line || "code=500" in line ->
                Detail("Сервер сообщил о внутренней ошибке. Причина на стороне сервиса неизвестна.")
            "reason=DNS_LOOKUP_FAILED" in line || "ERR_NAME_NOT_RESOLVED" in line ||
                "UnknownHostException" in line ->
                Detail("Не удалось определить сетевой адрес сервера по DNS. Это не доказывает, что сервер неисправен или заблокирован.",
                    "Проверьте подключение, DNS и используемый VPN; повторите запрос позже.")
            "reason=NETWORK_TIMEOUT" in line ->
                Detail("Подключение или получение ответа заняло больше разрешённого времени.",
                    "Попробуйте позже или проверьте другое доступное подключение.")
            "reason=CONNECTION_RESET" in line || "Connection reset" in line ->
                Detail("Сетевое соединение прервалось сбросом. Какая сторона его инициировала, неизвестно.",
                    "Повторите запрос позже; при частых сбросах проверьте качество соединения.")
            "ERR_NETWORK_CHANGED" in line ->
                Detail("Во время соединения изменилась сеть или её маршрут. Текущая попытка могла прерваться.",
                    "Проверьте стабильность сети и VPN, затем повторите попытку.")
            "SocketTimeoutException" in line || "ERR_TIMED_OUT" in line ->
                Detail("За отведённое время не получен ответ от сетевого соединения.",
                    "Попробуйте повторить позже или сравнить подключение с VPN и без него.")
            "MP3 redirect HTTP 302" in line ->
                Detail("Сервис перенаправил запрос аудио на другой сервер. Передача MP3 ещё не подтверждена.")
            "MP3 transport" in line && "platform-retry" in line ->
                Detail("Повторная попытка MP3 использует системный сетевой механизм Android вместо Cronet.")
            "MP3 transport" in line && "cronet" in line ->
                Detail("Для получения MP3 используется сетевой механизм Cronet.")
            "MP3 request" in line ->
                Detail("Приложение отправляет запрос на получение аудио. Это ещё не означает, что файл начал скачиваться.")
            "MP3 response" in line && "status=200" in line ->
                Detail("Сервер ответил успешно на запрос аудио. Файл ещё должен пройти загрузку и проверку.")
            "MP3 stream verified" in line ->
                Detail("Аудиоданные получены и прошли проверку формата перед сохранением.")
            "MP3 network attempt" in line && "failed" in line ->
                Detail("Одна из сетевых попыток получения MP3 завершилась ошибкой. Следующая возможна, если остались повторы.")
            "Native music results parsed" in line ->
                Detail("Приложение извлекло найденные музыкальные композиции из страницы поиска.")
            "Native music extraction failed" in line || "Native music extraction empty" in line ->
                Detail("Приложение не смогло получить пригодные строки с песнями из текущей страницы. Это не доказывает отсутствие песен.")
            "Search watchdog expired" in line ->
                Detail("Поиск не завершился за установленное время. Текущая попытка остановлена.",
                    "Проверьте доступность HitMoz в выбранной сети и повторите поиск.")
            "WebView timed out" in line ->
                Detail("Загрузка страницы длится слишком долго. Приложение ещё проверяет, есть ли доступные результаты.")
            "No usable search results" in line ->
                Detail("Ни основной, ни резервный способ не вернули пригодных результатов для текущего запроса.")
            "Ignored stale" in line || "Download ignored" in line ->
                Detail("Приложение отклонило неактуальное действие, чтобы не скачать другую композицию.")
            "Caption unavailable" in line ->
                Detail("Не удалось достоверно извлечь подпись Instagram. Используется резервное имя.",
                    "Имя можно исправить вручную в окне подтверждения.")
            "Publication caption recovered" in line ->
                Detail("Подпись публикации восстановлена из доступных метаданных и подготовлена для имени файла.")
            "source=SHAZAM_DIRECT" in line ->
                Detail("Композиция определена по звуковому отпечатку, а не по названию исходного файла.")
            "source=SOURCE_METADATA" in line ->
                Detail("Исполнитель и название взяты из метаданных источника, без акустического распознавания.")
            "Alternative match retained" in line ->
                Detail("Другое распознавание дало отличающийся результат. Прежние сведения защищены от автоматической замены.")
            "[Download]" in line && "Completed" in line ->
                Detail("Загрузка завершена и файл сохранён.")
            "[Download]" in line && "Failed" in line ->
                Detail("Загрузка завершилась ошибкой. Подробная причина может быть указана в соседних строках журнала.")
            "[Download]" in line && "Started" in line ->
                Detail("Задача перешла к выполнению, но получение данных могло ещё не начаться.")
            "[Download]" in line && "Queued" in line ->
                Detail("Задача добавлена в очередь ожидания загрузки.")
            "[Download]" in line && "Retry queued" in line ->
                Detail("Пользователь или приложение поставили повторную попытку в очередь.")
            "[Resolve]" in line && "Completed" in line ->
                Detail("Ссылка обработана, а список доступных материалов сформирован.")
            else -> when {
                "[ERROR]" in line -> Detail("Произошла ошибка. Для этого события пока нет точного пояснения; проверьте соседние строки.")
                "[WARN]" in line -> Detail("Записано предупреждение. Для этого события пока нет точного пояснения.")
                else -> null
            }
        }
    }
}
