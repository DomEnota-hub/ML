package com.domenota.medialoader.data.tasks

internal enum class TaskSection(val label:String, val kinds:List<TaskKind>) {
    DOWNLOADS("Загрузки",listOf(TaskKind.DOWNLOAD,TaskKind.BULK,TaskKind.PROFILE,TaskKind.COMMUNITY)),
    LIBRARY("Медиатека",listOf(TaskKind.OCR,TaskKind.VISUAL_DUPLICATES,TaskKind.FILE_OPERATION)),
    MUSIC("Музыка",listOf(TaskKind.WAVEFORM,TaskKind.LOUDNESS,TaskKind.AUDIO_DUPLICATES,TaskKind.LYRICS)),
    EDITORS("Обработка медиа",listOf(TaskKind.EXPORT,TaskKind.BATCH_EDIT)),
    MODELS("AI-модели",listOf(TaskKind.MODEL))
}
internal object TaskCenterPresentation {
    fun active(rows:List<TaskSnapshot>)=rows.filter{it.active&&!it.idle}
    fun waiting(rows:List<TaskSnapshot>)=rows.filter{!it.idle&&it.status in setOf(TaskStatus.WAITING,TaskStatus.QUEUED)}
    fun paused(rows:List<TaskSnapshot>)=rows.filter{!it.idle&&it.status==TaskStatus.PAUSED}
    fun owner(kind:TaskKind):TaskOwner=when(kind){
        TaskKind.DOWNLOAD->TaskOwner.DOWNLOADS;TaskKind.BULK->TaskOwner.BULK;TaskKind.PROFILE->TaskOwner.PROFILE
        TaskKind.COMMUNITY->TaskOwner.COMMUNITY;TaskKind.EXPORT->TaskOwner.EXPORT;TaskKind.BATCH_EDIT->TaskOwner.BATCH
        TaskKind.WAVEFORM->TaskOwner.WAVEFORM;TaskKind.MODEL->TaskOwner.MODEL;TaskKind.VISUAL_DUPLICATES->TaskOwner.VISUAL_SCAN
        TaskKind.AUDIO_DUPLICATES->TaskOwner.AUDIO_SCAN;TaskKind.OCR->TaskOwner.OCR;TaskKind.LOUDNESS->TaskOwner.LOUDNESS;TaskKind.FILE_OPERATION->TaskOwner.FILES;TaskKind.LYRICS->TaskOwner.LYRICS
    }
    fun idleLabel(kind:TaskKind,known:Set<TaskOwner>)=if(owner(kind) in known)"Нет задач" else "Нет данных о состоянии"
    fun focusFirst(rows:List<TaskSnapshot>,id:String?)=if(id==null)rows else rows.sortedBy{it.id!=id}
    fun history(rows:List<TaskSnapshot>)=rows.filter{it.status.terminal}.sortedByDescending{it.updatedAt}
    fun errors(rows:List<TaskSnapshot>)=history(rows).filter{it.status==TaskStatus.FAILED||it.errors>0}
    fun latestConfirmed(rows:List<TaskSnapshot>,kind:TaskKind):TaskSnapshot? =
        history(rows).firstOrNull { it.kind==kind && it.status in setOf(TaskStatus.COMPLETE,TaskStatus.PARTIAL) && it.details?.outcomes!=null }
    fun partial(rows:List<TaskSnapshot>)=history(rows).filter{it.status==TaskStatus.PARTIAL}
    fun duration(ms:Long):String {
        val seconds=ms.coerceAtLeast(0)/1000
        return if(seconds>=3600)"${seconds/3600} ч ${seconds/60%60} мин" else if(seconds>=60)"${seconds/60} мин ${seconds%60} с" else "$seconds с"
    }
    fun triggerLabel(trigger:TaskTrigger)=when(trigger){
        TaskTrigger.USER->"Вручную";TaskTrigger.BACKGROUND->"Фоновая проверка";TaskTrigger.PERIODIC->"Периодическая проверка"
        TaskTrigger.MEDIA_CHANGED->"Изменение медиатеки";TaskTrigger.RECOVERY->"Восстановление"
    }
    fun advice(failure:TaskFailure):String?=when(failure){
        TaskFailure.NETWORK->"Проверить подключение и повторить операцию"
        TaskFailure.PERMISSION->"Проверить разрешения приложения"
        TaskFailure.STORAGE->"Проверить свободное место"
        TaskFailure.DECODE->"Проверить доступность и формат файла"
        TaskFailure.CACHE_WRITE,TaskFailure.IO->"Проверить доступ к файлу и свободное место"
        TaskFailure.INTERRUPTED->"Повторить операцию"
        TaskFailure.UNKNOWN->null
    }
    fun bytes(value:Long):String {
        if(value<1024)return "$value Б"
        val units=listOf("КБ","МБ","ГБ","ТБ")
        var size=value/1024.0
        var index=0
        while(size>=1024&&index<units.lastIndex){size/=1024;index++}
        return String.format(java.util.Locale.forLanguageTag("ru"),"%.1f %s",size,units[index])
    }
    fun title(row:TaskSnapshot):String = if(row.kind==TaskKind.DOWNLOAD) {
        if(row.children.isNotEmpty()||row.files!=null)"Загрузка группы" else when(row.media){TaskMedia.AUDIO->"Загрузка аудио";TaskMedia.VIDEO->"Загрузка видео";TaskMedia.PHOTO->"Загрузка фото";null->row.kind.label}
    } else if(row.kind==TaskKind.WAVEFORM)"Генерация звуковых волн" else row.kind.label
    fun progressText(row:TaskSnapshot):String? = row.files?.let {
        val unit=when(row.kind){TaskKind.WAVEFORM,TaskKind.LOUDNESS,TaskKind.AUDIO_DUPLICATES,TaskKind.LYRICS->"треков";TaskKind.OCR->"фото";else->"файлов"}
        if(it.total!=null) "${it.done} из ${it.total} $unit" else "${it.done} $unit"
    } ?: row.progress?.let { if(it.total!=null) "${bytes(it.done)} / ${bytes(it.total)}" else if(it.done>0) bytes(it.done) else null }
    fun expandable(row:TaskSnapshot)=row.children.isNotEmpty() || row.stage!=null || row.errors>0 || row.details!=null || row.status.terminal
    fun showBar(row:TaskSnapshot)=row.active || (!row.status.terminal&&!row.idle&&(row.files?.fraction!=null||row.progress?.fraction!=null||row.percent!=null))
}
