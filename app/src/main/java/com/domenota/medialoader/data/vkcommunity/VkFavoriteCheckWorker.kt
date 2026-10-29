package com.domenota.medialoader.data.vkcommunity

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
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.json.JSONObject

/** One foreground worker reads favorites in sequence to avoid parallel VK API bursts. */
class VkFavoriteCheckWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    private val database = VkCommunityDatabase(context)
    private val store = VkFavoriteCheckStore(context)
    private val favorites = VkFavoriteStore(context)

    override suspend fun doWork(): Result {
        val batchId = inputData.getString(KEY_BATCH) ?: return Result.failure()
        val batch = store.current()?.takeIf { it.id == batchId } ?: return Result.success()
        setForeground(notice())
        val token = VkTokenStore(applicationContext).read()
        if (token == null) {
            failPending(batch, "Сервисный ключ VK отсутствует. Добавьте ключ и повторите проверку")
            return Result.failure()
        }
        val api = VkCommunityApi()
        for (entry in batch.entries) {
            if (isStopped || store.current()?.id != batchId) {
                failPending(batch, "Проверка прервана. Её можно повторить")
                return Result.failure()
            }
            val job = database.job(entry.jobId) ?: continue
            if (job.scanComplete || job.phase != VkJobPhase.SCANNING) continue
            val favorite = favorites.list().firstOrNull { it.key == entry.favoriteKey }
            if (favorite?.sinceDate != entry.sinceDate ||
                favorite.reviewedThroughEpoch != entry.reviewedThroughAtStart) {
                database.phase(job.id, VkJobPhase.ERROR, "Дата сообщества изменилась. Запустите новую проверку")
                continue
            }
            try {
                scan(job, entry, favorite, api, token)
            } catch (error: CancellationException) {
                failPending(batch, "Проверка прервана. Её можно повторить")
                throw error
            } catch (error: VkApiFailure) {
                if (error.kind == VkApiFailure.Kind.BAD_TOKEN)
                    applicationContext.getSharedPreferences("vk_community_ui", Context.MODE_PRIVATE)
                        .edit().putBoolean("key_verified", false).putBoolean("key_error", true).apply()
                database.phase(job.id, VkJobPhase.ERROR, error.userMessage())
                AppLog.w("VkFavoriteCheck", "Community check failed · kind=${error.kind} · ${error.diagnostic()}")
                if (error.kind == VkApiFailure.Kind.BAD_TOKEN) {
                    failPending(batch, "Сервисный ключ VK отклонён. Замените ключ и повторите проверку")
                    return Result.failure()
                }
            } catch (error: Exception) {
                database.phase(job.id, VkJobPhase.ERROR, "Не удалось проверить сообщество")
                AppLog.w("VkFavoriteCheck", "Community check failed · kind=${error.javaClass.simpleName}")
            }
            delay(350)
        }
        return Result.success()
    }

    private fun failPending(batch: VkFavoriteCheckBatch, reason: String) {
        batch.entries.forEach { entry ->
            if (database.job(entry.jobId)?.phase == VkJobPhase.SCANNING)
                database.phase(entry.jobId, VkJobPhase.ERROR, reason)
        }
    }

    private suspend fun scan(job: VkCommunityJob, entry: VkFavoriteCheckEntry,
        favorite: VkFavorite, api: VkCommunityApi, token: String) {
        val link = VkCommunityLink.parse(entry.link)
            ?: throw VkApiFailure(VkApiFailure.Kind.OTHER)
        val zone = ZoneId.of(job.zoneId)
        val period = VkDatePeriod.of(LocalDate.parse(job.startDate), LocalDate.parse(job.endDate))
        val after = VkFavoriteCheckStore.afterEpoch(favorite, zone)
        var cursor = VkScanCursor(job.offset, job.totalPosts, false)
        while (!isStopped) {
            val page = withRateBackoff { api.wallPage(link, cursor.offset, token) }
            applicationContext.getSharedPreferences("vk_community_ui", Context.MODE_PRIVATE)
                .edit().putBoolean("key_verified", true).putBoolean("key_error", false).apply()
            val parsed = VkWallParser.parse(page, period, zone, after, entry.throughEpoch)
            cursor = cursor.advance(parsed)
            database.addPage(job.id, parsed.images, cursor.offset, cursor.total)
            if (cursor.done) {
                database.phase(job.id, VkJobPhase.READY)
                if (database.job(job.id)?.found == 0) {
                    VkCommunityController(applicationContext).completeFavoriteReview(job.id)
                }
                return
            }
            delay(350)
        }
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

    private fun notice(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL,
            "Проверка публикаций VK", NotificationManager.IMPORTANCE_LOW))
        val intent = PendingIntent.getActivity(applicationContext, 0,
            Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Проверка избранных сообществ VK")
            .setContentText("Поиск новых изображений")
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(intent).build()
        return if (Build.VERSION.SDK_INT >= 29)
            ForegroundInfo(5240, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(5240, notification)
    }

    companion object {
        const val KEY_BATCH = "batch_id"
        private const val CHANNEL = "vk-favorite-check"
    }
}
