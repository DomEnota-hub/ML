package com.domenota.medialoader.data.vkcommunity

import com.domenota.medialoader.data.tasks.*
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.domenota.medialoader.MainActivity
import com.domenota.medialoader.core.logging.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import java.time.LocalDate
import java.time.ZoneId

/** One bounded worker per community job. Checkpoints live in SQLite, not WorkManager progress. */
class VkCommunityWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    private val db = VkCommunityDatabase(context)
    private var task:TaskReporter?=null

    override suspend fun doWork(): Result {
        AppLog.init(applicationContext)
        val jobId = inputData.getString(KEY_JOB) ?: return Result.failure()
        val mode = inputData.getString(KEY_MODE) ?: return Result.failure()
        val job = db.job(jobId) ?: return Result.failure()
        if(mode !in setOf(MODE_SCAN,MODE_DOWNLOAD))return Result.failure()
        val report=TaskReporter.create(TaskOwner.COMMUNITY,TaskKind.COMMUNITY,"$jobId:$mode")
        task=report
        report.report(stage=if(mode==MODE_SCAN)TaskStage.SCANNING else TaskStage.TRANSFER)
        AppLog.i("VkCommunity", "Job started · id=$jobId · mode=$mode")
        var noticeObserver:kotlinx.coroutines.Job?=null
        return try {
            setForeground(notice(jobId, mode))
            noticeObserver=CoroutineScope(currentCoroutineContext()).launch {
                TaskCenter.registry.liveTasks.collect{rows->
                    val row=rows.firstOrNull{it.id==taskId(TaskOwner.COMMUNITY,"$jobId:$mode")} ?: return@collect
                    if(!row.status.terminal)try{setForeground(notice(jobId,mode,row))}
                    catch(cancelled:CancellationException){throw cancelled}
                    catch(_:Exception){/* Optional progress must not fail the executor. */}
                }
            }
            when (mode) {
                MODE_SCAN -> scan(job)
                MODE_DOWNLOAD -> download(job)
                else -> return Result.failure()
            }
            val saved=db.job(jobId)
            val status=when(saved?.phase) {
                VkJobPhase.READY -> TaskStatus.COMPLETE
                VkJobPhase.FINISHED -> if(saved.failed>0)TaskStatus.PARTIAL else TaskStatus.COMPLETE
                VkJobPhase.CANCELLED -> TaskStatus.CANCELLED
                else -> TaskStatus.FAILED
            }
            report.report(status,files=if(mode==MODE_SCAN)countProgress(saved?.scannedPosts?:0,saved?.totalPosts?:0) else countProgress((saved?.downloaded?:0)+(saved?.duplicates?:0),saved?.selected?:0),errors=saved?.failed?:0,stage=if(mode==MODE_SCAN)TaskStage.SCANNING else TaskStage.TRANSFER)
            Result.success()
        } catch (error: CancellationException) {
            db.phase(jobId, VkJobPhase.CANCELLED)
            AppLog.i("VkCommunity", "Job cancelled · id=$jobId")
            report.report(TaskStatus.CANCELLED)
            throw error
        } catch (error: VkApiFailure) {
            if (error.kind == VkApiFailure.Kind.BAD_TOKEN) {
                applicationContext.getSharedPreferences("vk_community_ui", Context.MODE_PRIVATE)
                    .edit().putBoolean("key_verified", false).putBoolean("key_error", true).apply()
            }
            db.phase(jobId, VkJobPhase.ERROR, error.userMessage())
            AppLog.w("VkCommunity", "VK job failed · id=$jobId · kind=${error.kind} · ${error.diagnostic()}")
            report.report(TaskStatus.FAILED,errors=1)
            Result.failure()
        } catch (_: Exception) {
            db.phase(jobId, VkJobPhase.ERROR, "Не удалось завершить задачу VK")
            AppLog.w("VkCommunity", "VK job failed · id=$jobId · kind=internal")
            report.report(TaskStatus.FAILED,errors=1)
            Result.failure()
        } finally { noticeObserver?.cancel();task=null }
    }

    private suspend fun scan(job: VkCommunityJob) {
        val link = VkCommunityLink.parse(job.link)
            ?: throw VkApiFailure(VkApiFailure.Kind.OTHER)
        val token = VkTokenStore(applicationContext).read()
            ?: throw VkApiFailure(VkApiFailure.Kind.BAD_TOKEN)
        val period = VkDatePeriod.of(LocalDate.parse(job.startDate), LocalDate.parse(job.endDate))
        val zone = ZoneId.of(job.zoneId)
        val api = VkCommunityApi()
        var cursor = VkScanCursor(job.offset, job.totalPosts, false)
        db.phase(job.id, VkJobPhase.SCANNING)
        while (!isStopped) {
            val page = withRateBackoff { api.wallPage(link, cursor.offset, token) }
            applicationContext.getSharedPreferences("vk_community_ui", Context.MODE_PRIVATE)
                .edit().putBoolean("key_verified", true).putBoolean("key_error", false).apply()
            val parsed = VkWallParser.parse(page, period, zone)
            cursor = cursor.advance(parsed)
            db.addPage(job.id, parsed.images, cursor.offset, cursor.total)
            task?.report(files=countProgress(cursor.offset,cursor.total),stage=TaskStage.SCANNING)
            if (cursor.offset == parsed.scannedPosts || cursor.offset % 500 == 0)
                AppLog.d("VkCommunity", "Scan progress · id=${job.id} · posts=${cursor.offset} · photos=${db.job(job.id)?.found}")
            if (cursor.done) {
                db.phase(job.id, VkJobPhase.READY)
                AppLog.ok("VkCommunity", "Scan completed · id=${job.id} · posts=${cursor.offset} · photos=${db.job(job.id)?.found}")
                return
            }
            delay(350)
        }
        db.phase(job.id, VkJobPhase.CANCELLED)
    }

    private suspend fun download(job: VkCommunityJob) {
        db.resetInterrupted(job.id)
        val recovered = VkCommunityRepair(applicationContext, db).requeueMissingSelected(job.id)
        if (recovered > 0) AppLog.i("VkCommunity", "Missing selected files returned to queue · count=$recovered")
        db.phase(job.id, VkJobPhase.DOWNLOADING)
        val downloader = VkCommunityDownload(applicationContext, db)
        var consecutiveErrors = 0
        while (!isStopped) {
            val next = db.nextDownloads(job.id, 20)
            if (next.isEmpty()) {
                db.phase(job.id, VkJobPhase.FINISHED)
                AppLog.ok("VkCommunity", "Download completed · id=${job.id} · saved=${db.job(job.id)?.downloaded} · duplicates=${db.job(job.id)?.duplicates} · errors=${db.job(job.id)?.failed}")
                return
            }
            for (item in next) {
                if (isStopped) {
                    db.phase(job.id, VkJobPhase.CANCELLED)
                    return
                }
                val outcome = downloader.download(item)
                db.job(job.id)?.let{ live -> task?.report(files=countProgress(live.downloaded+live.duplicates,live.selected),errors=live.failed,stage=TaskStage.TRANSFER) }
                consecutiveErrors = if (outcome == VkItemPhase.ERROR) consecutiveErrors + 1 else 0
                if (consecutiveErrors >= 5) {
                    db.phase(job.id, VkJobPhase.ERROR,
                        "Пять ошибок подряд. Проверь сеть и повтори загрузку")
                    return
                }
                delay(250)
            }
        }
        db.phase(job.id, VkJobPhase.CANCELLED)
    }

    private suspend fun <T> withRateBackoff(block: suspend () -> T): T {
        var attempts = 0
        while (true) {
            try { return block() } catch (error: VkApiFailure) {
                if (error.kind !in setOf(VkApiFailure.Kind.RATE_LIMIT, VkApiFailure.Kind.SERVER) ||
                    attempts >= 3) throw error
                delay(1_000L shl attempts)
                attempts++
            }
        }
    }

    private fun notice(jobId: String, mode: String, snapshot:TaskSnapshot?=null): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL, "Загрузка из сообщества VK", NotificationManager.IMPORTANCE_LOW,
        ))
        val intent = TaskNotifications.open(applicationContext,taskId(TaskOwner.COMMUNITY,"$jobId:$mode"))
        val builder = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(TaskNotifications.icon(TaskKind.COMMUNITY))
            .setContentTitle("Загрузка из сообщества VK")
            .setContentText(if (mode == MODE_SCAN) "Поиск изображений" else "Сохранение изображений")
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(intent)
        if(snapshot!=null)TaskNotifications.apply(applicationContext,builder,snapshot,actions=false)
        val notification=builder.build()
        val id = 4200 + (jobId.hashCode() and 1023)
        return if (Build.VERSION.SDK_INT >= 29)
            ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(id, notification)
    }

    companion object {
        const val KEY_JOB = "job_id"
        const val KEY_MODE = "mode"
        const val MODE_SCAN = "scan"
        const val MODE_DOWNLOAD = "download"
        private const val CHANNEL = "vk-community"
    }
}
