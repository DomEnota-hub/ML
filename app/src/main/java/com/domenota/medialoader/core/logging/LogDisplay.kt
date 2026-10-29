package com.domenota.medialoader.core.logging

/**
 * Presentation-only interpretation of stored log lines.
 * Does not modify the original log, export format, or the event's raw severity.
 */
enum class LogTone(val label: String) {
    ERROR("Ошибки"),
    WARN("Предупреждения"),
    OK("Успешные"),
    INFO("Информация"),
    DEBUG("Отладка"),
}

data class DisplayLogEntry(
    val original: String,
    val timestamp: String,
    val technicalLevel: String,
    val tag: String,
    val event: String,
    val metadata: String,
    val tone: LogTone,
    val stack: List<String> = emptyList(),
    val repeats: Int = 1,
)

object LogDisplay {
    private val eventPattern = Regex(
        """^(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d(?:\.\d+)?) \[([A-Z]+)] \[([^]]+)] (.*)$""",
    )
    private val multipleSpaces = Regex("""\s+""")

    fun entries(raw: String): List<DisplayLogEntry> {
        val entries = mutableListOf<DisplayLogEntry>()
        for (line in raw.lineSequence()) {
            if (line.isBlank()) continue
            val matched = eventPattern.matchEntire(line)
            if (matched == null) {
                // Stack traces and multiline diagnostics belong to their original event.
                if (entries.isNotEmpty()) {
                    val last = entries.lastIndex
                    entries[last] = entries[last].copy(stack = entries[last].stack + line)
                }
                continue
            }
            val (timestamp, technicalLevel, tag, message) = matched.destructured
            val event = message.substringBefore(" · ").trim()
            val metadata = message.substringAfter(" · ", "").trim()
            entries.add(DisplayLogEntry(
                original = line,
                timestamp = timestamp,
                technicalLevel = technicalLevel,
                tag = tag,
                event = event,
                metadata = metadata,
                tone = classify(technicalLevel, tag, event, line),
            ))
        }
        return entries
    }

    fun classify(level: String, tag: String, event: String, raw: String = ""): LogTone {
        // The source event's status wins unless a narrowly defined successful
        // completion had historically been written as INFO.
        if (LogExplanation.isCriticalSearchTimeout(raw)) return LogTone.ERROR
        return when (level) {
            "ERROR", "FATAL" -> LogTone.ERROR
            "WARN" -> LogTone.WARN
            "OK" -> LogTone.OK
            "INFO" -> when {
                (tag == "HitMoz" || tag == "Muzmo") &&
                    event.startsWith("MP3 stream verified", ignoreCase = true) -> LogTone.OK
                else -> LogTone.INFO
            }
            else -> LogTone.DEBUG
        }
    }

    fun filter(entries: List<DisplayLogEntry>, tone: LogTone?): List<DisplayLogEntry> =
        if (tone == null) entries else entries.filter { it.tone == tone }

    /**
     * Collapse only consecutive same-kind thumbnail events in the readable view.
     * Raw/technical view and Markdown export preserve every original record.
     * Errors are never merged across separate download IDs or search operations.
     */
    fun compact(entries: List<DisplayLogEntry>): List<DisplayLogEntry> {
        val result = mutableListOf<DisplayLogEntry>()
        for (entry in entries) {
            val last = result.lastOrNull()
            if (last != null && isRepeatableThumbnail(entry) && isRepeatableThumbnail(last) &&
                last.tone == entry.tone && repeatKey(last) == repeatKey(entry)) {
                result[result.lastIndex] = last.copy(repeats = last.repeats + entry.repeats)
            } else result.add(entry)
        }
        return result
    }

    private fun isRepeatableThumbnail(entry: DisplayLogEntry): Boolean =
        entry.tag == "Thumbnail" &&
            (entry.event.startsWith("Fetch") && "status=403" in entry.original ||
                entry.event.startsWith("Image host=") && "Thumbnail HTTP 403" in entry.original)

    private fun repeatKey(entry: DisplayLogEntry): String =
        (if (entry.event.startsWith("Fetch")) "thumbnail-fetch-403" else "thumbnail-warning-403") +
            ":" + entry.technicalLevel

    /** Never include private log fields, URLs or filenames in the readable event title. */
    fun readable(entry: DisplayLogEntry): String =
        LogExplanation.forRecord(entry.original, entry.stack)?.meaning
            ?: when (entry.tone) {
                LogTone.ERROR -> "Зафиксирована ошибка. Подробности доступны в техническом журнале."
                LogTone.WARN -> "Зафиксировано предупреждение. Подробности доступны в техническом журнале."
                LogTone.OK -> "Операция завершилась успешно."
                LogTone.INFO -> "Событие приложения. Подробности доступны в техническом журнале."
                LogTone.DEBUG -> "Техническое диагностическое событие."
            }

    /** A short safe grouping label; never contains filenames or remote hosts. */
    fun groupName(entry: DisplayLogEntry): String = when (entry.tag) {
        "Muzmo", "HitMoz", "MusicSource", "MusicWebView", "MusicAds" -> "Поиск музыки"
        "Recognition", "MusicArtwork" -> "Распознавание музыки"
        "Download", "DownloadProgress" -> "Загрузки"
        "Thumbnail" -> "Миниатюры"
        "App" -> "Приложение"
        "Crash" -> "Сбой приложения"
        "Settings" -> "Настройки"
        "Logs" -> "Журнал"
        else -> entry.tag.replace(multipleSpaces, " ").take(32)
    }
}
