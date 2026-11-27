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
    STORAGE("Свободное место"), RESOURCE("Освобождение ресурсов"), USER_ACTION("Действие пользователя")
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
internal enum class TaskSource(val label:String) { INSTAGRAM("Instagram"), YOUTUBE("YouTube"), VK("VK"), RUTUBE("RUTUBE");
    companion object { fun from(raw:String) = entries.firstOrNull { it.name.equals(raw,ignoreCase=true) } }
}
internal enum class TaskMedia(val label:String) { PHOTO("Фото"), VIDEO("Видео"), AUDIO("Аудио") }
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
    val source: TaskSource? = null,
    val media: TaskMedia? = null,
    val idle: Boolean = false,
    val details: TaskDetails? = null,
) {
    init {
        require(id.matches(Regex("[a-f0-9]{64}")))
        require(percent == null || percent in 0..100)
        require(errors >= 0 && updatedAt >= 0)
        require(wait == null || status == TaskStatus.WAITING)
        require(!idle || status == TaskStatus.PAUSED)
        require(children.map { it.id }.distinct().size == children.size)
    }
    val active get() = status == TaskStatus.RUNNING
}

/** Only enum labels, opaque IDs and counters cross this boundary. Never filenames, paths,
 * raw provider errors, media titles or thumbnails. Executors retain sole ownership of work. */
internal fun taskId(owner: TaskOwner, key: String): String =
    MessageDigest.getInstance("SHA-256").digest((owner.name + ":" + key).toByteArray(Charsets.UTF_8))
        .let { bytes -> buildString(64) {
        val digits="0123456789abcdef"
        for(byte in bytes) { val n=byte.toInt() and 255; append(digits[n ushr 4]); append(digits[n and 15]) }
    } }

internal class TaskRegistry(private val eventSink:(TaskJournalEvent)->Unit = {}) {
    private data class Feed(val rows: List<TaskSnapshot>, val command: (String, TaskCommand) -> Unit)
    private val sources = linkedMapOf<TaskOwner, Feed>()
    private val rowHandlers=hashMapOf<String,(TaskCommand)->Unit>()
    private val mutable = MutableStateFlow<List<TaskSnapshot>>(emptyList())
    val tasks = mutable.asStateFlow()
    private val liveMutable = MutableStateFlow<List<TaskSnapshot>>(emptyList())
    val liveTasks = liveMutable.asStateFlow()
    private val registeredMutable=MutableStateFlow<Set<TaskOwner>>(emptySet())
    val registeredOwners=registeredMutable.asStateFlow()
    private val saved = MutableStateFlow(TaskHistoryData())
    val history = saved.asStateFlow()
    private var retentionDays = 7
    private val seen = hashMapOf<String,TaskStatus>()
    private val started = hashMapOf<String,Long>()
    private val observed = hashMapOf<String,TaskSnapshot>()
    private data class FailureAnnotation(val failure:TaskFailure,val stage:TaskStage?)
    private val downloadFailures=linkedMapOf<String,FailureAnnotation>()
    /** Typed evidence from the existing download executor; never an independent task or command. */
    @Synchronized fun noteDownloadFailure(id:String,failure:TaskFailure,stage:TaskStage?) {
        require(id.matches(Regex("[a-f0-9]{64}")))
        downloadFailures[id]=FailureAnnotation(failure,stage)
        while(downloadFailures.size>512)downloadFailures.remove(downloadFailures.keys.first())
    }
    @Synchronized fun clearDownloadFailure(id:String){downloadFailures.remove(id)}
    private fun takeDownloadFailure(row:TaskSnapshot):FailureAnnotation? {
        if(row.kind!=TaskKind.DOWNLOAD||!row.status.terminal||row.errors<=0)return null
        val ids=if(row.children.isEmpty())listOf(row.id) else row.children.filter{it.errors>0}.map{it.id}
        val found=ids.map{downloadFailures.remove(it)}
        if(found.none{it!=null})return null
        val uniform=found.firstOrNull()?.takeIf{known->found.all{it==known}}
        return uniform?:FailureAnnotation(TaskFailure.UNKNOWN,null)
    }
    private var completion: ((TaskSnapshot)->Unit)? = null

    @Synchronized fun configure(days: Int, onCompletion: ((TaskSnapshot)->Unit)? = completion) {
        require(days in TaskSettings.RETENTION)
        retentionDays=days; completion=onCompletion; rebuild()
    }
    @Synchronized fun restore(data: TaskHistoryData) {
        val cutoff=maxOf(data.clearedAt,saved.value.clearedAt)
        val merged=data.rows.filter{it.updatedAt>cutoff}.associateBy{it.id}.toMutableMap()
        for(row in saved.value.rows.filter{it.updatedAt>cutoff}) {
            val old=merged[row.id]
            merged[row.id]=if(old!=null&&old.status==row.status&&row.details==null&&old.updatedAt>=row.updatedAt)old else row
        }
        saved.value=TaskHistoryData(cutoff,merged.values.toList()); rebuild()
    }
    @Synchronized fun clearHistory() {
        saved.value=TaskHistoryData(System.currentTimeMillis(),emptyList()); rebuild()
    }
    private fun rebuild() {
        val now=System.currentTimeMillis()
        val cutoff=if(retentionDays==0) saved.value.clearedAt else maxOf(saved.value.clearedAt,now-retentionDays*86_400_000L)
        val archivedById=saved.value.rows.associateBy{it.id}
        val live=sources.values.flatMap { it.rows }.map { observe(it,now,archivedById[it.id]) }
        val nested=live.flatMap{it.children}.mapTo(hashSetOf()){it.id}
        val archive=saved.value.rows.filter { it.updatedAt>cutoff && it.id !in nested }.associateBy { it.id }.toMutableMap()
        for(row in live) {
            if(row.idle) { seen.remove(row.id); started.remove(row.id); continue }
            val before=seen.put(row.id,row.status)
            if(!row.status.terminal) {
                archive.remove(row.id)
                started.putIfAbsent(row.id,now)
            } else {
                val start=started.remove(row.id)
                val ended=if(before!=null && !before.terminal) now else row.updatedAt
                if(ended>cutoff && (before==null || !before.terminal || row.id in archive)) {
                    val old=archive[row.id]
                    archive[row.id]=row.copy(updatedAt=maxOf(ended,old?.updatedAt?:0),commands=emptySet(),children=emptyList(),source=null,media=null)
                    if(start!=null && now-start>=30_000 && before!=null && !before.terminal) completion?.invoke(row)
                }
            }
        }
        val rows=archive.values.sortedByDescending { it.updatedAt }
        saved.value=TaskHistoryData(saved.value.clearedAt,rows)
        val allowed=archive.keys
        val shown=live.filter { !it.idle && (!it.status.terminal || it.id in allowed) }
        val liveKeys=live.mapTo(hashSetOf()){it.id}
        seen.keys.retainAll(liveKeys); started.keys.retainAll(liveKeys); rowHandlers.keys.retainAll(liveKeys); observed.keys.retainAll(liveKeys)
        val liveIds=shown.mapTo(hashSetOf()) { it.id }
        mutable.value=shown+rows.filterNot { it.id in liveIds }
        liveMutable.value=live.filter { !it.status.terminal || it.kind == TaskKind.OCR }
        registeredMutable.value=sources.keys.toSet()
    }

    private fun emit(event:TaskEvent,row:TaskSnapshot,now:Long,command:TaskCommand?=null,forwarded:Boolean?=null) {
        TaskJournalEvent.from(event,row,now,command,forwarded)?.let { runCatching { eventSink(it) } }
    }
    private fun observe(raw:TaskSnapshot,now:Long,archivedRow:TaskSnapshot?):TaskSnapshot {
        if(raw.idle) { observed.remove(raw.id);return raw }
        val previous=observed[raw.id]
        val restarted=previous?.status?.terminal==true&&!raw.status.terminal
        val archived=archivedRow?.takeIf{it.status==raw.status}
        val base=if(restarted)null else previous?.details ?: archived?.details
        // Historical terminal feed rows are not fresh events. Never assign an operation token to them.
        val failure=takeDownloadFailure(raw)
        val metadata=raw.details ?: base ?: if(!raw.status.terminal||failure!=null)TaskDetails(TaskCorrelation.create(),if(raw.status.terminal)null else now) else null
        val details=if(failure!=null)metadata?.copy(failure=failure.failure) else metadata
        val stage=raw.stage?:failure?.stage?:previous?.stage?.takeIf{!restarted}?:archived?.stage
        val row=if(details==raw.details&&stage==raw.stage)raw else raw.copy(details=details,stage=stage)
        val old=previous?.takeIf { !restarted && it.details?.correlation==details?.correlation }
        val event=when {
            details?.correlation==null -> null
            old==null && row.status.terminal -> if((raw.details?.correlation!=null||failure!=null) && archived?.details?.correlation!=details?.correlation)terminalEvent(row.status) else null
            old==null -> if(details?.recovered==true)TaskEvent.RECOVERY else if(row.status==TaskStatus.WAITING)TaskEvent.WAIT else TaskEvent.START
            old.status!=row.status -> when(row.status) {
                TaskStatus.RUNNING->TaskEvent.RESUME;TaskStatus.WAITING->TaskEvent.WAIT;TaskStatus.PAUSED->TaskEvent.PAUSE
                TaskStatus.QUEUED->TaskEvent.CHECKPOINT;else->terminalEvent(row.status)
            }
            row.status==TaskStatus.WAITING&&old.wait!=row.wait -> TaskEvent.WAIT
            else -> null
        }
        observed[row.id]=row
        event?.let{emit(it,row,now)}
        return row
    }
    private fun terminalEvent(status:TaskStatus):TaskEvent?=when(status){
        TaskStatus.COMPLETE->TaskEvent.COMPLETE;TaskStatus.PARTIAL->TaskEvent.PARTIAL
        TaskStatus.FAILED->TaskEvent.FAIL;TaskStatus.CANCELLED->TaskEvent.CANCEL;else->null
    }

    /** Replace an executor's complete authoritative snapshot, not append per progress tick. */
    @Synchronized fun publish(owner: TaskOwner, rows: List<TaskSnapshot>,
        command: (String, TaskCommand) -> Unit = { _, _ -> }) {
        val next = rows.toList()
        val ids = next.flatMap { listOf(it.id) + it.children.map(TaskSnapshot::id) }
        require(ids.distinct().size == ids.size)
        require(next.none { row -> row.children.any { it.children.isNotEmpty() } })
        sources[owner] = Feed(next, command)
        rebuild()
    }

    @Synchronized fun record(owner: TaskOwner, row: TaskSnapshot, handler: ((TaskCommand)->Unit)?=null) {
        if(handler!=null && row.commands.isNotEmpty())rowHandlers[row.id]=handler else rowHandlers.remove(row.id)
        val old = sources[owner]
        publish(owner, old?.rows.orEmpty().filterNot { it.id == row.id || it.status.terminal } + row,
            old?.command ?: { _, _ -> })
    }
    @Synchronized fun remove(owner: TaskOwner, id: String) {
        val old = sources[owner] ?: return
        publish(owner, old.rows.filterNot { it.id == id }, old.command)
    }
    @Synchronized fun clear(owner: TaskOwner) {
        sources.remove(owner)
        rebuild()
    }

    /** Check fresh capability at dispatch. Adapter must also verify the executor's live state. */
    fun dispatch(id: String, command: TaskCommand): Boolean {
        val handler = synchronized(this) {
            sources.values.firstNotNullOfOrNull { feed ->
                val row = feed.rows.firstNotNullOfOrNull { top ->
                    if (top.id == id) top else top.children.firstOrNull { it.id == id }
                }
                if (row != null && command in row.commands) rowHandlers[id]?.let { callback -> { _:String, action:TaskCommand -> callback(action) } } ?: feed.command else null
            }
        } ?: return false
        val row=synchronized(this) { observed[id] ?: observed.values.firstOrNull { top->top.children.any { it.id==id } } }
        return try {
            handler(id, command)
            row?.let{emit(TaskEvent.COMMAND,it,System.currentTimeMillis(),command,true)}
            true
        } catch(_:Exception) {
            row?.let{emit(TaskEvent.COMMAND,it,System.currentTimeMillis(),command,false)}
            false
        }
    }
}

internal object TaskCenter {
    val registry = TaskRegistry(TaskJournal::write)
    fun initialize(context: android.content.Context) = TaskHistoryStore.start(context.applicationContext,registry)
}
