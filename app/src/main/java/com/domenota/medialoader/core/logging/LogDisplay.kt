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
    val thread: String? = null,
)

object LogDisplay {
    private val eventPattern = Regex(
        """^(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d(?:\.\d+)?) \[([A-Z]+)] \[([^]]+)] (.*)$""",
    )

    internal fun parseHeader(line: String): DisplayLogEntry? {
        val match = eventPattern.matchEntire(line) ?: return null
        val (timestamp, level, tag, message) = match.destructured
        val thread = if (message.startsWith("[thread=") && "] " in message)
            message.substringAfter("[thread=").substringBefore("] ") else null
        val payload = if (thread != null) message.substringAfter("] ") else message
        val event = payload.substringBefore(" · ").trim()
        return DisplayLogEntry(line, timestamp, level, tag, event,
            payload.substringAfter(" · ", "").trim(), classify(level, tag, event, line), thread = thread)
    }
    fun entries(raw: String): List<DisplayLogEntry> {
        val entries = mutableListOf<DisplayLogEntry>()
        var current:DisplayLogEntry?=null
        var stack=mutableListOf<String>()
        fun flush(){current?.let{entries.add(it.copy(stack=stack.toList()))};stack=mutableListOf()}
        for (line in raw.lineSequence()) {
            val parsed = parseHeader(line)
            if (parsed == null) {
                if (current != null) stack.add(line)
                continue
            }
            flush()
            current = parsed
        }
        flush()
        return entries
    }

    internal data class Presentation(val raw:List<DisplayLogEntry>,val compact:List<DisplayLogEntry>,
        val rawByTone:Map<LogTone,List<DisplayLogEntry>>,val compactByTone:Map<LogTone,List<DisplayLogEntry>>) {
        fun entries(tone:LogTone?,readable:Boolean):List<DisplayLogEntry> = if(tone==null){if(readable)compact else raw}
            else (if(readable)compactByTone else rawByTone)[tone].orEmpty()
    }
    internal fun prepare(raw:String):Presentation {
        val events=DetailedPerformance.measure(PerfWork.LOG_PARSE){entries(raw)}
        return DetailedPerformance.measure(PerfWork.LOG_FILTER){
            val compact=compact(events)
            Presentation(events,compact,events.groupBy{it.tone},compact.groupBy{it.tone})
        }
    }
    fun classify(level: String, tag: String, event: String, raw: String = ""): LogTone {
        // The source event's status wins unless a narrowly defined successful
        // completion had historically been written as INFO.
        if (tag == "HitMoz" && event.startsWith("Search watchdog expired") && LogExplanation.isCriticalSearchTimeout(raw)) return LogTone.ERROR
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

    /** Single source of explanations for the journal, coverage and export. */
    internal fun specificDetail(entry: DisplayLogEntry): LogExplanation.Detail? {
        TaskJournalDisplay.readable(entry)?.let { return LogExplanation.Detail(it) }
        StructuredLogExplanation.readable(entry)?.let { return LogExplanation.Detail(it) }
        if (entry.tag in legacyTags) {
            LogExplanation.forRecord(entry.original, entry.stack)
                ?.takeUnless { "пока нет точного пояснения" in it.meaning }?.let { return it }
        }
        return CurrentLogExplanation.readable(entry)?.let { LogExplanation.Detail(it) }
    }
    internal fun fallback(entry: DisplayLogEntry): LogExplanation.Detail = LogExplanation.Detail(when (entry.tone) {
        LogTone.ERROR -> "Зафиксирована ошибка. Подробности доступны в техническом журнале."
        LogTone.WARN -> "Зафиксировано предупреждение. Подробности доступны в техническом журнале."
        LogTone.OK -> "Операция завершилась успешно."
        LogTone.INFO -> "Событие приложения. Подробности доступны в техническом журнале."
        LogTone.DEBUG -> "Техническое диагностическое событие."
    })
    fun readable(entry: DisplayLogEntry): String = (specificDetail(entry) ?: fallback(entry)).meaning

    private val legacyTags = setOf("Muzmo", "HitMoz", "MusicSource", "MusicWebView", "MusicAds",
        "Recognition", "MusicArtwork", "Thumbnail", "App", "Crash", "Settings", "Logs",
        "Instagram", "InstagramProfile", "Diagnostics", "BrowserWebView", "MuzmoWebView", "Resolve")

    /** A short safe grouping label; never contains filenames or remote hosts. */
    fun groupName(entry: DisplayLogEntry): String = when (entry.tag) {
        "Muzmo", "HitMoz", "MusicSource", "MusicWebView", "MusicAds" -> "Поиск музыки"
        "Recognition", "MusicArtwork" -> "Распознавание музыки"
        "Download", "DownloadProgress" -> "Загрузки"
        "TaskCenter" -> "Центр задач"
        "Thumbnail" -> "Миниатюры"
        "App" -> "Приложение"
        "Crash" -> "Сбой приложения"
        "Settings" -> "Настройки"
        "Logs" -> "Журнал"
        else -> CurrentLogExplanation.group(entry.tag) ?: StructuredLogExplanation.group(entry.tag)
    }
}
