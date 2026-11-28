package com.domenota.medialoader.data.playback.alignment

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.domenota.medialoader.data.tasks.*
import kotlinx.coroutines.*

/** User-started local media processing; does not own or alter MediaSession. */
class LyricsAlignmentService:Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val runs=AlignmentServiceRunSlot()
    private var worker:Job?=null
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onCreate(){
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL,"Синхронизация текста",NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        runs.observe(startId)
        val token=intent?.getStringExtra("token")
        if(intent?.action==CANCEL){
            if(token!=null&&token==LyricsAlignmentCoordinator.token())LyricsAlignmentCoordinator.cancel(token)
            if(!runs.hasOwner())stopSelfResult(startId)
            return START_NOT_STICKY
        }
        if(!runs.claim(token,startId,LyricsAlignmentCoordinator.token())) {
            if(!runs.hasOwner())stopSelfResult(startId)
            return START_NOT_STICKY
        }
        val owned=requireNotNull(token)
        try {
            val notice=notification("Подготовка синхронизации",null,owned)
            if(Build.VERSION.SDK_INT>=35)startForeground(ID,notice,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
            else if(Build.VERSION.SDK_INT>=29)startForeground(ID,notice,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(ID,notice)
        }catch(error:Exception){
            LyricsAlignmentCoordinator.promotionFailed(owned,error)
            finishNotice(owned)
            runs.finish(owned)?.let{stopSelfResult(it)}
            return START_NOT_STICKY
        }
        val previous=worker
        worker=scope.launch {
            try {
                previous?.join()
                LyricsAlignmentCoordinator.execute(this@LyricsAlignmentService,owned)
            }finally{
                runs.finish(owned)?.let{stopSelfResult(it)}
            }
        }
        return START_NOT_STICKY
    }
    internal fun show(token:String,phase:String,percent:Int?){if(runs.owns(token))getSystemService(NotificationManager::class.java).notify(ID,notification(phase,percent,token))}
    internal fun finishNotice(token:String){if(runs.owns(token))stopForeground(STOP_FOREGROUND_REMOVE)}
    override fun onTimeout(startId:Int,fgsType:Int){
        runs.token()?.let{LyricsAlignmentCoordinator.serviceStopped(it);finishNotice(it)}
        scope.cancel();stopSelf(startId)
    }
    override fun onDestroy(){runs.token()?.let(LyricsAlignmentCoordinator::serviceStopped);scope.cancel();super.onDestroy()}
    private fun notification(phase:String,percent:Int?,token:String):Notification {
        val cancel=PendingIntent.getService(this,0,Intent(this,LyricsAlignmentService::class.java).setAction(CANCEL)
            .setData(android.net.Uri.parse("ml-task://lyrics-alignment/$token")).putExtra("token",token),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return TaskNotifications.decorate(NotificationCompat.Builder(this,CHANNEL),TaskKind.LYRICS,"Синхронизация текста песни",phase)
            .setContentIntent(TaskNotifications.open(this,LyricsAlignmentCoordinator.notificationTaskId))
            .setOngoing(true).setProgress(100,percent?:0,percent==null).addAction(0,"Отменить",cancel).build()
    }
    companion object {
        private const val CHANNEL="lyrics-alignment"
        private const val ID=8493
        private const val CANCEL="com.domenota.medialoader.CANCEL_LYRICS_ALIGNMENT"
        internal fun start(context:Context,token:String){ContextCompat.startForegroundService(context,
            Intent(context,LyricsAlignmentService::class.java).putExtra("token",token))}
    }
}
