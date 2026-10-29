package com.domenota.medialoader.data.editor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.domenota.medialoader.MainActivity
import com.domenota.medialoader.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Foreground lifetime for long media transformations. */
class MediaProcessingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null
    private lateinit var coordinator: EditorExportCoordinator
    private var hasSeenWork = false

    override fun onCreate() {
        super.onCreate()
        coordinator = EditorExportCoordinator.get(applicationContext)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Обработка медиа", NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground(coordinator.state.value)

        if (intent?.action == ACTION_CANCEL) {
            coordinator.cancel("Обработка отменена")
        }

        if (observer == null) {
            observer = scope.launch {
                coordinator.state.collect { state ->
                    if (state.exporting) {
                        hasSeenWork = true
                        runCatching {
                            getSystemService(NotificationManager::class.java)
                                .notify(NOTIFICATION_ID, notification(state))
                        }
                    } else if (hasSeenWork) {
                        finishForeground(state)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        coordinator.cancel("Обработка остановлена системой по лимиту фоновой работы")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    override fun onDestroy() {
        observer?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startAsForeground(state: EditorExportState) {
        val notice = notification(state)
        if (Build.VERSION.SDK_INT >= 35) {
            startForeground(
                NOTIFICATION_ID,
                notice,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING,
            )
        } else if (Build.VERSION.SDK_INT >= 29) {
            // Android 14 and below have no mediaProcessing FGS type.
            startForeground(
                NOTIFICATION_ID,
                notice,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notice)
        }
    }

    private fun finishForeground(state: EditorExportState) {
        stopForeground(STOP_FOREGROUND_REMOVE)

        if (state.completedItemId != null || state.message != null) {
            val manager = getSystemService(NotificationManager::class.java)
            runCatching {
                manager.notify(
                    NOTIFICATION_ID,
                    completionNotification(state),
                )
            }
        }
        stopSelf()
    }

    private fun notification(state: EditorExportState): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val cancelIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, MediaProcessingService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(state.operation)
            .setContentText(
                if (state.progress > 0) "Готово на ${state.progress}%"
                else "Подготовка…",
            )
            .setProgress(100, state.progress.coerceIn(0, 100), state.progress <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(0, "Отменить", cancelIntent)
            .build()
    }

    private fun completionNotification(state: EditorExportState): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val success = state.completedItemId != null
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(
                if (success) android.R.drawable.stat_sys_download_done
                else android.R.drawable.stat_notify_error,
            )
            .setContentTitle(if (success) "Обработка завершена" else "Ошибка обработки")
            .setContentText(
                if (success) state.operation
                else state.message ?: "Не удалось обработать файл",
            )
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .build()
    }

    companion object {
        private const val CHANNEL = "media_processing"
        private const val NOTIFICATION_ID = 8293
        private const val ACTION_CANCEL = "com.domenota.medialoader.CANCEL_MEDIA_PROCESSING"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, MediaProcessingService::class.java),
            )
        }
    }
}
