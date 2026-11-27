package com.domenota.medialoader.data.tasks

import java.util.UUID

/** Random per logical operation, never derived from a media URI, name or account.
 * Only this token (not TaskSnapshot.id) may be used for journal correlation. */
internal data class TaskCorrelation(val value: String) {
    init { require(value.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"))) }
    companion object {
        fun create() = TaskCorrelation(UUID.randomUUID().toString())
        fun parse(value: String?) = value?.let { runCatching { TaskCorrelation(it) }.getOrNull() }
    }
}
internal enum class TaskTrigger { USER, BACKGROUND, PERIODIC, MEDIA_CHANGED, RECOVERY }
internal enum class TaskFailure(val label: String) {
    NETWORK("Ошибка соединения"), PERMISSION("Нет разрешения"), STORAGE("Недостаточно места"),
    DECODE("Ошибка обработки"), CACHE_WRITE("Результат не сохранён"), IO("Ошибка чтения или записи"),
    UNKNOWN("Причина не сохранена")
}
/** Mutually exclusive outcomes for a waveform sweep. Unknown counters remain absent on old rows. */
internal data class TaskOutcomes(
    val cached: Int = 0, val generated: Int = 0, val skipped: Int = 0, val failed: Int = 0
) {
    init { require(listOf(cached,generated,skipped,failed).all { it >= 0 }); require(checkedLong <= Int.MAX_VALUE) }
    private val checkedLong get() = cached.toLong()+generated+skipped+failed
    val checked get() = checkedLong.toInt()
}
internal data class TaskDetails(
    val correlation: TaskCorrelation? = null,
    val startedAt: Long? = null,
    val trigger: TaskTrigger? = null,
    val failure: TaskFailure? = null,
    val outcomes: TaskOutcomes? = null,
    val recovered:Boolean=false
) {
    init { require(startedAt == null || startedAt >= 0) }
}
internal enum class TaskEvent { START, WAIT, PAUSE, RESUME, CHECKPOINT, COMPLETE, PARTIAL, FAIL, CANCEL, COMMAND, RECOVERY }
