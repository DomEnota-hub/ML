package com.domenota.medialoader.core.logging
import com.domenota.medialoader.data.tasks.*

/** Interpret only typed, bounded TaskCenter fields. Technical export remains unchanged. */
internal object TaskJournalDisplay {
    private fun fields(entry:DisplayLogEntry)=entry.metadata.split(" · ").mapNotNull{
        val key=it.substringBefore('=',"")
        if(key.isBlank())null else key to it.substringAfter('=')
    }.toMap()
    fun related(raw:String,token:TaskCorrelation):String=LogDisplay.entries(raw)
        .filter{it.tag=="TaskCenter"&&fields(it)["op"]==token.value}.joinToString("\n"){it.original}
    fun readable(entry:DisplayLogEntry):String? {
        if(entry.tag!="TaskCenter")return null
        val fields=fields(entry)
        val event=TaskEvent.entries.firstOrNull{it.name==entry.event.let { raw -> if(raw.startsWith("[thread=")) raw.substringAfter("] ",raw) else raw }}?:return null
        val kind=TaskKind.entries.firstOrNull{it.name==fields["kind"]}?:return null
        val action=when(event){
            TaskEvent.START->"Начато";TaskEvent.WAIT->"Ожидание";TaskEvent.PAUSE->"Приостановлено"
            TaskEvent.RESUME->"Продолжено";TaskEvent.CHECKPOINT->"Контрольная точка сохранена"
            TaskEvent.COMPLETE->"Завершено";TaskEvent.PARTIAL->"Частично завершено";TaskEvent.FAIL->"Ошибка"
            TaskEvent.CANCEL->"Отменено";TaskEvent.COMMAND->"Команда";TaskEvent.RECOVERY->"Восстановлено с контрольной точки"
        }
        fun count(key:String)=fields[key]?.toLongOrNull()?.takeIf{it>=0}
        return buildList {
            add("${kind.label}: $action")
            TaskStage.entries.firstOrNull{it.name==fields["stage"]}?.let{add("Этап: ${it.label}")}
            TaskWait.entries.firstOrNull{it.name==fields["wait"]}?.let{add(it.label)}
            TaskFailure.entries.firstOrNull{it.name==fields["failure"]}?.let{add(it.label)}
            count("done")?.let{done->add(count("total")?.takeIf{it>=done}?.let{"$done из $it"}?: "Обработано $done")}
            val counters=listOf("generated" to "Создано","cached" to "В кэше","skipped" to "Пропущено","failed" to "Ошибки")
            counters.forEach{(key,label)->count(key)?.let{add("$label: $it")}}
            if(count("failed")==null)count("errors")?.takeIf{it>0}?.let{add("Ошибки: $it")}
            count("checkpoints")?.let{add("Пакеты: $it")}
            count("elapsedMs")?.let{add(TaskCenterPresentation.duration(it))}
            if(event==TaskEvent.COMMAND){
                TaskCommand.entries.firstOrNull{it.name==fields["command"]}?.let{add(it.label)}
                when(fields["forwarded"]){ "true"->add("Передана исполнителю");"false"->add("Не передана") }
            }
        }.joinToString(" · ")
    }
}
