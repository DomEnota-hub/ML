package com.domenota.medialoader.data.tasks

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class TaskNotificationRequest(val id:String?,val sequence:Long)
/** A notification opens a redacted task screen, never a file URI or an executor command. */
internal object TaskNotificationNavigation {
    private var sequence=0L
    private val mutable=MutableStateFlow<TaskNotificationRequest?>(null)
    val request=mutable.asStateFlow()
    @Synchronized fun accept(intent:Intent?):Boolean {
        if(intent?.action!=TaskNotifications.OPEN_TASK)return false
        val id=intent.getStringExtra(TaskNotifications.EXTRA_TASK)
        if(id!=null&&!id.matches(Regex("[0-9a-f]{64}")))return false
        mutable.value=TaskNotificationRequest(id,++sequence)
        return true
    }
    fun consume(request:TaskNotificationRequest){mutable.compareAndSet(request,null)}
}
