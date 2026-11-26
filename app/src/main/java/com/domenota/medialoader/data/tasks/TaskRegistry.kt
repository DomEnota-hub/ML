package com.domenota.medialoader.data.tasks

import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class TaskKind(val label: String) {
    DOWNLOAD("Загрузка"), BULK("Массовая загрузка"), PROFILE("Сканирование профиля"),
    COMMUNITY("Сканирование сообщества"), EXPORT("Экспорт"), BATCH_EDIT("Пакетная обработка"),
    WAVEFORM("Звуковая волна"), MODEL("AI-модель"), VISUAL_DUPLICATES("Дубликаты фото и видео"),
    AUDIO_DUPLICATES("Дубликаты музыки"), OCR("Распознавание текста"), LOUDNESS("Анализ громкости"),
    FILE_OPERATION("Операция с файлами")
}
internal enum class TaskStatus(val label: String) {
    RUNNING("Выполняется"), QUEUED("В очереди"), PAUSED("Приостановлено"),
    WAITING("Ожидает условий"), COMPLETE("Завершено"), PARTIAL("Частично завершено"),
    FAILED("Ошибка"), CANCELLED("Отменено");
    val terminal get() = this in setOf(COMPLETE, PARTIAL, FAILED, CANCELLED)
}
internal enum class TaskWait(val label: String) {
    NETWORK("Сеть"), WIFI("Wi-Fi"), CHARGING("Зарядка"), PERMISSION("Разрешение"),
    STORAGE("Свободное место"), RESOURCE("Освобождение ресурсов")
}
internal enum class TaskStage(val label: String) {
    SCANNING("Проверка"), ENQUEUE("Подготовка очереди"), TRANSFER("Передача"),
    PROCESSING("Обработка"), SAVING("Сохранение"), VERIFYING("Проверка результата")
}
internal enum class TaskCommand(val label: String) {
    PAUSE("Пауза"), RESUME("Продолжить"), CANCEL("Отмена"), RETRY("Повторить неудачные"), OPEN("К результатам")
}
internal enum class TaskOwner {
    DOWNLOADS, BULK, PROFILE, COMMUNITY, EXPORT, BATCH, WAVEFORM, MODEL, VISUAL_SCAN,
    AUDIO_SCAN, OCR, LOUDNESS, FILES
}
internal data class TaskProgress(val done: Long, val total: Long?) {
    init { require(done >= 0 && (total == null || total > 0 && done <= total)) }
    val fraction: Float? get() = total?.let { (done.toDouble() / it).toFloat() }
}
internal data class TaskSnapshot(
    val id: String,
    val kind: TaskKind,
    val status: TaskStatus,
    val progress: TaskProgress? = null,
    val files: TaskProgress? = null,
    val errors: Int = 0,
    val wait: TaskWait? = null,
    val commands: Set<TaskCommand> = emptySet(),
    val children: List<TaskSnapshot> = emptyList(),
    val updatedAt: Long = 0L,
    val percent: Int? = null,
    val stage: TaskStage? = null,
) {
    init {
        require(id.matches(Regex("[a-f0-9]{64}")))
        require(percent == null || percent in 0..100)
        require(errors >= 0 && updatedAt >= 0)
        require(wait == null || status == TaskStatus.WAITING)
        require(children.map { it.id }.distinct().size == children.size)
    }
    val active get() = status == TaskStatus.RUNNING
}

/** Only enum labels, opaque IDs and counters cross this boundary. Never filenames, paths,
 * raw provider errors, media titles or thumbnails. Executors retain sole ownership of work. */
internal fun taskId(owner: TaskOwner, key: String): String =
    MessageDigest.getInstance("SHA-256").digest((owner.name + ":" + key).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }

internal class TaskRegistry {
    private data class Feed(val rows: List<TaskSnapshot>, val command: (String, TaskCommand) -> Unit)
    private val sources = linkedMapOf<TaskOwner, Feed>()
    private val mutable = MutableStateFlow<List<TaskSnapshot>>(emptyList())
    val tasks = mutable.asStateFlow()

    /** Replace an executor's complete authoritative snapshot, not append per progress tick. */
    @Synchronized fun publish(owner: TaskOwner, rows: List<TaskSnapshot>,
        command: (String, TaskCommand) -> Unit = { _, _ -> }) {
        val next = rows.toList()
        val ids = next.flatMap { listOf(it.id) + it.children.map(TaskSnapshot::id) }
        require(ids.distinct().size == ids.size)
        require(next.none { row -> row.children.any { it.children.isNotEmpty() } })
        sources[owner] = Feed(next, command)
        mutable.value = sources.values.flatMap { it.rows }
    }

    @Synchronized fun record(owner: TaskOwner, row: TaskSnapshot) {
        val old = sources[owner]
        publish(owner, old?.rows.orEmpty().filterNot { it.id == row.id } + row,
            old?.command ?: { _, _ -> })
    }
    @Synchronized fun remove(owner: TaskOwner, id: String) {
        val old = sources[owner] ?: return
        publish(owner, old.rows.filterNot { it.id == id }, old.command)
    }
    @Synchronized fun clear(owner: TaskOwner) {
        sources.remove(owner)
        mutable.value = sources.values.flatMap { it.rows }
    }

    /** Check fresh capability at dispatch. Adapter must also verify the executor's live state. */
    fun dispatch(id: String, command: TaskCommand): Boolean {
        val handler = synchronized(this) {
            sources.values.firstNotNullOfOrNull { feed ->
                val row = feed.rows.firstNotNullOfOrNull { top ->
                    if (top.id == id) top else top.children.firstOrNull { it.id == id }
                }
                if (row != null && command in row.commands) feed.command else null
            }
        } ?: return false
        handler(id, command)
        return true
    }
}

internal object TaskCenter {
    val registry = TaskRegistry()
}
