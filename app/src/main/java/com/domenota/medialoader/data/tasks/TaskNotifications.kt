package com.domenota.medialoader.data.tasks

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.domenota.medialoader.MainActivity
import com.domenota.medialoader.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

internal data class TaskNotificationMessage(val title:String,val text:String,val percent:Int?)

/** Redacted enum labels and real counters only. The executors remain the source of truth. */
internal object TaskNotifications {
    const val OPEN_TASK="com.domenota.medialoader.OPEN_TASK"
    const val COMMAND="com.domenota.medialoader.TASK_COMMAND"
    const val EXTRA_TASK="task_notification_id"
    const val EXTRA_OPERATION="task_notification_operation"
    const val EXTRA_COMMAND="task_notification_command"
    private const val CHANNEL="task-center-progress"
    private val ownKinds=setOf(TaskKind.OCR,TaskKind.MODEL,TaskKind.WAVEFORM,TaskKind.LYRICS,TaskKind.LOUDNESS)
    fun message(row:TaskSnapshot):TaskNotificationMessage {
        val phase=when(row.status){
            TaskStatus.WAITING->"Ожидание: ${row.wait?.label ?: "условия"}"
            TaskStatus.PAUSED->"Пауза"
            TaskStatus.PARTIAL->if(row.errors>0)"Частично завершено" else "Завершено с пропусками"
            else->row.status.label
        }
        val text=buildList {
            if(!row.status.terminal)row.stage?.label?.let(::add)
            TaskCenterPresentation.progressText(row)?.let(::add)
            row.details?.outcomes?.let {
                add("Из кеша: ${it.cached} · создано: ${it.generated}")
                add("Пропущено: ${it.skipped} · ошибок: ${it.failed}")
            } ?: if(row.errors>0)add("Ошибок: ${row.errors}") else Unit
            if(row.status in setOf(TaskStatus.FAILED,TaskStatus.PARTIAL))row.details?.failure?.label?.let(::add)
        }.joinToString(" · ").ifBlank{phase}
        val fraction=row.files?.fraction ?: row.progress?.fraction
        val percent=if(row.status.terminal)null else fraction?.let{(it*100).toInt().coerceIn(0,100)} ?: row.percent
        return TaskNotificationMessage(TaskCenterPresentation.title(row)+" · "+phase,text,percent)
    }
    fun icon(kind:TaskKind):Int=when(kind){
        TaskKind.DOWNLOAD->R.drawable.ic_task_download
        TaskKind.BULK->R.drawable.ic_task_bulk
        TaskKind.PROFILE->R.drawable.ic_task_profile
        TaskKind.COMMUNITY->R.drawable.ic_task_community
        TaskKind.EXPORT->R.drawable.ic_task_export
        TaskKind.BATCH_EDIT->R.drawable.ic_task_edit
        TaskKind.WAVEFORM->R.drawable.ic_task_waveform
        TaskKind.MODEL->R.drawable.ic_task_model
        TaskKind.VISUAL_DUPLICATES->R.drawable.ic_task_images
        TaskKind.AUDIO_DUPLICATES->R.drawable.ic_task_music
        TaskKind.OCR->R.drawable.ic_task_ocr
        TaskKind.LOUDNESS->R.drawable.ic_task_loudness
        TaskKind.FILE_OPERATION->R.drawable.ic_task_folder
        TaskKind.LYRICS->R.drawable.ic_task_lyrics
    }
    fun open(context:Context,id:String?=null):PendingIntent {
        val safe=id?.takeIf{it.matches(Regex("[0-9a-f]{64}"))}
        val intent=Intent(context,MainActivity::class.java).setAction(OPEN_TASK)
            .setData(Uri.parse("ml-task://open/"+(safe ?: "overview")))
            .putExtra(EXTRA_TASK,safe).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context,0,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
    /** Decorations shared by service-owned notices; channels, FGS lifetime and actions stay owned by the service. */
    fun decorate(builder:NotificationCompat.Builder,kind:TaskKind,title:String,text:String):NotificationCompat.Builder =
        builder.setSmallIcon(icon(kind)).setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setColor(when(kind){
                TaskKind.DOWNLOAD,TaskKind.BULK->0xff448aff.toInt()
                TaskKind.OCR,TaskKind.LYRICS->0xffd5a638.toInt()
                TaskKind.WAVEFORM,TaskKind.AUDIO_DUPLICATES,TaskKind.LOUDNESS->0xff4bafaa.toInt()
                TaskKind.EXPORT,TaskKind.BATCH_EDIT->0xffbc79b9.toInt()
                else->0xff81a46a.toInt()
            })
    fun apply(context:Context,builder:NotificationCompat.Builder,row:TaskSnapshot,actions:Boolean=true):NotificationCompat.Builder {
        val message=message(row)
        decorate(builder,row.kind,message.title,message.text).setContentIntent(open(context,row.id))
        when(row.status){TaskStatus.FAILED->builder.setColor(0xffc74f5c.toInt());TaskStatus.PARTIAL->builder.setColor(0xffc08c24.toInt());else->Unit}
        if(!row.status.terminal && row.status!=TaskStatus.PAUSED && row.status!=TaskStatus.WAITING)
            builder.setProgress(100,message.percent ?: 0,message.percent==null)
        val operation=row.details?.correlation
        if(actions && operation!=null)listOf(TaskCommand.PAUSE,TaskCommand.RESUME,TaskCommand.RETRY,TaskCommand.CANCEL)
            .filter{it in row.commands}.take(3).forEach { command ->
                val intent=Intent(context,TaskNotificationCommandReceiver::class.java).setAction(COMMAND)
                    .setData(Uri.parse("ml-task://command/${row.id}/${operation.value}/${command.name}"))
                    .putExtra(EXTRA_TASK,row.id).putExtra(EXTRA_COMMAND,command.name).putExtra(EXTRA_OPERATION,operation.value)
                val pending=PendingIntent.getBroadcast(context,0,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                builder.addAction(0,command.label,pending)
            }
        return builder
    }
    fun canPost(context:Context)=Build.VERSION.SDK_INT<33 || ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED
    fun completionOwned(kind:TaskKind,status:TaskStatus?=null)=kind in ownKinds || kind in setOf(TaskKind.PROFILE,TaskKind.COMMUNITY,TaskKind.FILE_OPERATION) ||
        kind in setOf(TaskKind.AUDIO_DUPLICATES,TaskKind.VISUAL_DUPLICATES) && status in setOf(TaskStatus.FAILED,TaskStatus.PARTIAL,TaskStatus.CANCELLED)
    @OptIn(FlowPreview::class)
    fun observe(context:Context,registry:TaskRegistry,scope:CoroutineScope) {
        scope.launch {
            val manager=context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL,"Фоновые задачи",NotificationManager.IMPORTANCE_LOW))
            // Only our optional live namespace is reconciled; never touch FGS/MediaSession notices.
            runCatching{manager.activeNotifications.filter{it.tag=="ml-task-live"}.forEach{manager.cancel(it.tag,it.id)}}
            var shown=emptySet<String>()
            combine(registry.liveTasks,TaskHistoryStore.settings){rows,settings->rows to settings.notifications}.sample(1000).collect{(rows,enabled)->
                val live=if(enabled&&canPost(context))rows.filter{it.kind in ownKinds&&!it.idle&&!it.status.terminal} else emptyList()
                val ids=live.mapTo(hashSetOf()){it.id}
                (shown-ids).forEach{manager.cancel("ml-task-live",it.hashCode())}
                for(row in live)runCatching {
                    manager.notify("ml-task-live",row.id.hashCode(),apply(context,NotificationCompat.Builder(context,CHANNEL),row)
                        .setOngoing(row.active).setAutoCancel(!row.active).build())
                }
                shown=ids
            }
        }
    }
}

/** No URI, title or file data in the intent. Stale capabilities fail at the existing registry gate. */
class TaskNotificationCommandReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        if(intent.action!=TaskNotifications.COMMAND)return
        val id=intent.getStringExtra(TaskNotifications.EXTRA_TASK)?.takeIf{it.matches(Regex("[0-9a-f]{64}"))} ?: return
        val command=TaskCommand.entries.firstOrNull{it.name==intent.getStringExtra(TaskNotifications.EXTRA_COMMAND)} ?: return
        val operation=TaskCorrelation.parse(intent.getStringExtra(TaskNotifications.EXTRA_OPERATION)) ?: return
        if(command!=TaskCommand.OPEN)TaskCenter.registry.dispatchNotification(id,command,operation)
    }
}
