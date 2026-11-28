package com.domenota.medialoader.data.download

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.domenota.medialoader.MainActivity
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState

/** One quiet progress notification per queue batch, independent of file names. */
class DownloadNotifier(private val context: Context) {
    fun update(batchId: String, items: List<DownloadEntity>) {
        if (items.isEmpty() || !context.getSharedPreferences("ui", 0).getBoolean("notifications", true)) return
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        val system = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) system.createNotificationChannel(NotificationChannel(
            CHANNEL, "Загрузки", NotificationManager.IMPORTANCE_LOW))
        val completed = items.count { it.state == DownloadState.COMPLETED }
        val active = items.any { it.state == DownloadState.QUEUED || it.state == DownloadState.RUNNING }
        val waiting = items.count { it.networkWait != null }
        val onlyWaiting = waiting > 0 && items.none { it.state == DownloadState.RUNNING ||
            (it.state == DownloadState.QUEUED && it.networkWait == null) }
        // The foreground owner already shows active queue progress. Keep only the distinct terminal batch result.
        if(active && ForegroundServiceObservation.isForegroundObserved()){
            manager.cancel(batchId.hashCode());return
        }
        val failed=items.count{it.state==DownloadState.FAILED}
        val cancelled=items.count{it.state==DownloadState.CANCELLED}
        val rows=com.domenota.medialoader.data.tasks.DownloadTaskFeed.groups(items)
        val row=rows.singleOrNull()
        val title=when{
            onlyWaiting->"Загрузка · ожидание сети"
            active->"Загрузка файлов"
            failed>0->"Загрузка · есть ошибки"
            cancelled>0->"Загрузка · есть отменённые"
            else->"Загрузка завершена"
        }
        val text="Скачано: $completed из ${items.size} · ошибок: $failed · отменено: $cancelled"+
            if(waiting>0)" · ожидают: $waiting" else ""
        val builder=com.domenota.medialoader.data.tasks.TaskNotifications.decorate(
            NotificationCompat.Builder(context,CHANNEL),com.domenota.medialoader.data.tasks.TaskKind.DOWNLOAD,title,text)
            .setOnlyAlertOnce(true).setOngoing(active).setAutoCancel(!active)
            .setContentIntent(com.domenota.medialoader.data.tasks.TaskNotifications.open(context,row?.id))
        val notice=builder.build()
        runCatching { manager.notify(batchId.hashCode(), notice) }
    }

    private companion object { const val CHANNEL = "download_batches" }
}
