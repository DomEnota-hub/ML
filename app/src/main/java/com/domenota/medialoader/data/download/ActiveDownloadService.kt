package com.domenota.medialoader.data.download

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
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.data.MediaRepository
import com.domenota.medialoader.data.bulk.BulkPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Keeps the process eligible to finish transfers after the activity goes into the background. */
class ActiveDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observer: Job? = null
    private var idleTimeout: Job? = null
    private var hasSeenWork = false

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Фоновая загрузка",
            NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notice = notification(0, 0)
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, notice,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(ID, notice)
        ForegroundServiceObservation.onForegroundStarted()
        if (observer == null) observer = scope.launch {
            val repository = MediaRepository.get(applicationContext)
            combine(repository.downloads, repository.recognitionActive, repository.bulk.state) { rows, recognition, bulk ->
                Triple(rows.count { it.state == DownloadState.QUEUED || it.state == DownloadState.RUNNING },
                    recognition, bulk.phase == BulkPhase.ANALYZING || bulk.phase == BulkPhase.QUEUING)
            }.collect { (downloads, recognition, bulkActive) ->
                val active = downloads + recognition + if (bulkActive) 1 else 0
                if (active > 0) hasSeenWork = true
                if (hasSeenWork && active == 0) stopSelf()
                else runCatching {
                    getSystemService(NotificationManager::class.java).notify(
                        ID,
                        notification(downloads, recognition, bulkActive),
                    )
                }
            }
        }
        idleTimeout?.cancel()
        idleTimeout = scope.launch {
            delay(15_000)
            if (!hasSeenWork) stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        ForegroundServiceObservation.onServiceDestroyed()
        observer?.cancel()
        idleTimeout?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(downloads: Int, recognition: Int, bulkActive: Boolean = false): Notification {
        val intent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Загрузчик")
            .setContentText(
                when {
                    recognition > 0 -> "Распознаётся музыка"
                    bulkActive -> "Обрабатывается список ссылок"
                    downloads == 0 -> "Подготовка загрузки"
                    else -> "Загружается файлов: $downloads"
                },
            )
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(intent).build()
    }

    companion object {
        private const val CHANNEL = "active_download_service"
        private const val ID = 8291
        fun start(context: Context) {
            ContextCompat.startForegroundService(context,
                Intent(context, ActiveDownloadService::class.java))
        }
    }
}
