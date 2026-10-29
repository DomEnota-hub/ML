package com.domenota.medialoader.data.share

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.Operation
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.domenota.medialoader.MainActivity
import com.domenota.medialoader.core.logging.AppLog
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.provider.ProviderException
import com.domenota.medialoader.data.MediaRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Durable background analysis for shares. No "checking link" notification:
 * foreground promotion happens only AFTER download tasks are in the queue.
 *
 * Stay alive until all tasks reach a terminal state, so the existing in-process
 * DownloadQueue survives after the transparent receiver activity closes.
 */
class InstagramAutoShareWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val raw = inputData.getString(KEY_URL) ?: return Result.failure()
        val url = InstagramAutoShareSelection.canonicalUrl(raw) ?: return Result.failure()
        val notifications = ShareNotifications(applicationContext)
        try {
            val repo = MediaRepository.get(applicationContext)
            val resolved = when (val resolution = resolveAutomaticShare(runAttemptCount) { repo.resolve(url) }) {
                is AutomaticShareResolution.Ready -> resolution.value
                is AutomaticShareResolution.Retry -> {
                    AppLog.w("Share", "Instagram analysis deferred · attempt=${runAttemptCount + 1}/5", resolution.error)
                    notifications.show("Загрузка отложена",
                        "Нет связи с Instagram. Повторим автоматически при доступной сети (попытка ${runAttemptCount + 2} из 5)")
                    return Result.retry()
                }
            }
            val visuals = InstagramAutoShareSelection.visuals(resolved)
            if (visuals.isEmpty()) {
                notifications.show("Не удалось загрузить", "В публикации не найдены доступные фото или видео")
                return Result.failure()
            }

            val unseen = mutableListOf<com.domenota.medialoader.core.model.MediaItem>()
            for (item in visuals) {
                if (!repo.alreadyDownloaded(item)) unseen.add(item)
            }
            if (unseen.isEmpty()) {
                AppLog.i("Share", "Instagram publication already saved; no queue created")
                notifications.show("Файлы уже сохранены", "Новых фото и видео нет")
                return Result.success()
            }

            // This is the first moment we show a foreground notification.
            // Network constraints can stop and restart this worker after enqueue.
            // Reuse the same rows even if only part of the batch was persisted.
            val queued = repo.enqueueBulk(unseen,
                stableIds = unseen.map { automaticShareDownloadId(id.toString(), it) },
                groupIds = List(unseen.size) { "instagram_share_$id" })
            AppLog.i("Share", "Instagram auto-download queued · count=${queued.size}")
            setForeground(createForegroundInfo(queued.size, url))

            val ids = queued.map { it.id }
            while (!isStopped) {
                val states = ids.map { id -> repo.downloadById(id)?.state }
                if (states.all { state ->
                        state == DownloadState.COMPLETED ||
                        state == DownloadState.FAILED ||
                        state == DownloadState.CANCELLED
                    }) {
                    val completed = states.count { it == DownloadState.COMPLETED }
                    AppLog.i("Share", "Instagram automatic share done · completed=$completed/${ids.size}")
                    // The normal queue notifier owns the final notification and keeps
                    // one notification for the whole photo/video batch.
                    return Result.success()
                }
                delay(800L)
            }
            AppLog.w("Share", "Automatic share worker stopped; queue remains in history")
            return Result.retry()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            AppLog.w("Share", "Automatic Instagram download failed", error)
            val description = when (error) {
                is ProviderException -> when (error.reason) {
                    ProviderException.Reason.ACCESS_REQUIRED -> "Instagram требует входа в аккаунт"
                    ProviderException.Reason.RATE_LIMITED -> "Instagram ограничил запросы; повторите позднее"
                    ProviderException.Reason.UNSUPPORTED -> "Ссылка не поддерживается или публикация недоступна"
                    else -> "Не удалось получить фото или видео"
                }
                else -> if (error is java.net.UnknownHostException ||
                    error is java.net.SocketTimeoutException || error is java.net.SocketException)
                    "Не удалось соединиться с Instagram. Отправьте ссылку повторно после восстановления связи"
                else "Проверьте соединение, ссылку и доступ к папке"
            }
            notifications.show("Не удалось загрузить", description)
            return Result.failure()
        }
    }

    private fun createForegroundInfo(count: Int, url: String): ForegroundInfo {
        val notifier = ShareNotifications(applicationContext)
        val notification = notifier.build(
            title = "Загрузка началась",
            description = "Instagram · файлов: $count",
            ongoing = true,
        )
        val identifier = FOREGROUND_NOTIFICATION_BASE + (url.hashCode() and Int.MAX_VALUE) % 100_000
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(identifier, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else ForegroundInfo(identifier, notification)
    }

    companion object {
        private const val KEY_URL = "instagram_url"
        private const val FOREGROUND_NOTIFICATION_BASE = 20_000

        fun schedule(context: Context, canonicalUrl: String): Operation {
            val normalized = InstagramAutoShareSelection.canonicalUrl(canonicalUrl)
                ?: throw IllegalArgumentException("Only Instagram media links allowed")
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(normalized.toByteArray(Charsets.UTF_8))
                .take(12).joinToString("") { "%02x".format(it) }
            val builder = OneTimeWorkRequestBuilder<InstagramAutoShareWorker>()
                .setInputData(workDataOf(KEY_URL to normalized))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            // Pre-Android 12 expedited WorkManager requires a foreground notification
            // before work starts. Keep analysis silent across all supported devices.
            if (Build.VERSION.SDK_INT >= 31) {
                builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            }
            val task = builder.build()
            val operation = WorkManager.getInstance(context).enqueueUniqueWork(
                "instagram_visual_$digest", ExistingWorkPolicy.KEEP, task)
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            if (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) != true) {
                ShareNotifications(context).show("Ожидание сети",
                    "Ссылка сохранена. Загрузка начнётся автоматически, когда появится подключение")
            }
            return operation
        }
    }
}

private class ShareNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val channel = "instagram_automatic_share"

    fun build(title: String, description: String, ongoing: Boolean): Notification {
        manager.createNotificationChannel(NotificationChannel(
            channel, "Instagram — автоматическая загрузка", NotificationManager.IMPORTANCE_LOW))
        val openApp = android.app.PendingIntent.getActivity(
            context, 1, Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(description)
            .setOnlyAlertOnce(true)
            .setAutoCancel(!ongoing)
            .setOngoing(ongoing)
            .setContentIntent(openApp)
            .build()
    }

    fun show(title: String, description: String) {
        // Ordinary alerts follow the user's existing notification preference.
        if (!context.getSharedPreferences("ui", Context.MODE_PRIVATE)
                .getBoolean("notifications", true)) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED) return
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        manager.notify(18_212, build(title, description, ongoing = false))
    }
}
