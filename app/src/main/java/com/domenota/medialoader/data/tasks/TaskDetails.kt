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
    INTERRUPTED("Обработка прервана"), UNKNOWN("Причина не сохранена")
}
/** Classify actual exception types, not provider text, URL or presumed causes. */
internal object TaskFailureClassifier {
    fun from(error:Throwable):TaskFailure {
        var current:Throwable?=error
        val seen=hashSetOf<Throwable>()
        var io=false
        repeat(12){
            val e=current?:return if(io)TaskFailure.IO else TaskFailure.UNKNOWN
            if(!seen.add(e))return if(io)TaskFailure.IO else TaskFailure.UNKNOWN
            when(e){
                is SecurityException->return TaskFailure.PERMISSION
                is java.net.UnknownHostException,is java.net.SocketTimeoutException,is java.net.SocketException,is java.net.ProtocolException,is java.io.EOFException->return TaskFailure.NETWORK
                is android.system.ErrnoException->if(e.errno==android.system.OsConstants.ENOSPC)return TaskFailure.STORAGE
                is java.io.IOException->io=true
            }
            current=e.cause
        }
        return if(io)TaskFailure.IO else TaskFailure.UNKNOWN
    }
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
