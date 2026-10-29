package com.domenota.medialoader.core.diagnostics

/** Never interpolate free-form exception strings, URLs, names or cookies into cards. */
data class FaultDescription(
    val title: String,
    val explanation: String,
    val advice: String?,
)

object FaultExplanations {
    fun forRecord(record: FaultRecord): FaultDescription = when (record.reasonCode) {
        "HTTP_ACCESS_DENIED" -> FaultDescription(
            "Источник ограничил доступ",
            "Сервер ответил HTTP 403. По одному ответу нельзя определить, связано ли " +
                "это с VPN, IP-адресом, сессией или защитой сайта.",
            "Проверьте соединение и при необходимости смените VPN-сервер. " +
                "Это не доказывает неисправность приложения.")
        "HTTP_RATE_LIMIT" -> FaultDescription(
            "Источник ограничил частоту запросов",
            "Сервер ответил HTTP 429 и мог временно ограничить обращения.",
            "Подождите перед следующей попыткой.")
        "HTTP_AUTH_REQUIRED" -> FaultDescription(
            "Сервер запрашивает авторизацию",
            "Анонимный запрос получил HTTP 401. Действительность сохранённой сессии неизвестна.",
            "При необходимости проверьте вход через настройки аккаунта.")
        "HTTP_REMOTE_FAILURE" -> FaultDescription(
            "Источник сообщил о серверной ошибке",
            "Удалённый сервер ответил кодом 5xx. Причина в приложении не подтверждена.",
            "Повторите проверку позднее.")
        "HTTP_REQUEST_REJECTED" -> FaultDescription(
            "Сервер отклонил запрос",
            "Анонимный запрос не принят. Работа анализатора и загрузки не проверялась.",
            "Повторите позже либо проверьте доступность сайта.")
        "RUNTIME_LIBRARY_MISSING", "ONNX_LIBRARY_MISSING", "QUICKJS_NOT_FOUND" ->
            FaultDescription(
                "Компонент приложения отсутствует",
                "Проверка установленной сборки не нашла ожидаемый компонент.",
                "Проверьте целостность установки официального APK.")
        "SERVICE_NOT_DECLARED" -> FaultDescription(
            "Служба загрузок не зарегистрирована",
            "Проверка манифеста не нашла foreground-службу загрузок.",
            "Проверьте установленную сборку приложения.")
        "FOLDER_PERMISSION_MISSING" -> FaultDescription(
            "Нет разрешения на папку",
            "Сохранённые разрешения для выбранного каталога неполны.",
            "Повторно выберите папку загрузок в настройках.")
        "FOLDER_NOT_AVAILABLE", "FOLDER_URI_INVALID" -> FaultDescription(
            "Папка загрузок недоступна",
            "Выбранный каталог больше не доступен по сохранённому адресу.",
            "Повторно выберите папку загрузок.")
        "FOLDER_ACCESS_DENIED" -> FaultDescription(
            "Доступ к выбранной папке ограничен",
            "Системный поставщик документов не подтвердил чтение или запись.",
            "Проверьте разрешения и выбор папки.")
        "FREE_SPACE_LOW" -> FaultDescription(
            "Мало свободного места",
            "Система сообщила менее 100 МБ свободного пространства.",
            "Проверьте свободное место перед крупной загрузкой.")
        "LAMA_DIGEST_MISMATCH" -> FaultDescription(
            "Файл модели LaMa повреждён или изменён",
            "Контрольная сумма установленной модели не совпала с закреплённой.",
            "Повторная установка модели возможна только отдельным действием пользователя.")
        "LAMA_INVALID_SIZE" -> FaultDescription(
            "Файл LaMa слишком мал",
            "Размер модели не соответствует минимальным требованиям.",
            "Проверьте установку модели в настройках редактора.")
        "LAMA_NOT_INSTALLED" -> FaultDescription(
            "Необязательная модель LaMa отсутствует",
            "Удаление объектов через LaMa может быть недоступно. " +
                "Остальные функции приложения от этого не обязательно страдают.",
            "При необходимости установите модель в фоторедакторе.")
        else -> FaultDescription(
            "Обнаружено отклонение в проверке",
            "Диагностическая проверка сообщила об отклонении. " +
                "Подтверждённая причина требует отдельного разбора.",
            "Откройте связанные технические события, если они доступны.")
    }

    /** UI title for subsystem: no internal identifiers or technical provider domains. */
    fun componentTitle(component: DiagnosticComponent): String = when (component) {
        DiagnosticComponent.SOURCES -> "Источники"
        DiagnosticComponent.MEDIA_RUNTIME -> "yt-dlp и обработка медиа"
        DiagnosticComponent.DOWNLOAD_QUEUE -> "Очередь загрузок"
        DiagnosticComponent.STORAGE -> "Хранилище"
        DiagnosticComponent.PHOTO_EDITOR -> "LaMa и редактор"
        DiagnosticComponent.FAULT_JOURNAL -> "Журнал неисправностей"
        DiagnosticComponent.DEVICE -> "Устройство"
    }
}
