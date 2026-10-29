package com.domenota.medialoader.data.instagramprofile

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
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.provider.InstagramApiClient
import com.domenota.medialoader.core.provider.InstagramProvider
import com.domenota.medialoader.core.provider.ProviderException
import com.domenota.medialoader.data.MediaRepository
import com.domenota.medialoader.data.bulk.BulkIdentity
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

class InstagramProfileWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    private val store = InstagramProfileStore(context)
    private val repository = MediaRepository.get(context)
    private var stage = "initial"

    override suspend fun doWork(): Result {
        AppLog.init(applicationContext)
        val id = inputData.getString(KEY_JOB) ?: return Result.failure()
        val mode = inputData.getString(KEY_MODE) ?: return Result.failure()
        setForeground(notification(id, mode))
        return try {
            when (mode) {
                MODE_SCAN -> scan(id)
                MODE_DOWNLOAD -> download(id)
                else -> return Result.failure()
            }
            if (mode == MODE_SCAN && store.job(id)?.favoriteCheck == true)
                InstagramProfileController(applicationContext).scheduleNextFavoriteCheck(id)
            Result.success()
        } catch (cancelled: CancellationException) {
            store.phase(id, InstagramProfilePhase.CANCELLED)
            throw cancelled
        } catch (error: Exception) {
            val message = when (error) {
                is ProviderException -> error.message ?: "Instagram не ответил"
                is InstagramProfileFormatException -> error.message ?: "Изменился ответ Instagram"
                else -> "Не удалось завершить поиск профиля. Проверьте соединение и повторите."
            }
            store.phase(id, InstagramProfilePhase.ERROR, message)
            val reason = (error as? ProviderException)?.reason?.name ?: error.javaClass.simpleName
            AppLog.w("InstagramProfile", "Task stopped · id=$id · mode=$mode · stage=$stage · reason=$reason")
            if (mode == MODE_SCAN && store.job(id)?.favoriteCheck == true)
                InstagramProfileController(applicationContext).scheduleNextFavoriteCheck(id)
            Result.failure()
        }
    }

    private suspend fun scan(id: String) {
        val session = repository.sessions.cookies() ?: throw ProviderException(
            ProviderException.Reason.ACCESS_REQUIRED,
            "Для поиска профиля войдите в Instagram в настройках приложения.", "instagram")
        val api = InstagramProfileApi()
        var job = store.job(id) ?: return
        stage = "profile"
        var profile = job.userId?.let { userId -> InstagramProfileInfo(userId, job.username,
            job.profileName ?: job.username, job.avatarUrl, false) }
            ?: api.profile(job.username, session).also { info ->
                if (info.private) throw ProviderException(ProviderException.Reason.ACCESS_REQUIRED,
                    "Этот профиль закрыт. Поиск поддерживает открытые профили.", "instagram")
                store.setProfile(id, info)
            }
        store.phase(id, InstagramProfilePhase.SCANNING)
        while (!isStopped) {
            job = store.job(id) ?: return
            if (job.scanComplete || (job.startDate == null &&
                    InstagramProfileScanPolicy.reachedBatchLimit(store.posts(id).size, job.batchLimit))) {
                store.phase(id, InstagramProfilePhase.READY)
                AppLog.ok("InstagramProfile", "Scan paused · id=$id · scanned=${job.scanned} · complete=${job.scanComplete}")
                return
            }
            val reels = job.includeReels && !job.reelsDone && (job.feedDone || job.nextReels)
            stage = if (reels) "reels" else "feed"
            val cursor = if (reels) job.reelsCursor else job.feedCursor
            val page = api.page(profile, cursor, reels, session)
            if (page.posts.isEmpty() && page.cursor != null)
                throw InstagramProfileFormatException("Instagram вернул пустую страницу продолжения")
            if (page.cursor != null && page.cursor == cursor)
                throw InstagramProfileFormatException("Instagram повторил страницу поиска")
            if (job.startDate != null && page.posts.any { it.epochSecond <= 0L })
                throw InstagramProfileFormatException("Instagram не вернул даты публикаций")
            val decision = InstagramProfileScanPolicy.filter(page.posts, job.periodStart,
                job.periodEnd, ZoneId.systemDefault())
            store.addPage(id, job.username, decision.posts, reels, page.cursor,
                page.cursor == null || decision.reachedStart, job.scanned + page.posts.size)
            AppLog.i("InstagramProfile", "Page saved · id=$id · tab=${if (reels) "reels" else "feed"} · posts=${page.posts.size} · total=${job.scanned + page.posts.size}")
            delay(800)
        }
        store.phase(id, InstagramProfilePhase.CANCELLED)
    }

    private suspend fun download(id: String) {
        val job = store.job(id) ?: return
        val session = repository.sessions.cookies() ?: throw ProviderException(
            ProviderException.Reason.ACCESS_REQUIRED,
            "Сессия Instagram недоступна. Войдите снова.", "instagram")
        val api = InstagramApiClient()
        store.phase(id, InstagramProfilePhase.DOWNLOADING)
        var failures = 0
        for (stored in store.posts(id).filter { it.selected }) {
            if (isStopped) { store.phase(id, InstagramProfilePhase.CANCELLED); return }
            stage = "selected_post"
            try {
                val code = stored.post.code
                val visuals = InstagramProfileScanPolicy.selectedVisuals(InstagramProvider.parseApiResponse(
                    api.mediaInfo(InstagramProvider.mediaId(code), session), code)
                    .filter { it.type == MediaType.PHOTO || it.type == MediaType.VIDEO },
                    stored.excludedIds).map { it.copy(sourcePageUrl = stored.post.pageUrl) }
                val known = mutableListOf<String>()
                val missing = mutableListOf<com.domenota.medialoader.core.model.MediaItem>()
                val active = mutableListOf<com.domenota.medialoader.core.database.DownloadEntity>()
                for (taskId in stored.taskIds) repository.downloadById(taskId)?.let { task ->
                    if (task.state == DownloadState.QUEUED || task.state == DownloadState.RUNNING)
                        active += task
                }
                for (visual in visuals) {
                    val saved = repository.findDownloaded(visual)
                    val running = active.firstOrNull { it.bulkMediaKey == BulkIdentity.key(visual) }
                    when {
                        saved != null -> known += saved.id
                        running != null -> known += running.id
                        else -> missing += visual
                    }
                }
                val queued = if (missing.isEmpty()) emptyList() else repository.enqueueBulk(missing,
                    missing.map { java.util.UUID.randomUUID().toString() },
                    missing.map { "instagram-profile-$id-$code" })
                store.setTasks(id, code, known + queued.map { it.id })
                failures = 0
            } catch (error: CancellationException) { throw error }
            catch (error: ProviderException) {
                if (error.reason == ProviderException.Reason.RATE_LIMITED ||
                    error.reason == ProviderException.Reason.ACCESS_REQUIRED) throw error
                failures++
                AppLog.w("InstagramProfile", "Post not queued · id=$id · kind=${error.reason}")
            } catch (error: Exception) {
                failures++
                AppLog.w("InstagramProfile", "Post not queued · id=$id · kind=${error.javaClass.simpleName}")
            }
            if (failures >= 5) throw InstagramProfileFormatException("Пять публикаций подряд не удалось обработать")
            delay(2_000)
        }
        store.phase(id, InstagramProfilePhase.FINISHED)
        AppLog.ok("InstagramProfile", "Selected files queued · id=$id")
    }

    private fun notification(id: String, mode: String): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("instagram-profile",
            "Загрузка из профиля Instagram", NotificationManager.IMPORTANCE_LOW))
        val intent = PendingIntent.getActivity(applicationContext, 0,
            Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notice = NotificationCompat.Builder(applicationContext, "instagram-profile")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Профиль Instagram")
            .setContentText(if (mode == MODE_SCAN) "Поиск публикаций" else "Добавление файлов в загрузки")
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(intent).build()
        val noticeId = 5400 + (id.hashCode() and 1023)
        return if (Build.VERSION.SDK_INT >= 29)
            ForegroundInfo(noticeId, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(noticeId, notice)
    }

    companion object {
        const val KEY_JOB = "job_id"
        const val KEY_MODE = "mode"
        const val MODE_SCAN = "scan"
        const val MODE_DOWNLOAD = "download"
    }
}
