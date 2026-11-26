package com.domenota.medialoader.data.tasks

import android.content.Context
import com.domenota.medialoader.data.library.DuplicateBackgroundScan
import com.domenota.medialoader.data.library.VisualDuplicateBackgroundScan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

internal fun observeDuplicateTasks(context: Context, scope: CoroutineScope) {
    val process = UUID.randomUUID().toString()
    scope.launch(Dispatchers.Main.immediate) {
        var token = 0L
        var reporter: TaskReporter? = null
        DuplicateBackgroundScan.state.collect { s ->
            if (s.token != token) {
                reporter?.remove(); reporter = null; token = s.token
                if (s.running) reporter = TaskReporter.create(TaskOwner.AUDIO_SCAN,TaskKind.AUDIO_DUPLICATES,"$process:$token")
            }
            val status = when(s.outcome) {
                DuplicateBackgroundScan.Outcome.RUNNING -> TaskStatus.RUNNING
                DuplicateBackgroundScan.Outcome.PAUSED -> TaskStatus.PAUSED
                DuplicateBackgroundScan.Outcome.STOPPED -> TaskStatus.CANCELLED
                DuplicateBackgroundScan.Outcome.COMPLETED -> TaskStatus.COMPLETE
                DuplicateBackgroundScan.Outcome.FAILED -> TaskStatus.FAILED
                DuplicateBackgroundScan.Outcome.INTERRUPTED -> TaskStatus.PARTIAL
                else -> null
            }
            if(status!=null) reporter?.report(status,files=countProgress(s.done,s.total),
                errors=if(status==TaskStatus.FAILED) 1 else 0,stage=if(s.committing) TaskStage.SAVING else TaskStage.SCANNING,
                commands=if(s.running&&!s.committing)setOf(TaskCommand.PAUSE,TaskCommand.CANCEL)else emptySet(),onCommand={ action ->
                    val live=DuplicateBackgroundScan.state.value
                    if(live.token==s.token&&live.running&&!live.committing) when(action){TaskCommand.PAUSE->DuplicateBackgroundScan.pause(context);TaskCommand.CANCEL->DuplicateBackgroundScan.cancel(context);else->Unit}
                })
        }
    }
    scope.launch(Dispatchers.Main.immediate) {
        var token = 0L
        var reporter: TaskReporter? = null
        VisualDuplicateBackgroundScan.state.collect { s ->
            if (s.token != token) {
                reporter?.remove(); reporter = null; token = s.token
                if(s.running) reporter=TaskReporter.create(TaskOwner.VISUAL_SCAN,TaskKind.VISUAL_DUPLICATES,"$process:$token")
            }
            val status=when {
                s.running -> TaskStatus.RUNNING
                s.session!=null -> TaskStatus.COMPLETE
                s.stopped -> if(VisualDuplicateBackgroundScan.journalStatus(context)=="Приостановлен") TaskStatus.PAUSED else TaskStatus.CANCELLED
                s.error!=null -> TaskStatus.FAILED
                else -> null
            }
            if(status!=null) reporter?.report(status,files=countProgress(s.done,s.total),
                errors=if(status==TaskStatus.FAILED) 1 else 0,stage=if(s.committing) TaskStage.SAVING else TaskStage.SCANNING,
                commands=if(s.running&&!s.committing)setOf(TaskCommand.PAUSE,TaskCommand.CANCEL)else emptySet(),onCommand={ action ->
                    val live=VisualDuplicateBackgroundScan.state.value
                    if(live.token==s.token&&live.running&&!live.committing) when(action){TaskCommand.PAUSE->VisualDuplicateBackgroundScan.pause(context);TaskCommand.CANCEL->VisualDuplicateBackgroundScan.cancel(context);else->Unit}
                })
        }
    }
}
