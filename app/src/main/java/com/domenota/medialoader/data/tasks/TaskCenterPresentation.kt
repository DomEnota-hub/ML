package com.domenota.medialoader.data.tasks

internal enum class TaskSection(val label:String, val kinds:List<TaskKind>) {
    DOWNLOADS("Загрузки",listOf(TaskKind.DOWNLOAD,TaskKind.BULK,TaskKind.PROFILE,TaskKind.COMMUNITY)),
    LIBRARY("Медиатека",listOf(TaskKind.OCR,TaskKind.VISUAL_DUPLICATES,TaskKind.FILE_OPERATION)),
    MUSIC("Музыка",listOf(TaskKind.WAVEFORM,TaskKind.LOUDNESS,TaskKind.AUDIO_DUPLICATES)),
    EDITORS("Обработка медиа",listOf(TaskKind.EXPORT,TaskKind.BATCH_EDIT)),
    MODELS("AI-модели",listOf(TaskKind.MODEL))
}
internal object TaskCenterPresentation {
    fun active(rows:List<TaskSnapshot>)=rows.filter{it.active&&!it.idle}
    fun waiting(rows:List<TaskSnapshot>)=rows.filter{!it.active&&!it.idle&&!it.status.terminal}
    fun history(rows:List<TaskSnapshot>)=rows.filter{it.status.terminal}.sortedByDescending{it.updatedAt}
    fun errors(rows:List<TaskSnapshot>)=history(rows).filter{it.status==TaskStatus.FAILED||it.status==TaskStatus.PARTIAL||it.errors>0}
    fun bytes(value:Long):String {
        if(value<1024)return "$value Б"
        val units=listOf("КБ","МБ","ГБ","ТБ")
        var size=value/1024.0
        var index=0
        while(size>=1024&&index<units.lastIndex){size/=1024;index++}
        return String.format(java.util.Locale.forLanguageTag("ru"),"%.1f %s",size,units[index])
    }
    fun title(row:TaskSnapshot):String = if(row.kind==TaskKind.DOWNLOAD) {
        listOfNotNull(row.source?.label, if(row.children.isNotEmpty()||row.files!=null) "Группа файлов" else row.media?.label)
            .joinToString(" · ").ifBlank{row.kind.label}
    } else row.kind.label
    fun progressText(row:TaskSnapshot):String? = row.files?.let {
        val unit=when(row.kind){TaskKind.WAVEFORM,TaskKind.LOUDNESS,TaskKind.AUDIO_DUPLICATES->"треков";TaskKind.OCR->"фото";else->"файлов"}
        if(it.total!=null) "${it.done} из ${it.total} $unit" + if(row.kind==TaskKind.WAVEFORM) " · пакет" else "" else "${it.done} $unit"
    } ?: row.progress?.let { if(it.total!=null) "${bytes(it.done)} / ${bytes(it.total)}" else if(it.done>0) bytes(it.done) else null }
    fun expandable(row:TaskSnapshot)=row.children.isNotEmpty() || row.stage!=null || row.errors>0
    fun showBar(row:TaskSnapshot)=row.active || (!row.status.terminal&&!row.idle&&(row.files?.fraction!=null||row.progress?.fraction!=null||row.percent!=null))
}
