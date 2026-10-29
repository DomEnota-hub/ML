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
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(context, batchId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notice = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(if (active) android.R.drawable.stat_sys_download else android.R.drawable.stat_sys_download_done)
            .setContentTitle(if (active) "Загрузка файлов" else if (completed == items.size)
                "Загрузка завершена" else "Загрузка завершена с ошибками")
            .setContentText("Скачано $completed из ${items.size}")
            .setOnlyAlertOnce(true)
            .setOngoing(active)
            .setAutoCancel(!active)
            .setContentIntent(pending)
            .build()
        runCatching { manager.notify(batchId.hashCode(), notice) }
    }

    private companion object { const val CHANNEL = "download_batches" }
}
