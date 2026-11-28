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
    private data class Counts(val downloads:Int,val waiting:Int,val recognition:Int,val bulk:Boolean,
        val bytes:com.domenota.medialoader.data.tasks.TaskProgress?)
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
            combine(repository.downloads, repository.recognitionActive, repository.bulk.state, repository.missingRecoveryActive) { rows, recognition, bulk, recovering ->
                val current=rows.filter{it.state==DownloadState.QUEUED||it.state==DownloadState.RUNNING}
                val done=current.sumOf{it.bytesDownloaded.coerceAtLeast(0)}
                val total=current.sumOf{it.sizeBytes?.coerceAtLeast(0) ?: 0L}
                val known=current.isNotEmpty() && recovering==0 && total>0 && done in 0..total &&
                    current.all{it.sizeBytes?.let{size->size>0&&it.bytesDownloaded<=size}==true}
                Counts(current.size+recovering,current.count{it.networkWait!=null},recognition,
                    bulk.phase==BulkPhase.ANALYZING||bulk.phase==BulkPhase.QUEUING,
                    if(known)com.domenota.medialoader.data.tasks.TaskProgress(done,total)else null)
            }.collect { counts ->
                val downloads=counts.downloads
                val waiting=counts.waiting
                val recognition=counts.recognition
                val bulkActive=counts.bulk
                val active = downloads + recognition + if (bulkActive) 1 else 0
                if (active > 0) hasSeenWork = true
                if (hasSeenWork && active == 0) stopSelf()
                else runCatching {
                    getSystemService(NotificationManager::class.java).notify(
                        ID,
                        notification(downloads, recognition, bulkActive, waiting,counts.bytes),
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

    private fun notification(downloads: Int, recognition: Int, bulkActive: Boolean = false, waiting: Int = 0, bytes:com.domenota.medialoader.data.tasks.TaskProgress?=null): Notification {
        val intent = com.domenota.medialoader.data.tasks.TaskNotifications.open(this)
        val kind=if(recognition>0)com.domenota.medialoader.data.tasks.TaskKind.AUDIO_DUPLICATES
            else if(bulkActive)com.domenota.medialoader.data.tasks.TaskKind.BULK else com.domenota.medialoader.data.tasks.TaskKind.DOWNLOAD
        val title=buildList{if(downloads>0)add("Загрузка файлов");if(recognition>0)add("Распознавание музыки");if(bulkActive)add("Массовая загрузка")}.joinToString(" · ").ifBlank{"Подготовка загрузки"}
        val description=buildList {
            if(downloads>0)add(when{
                waiting==downloads->"Ожидают сеть: $waiting"
                waiting>0->"Активных: ${downloads-waiting} · ожидают сеть: $waiting"
                else->"Загружается файлов: $downloads"
            })
            if(recognition>0)add("Распознаётся треков: $recognition")
            if(bulkActive)add("Обрабатывается список ссылок")
        }.joinToString(" · ").ifBlank{"Подготовка загрузки"} + if(bytes!=null)" · Текущие файлы: ${com.domenota.medialoader.data.tasks.TaskCenterPresentation.bytes(bytes.done)} / ${com.domenota.medialoader.data.tasks.TaskCenterPresentation.bytes(requireNotNull(bytes.total))}" else ""
        return com.domenota.medialoader.data.tasks.TaskNotifications.decorate(NotificationCompat.Builder(this,CHANNEL),kind,title,description)
            .also{builder->if(bytes!=null&&downloads>waiting)builder.setProgress(100,((bytes.fraction ?: 0f)*100).toInt(),false)}
            .setOngoing(true).setContentIntent(intent).build()
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
