package com.domenota.medialoader.data.vkcommunity

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Screen commands; all durable progress remains in VkCommunityDatabase. */
class VkCommunityController(context: Context) {
    private val appContext = context.applicationContext
    val database = VkCommunityDatabase(context)
    val tokens = VkTokenStore(context)
    val favorites = VkFavoriteStore(context)
    val favoriteChecks = VkFavoriteCheckStore(context)
    private val work = WorkManager.getInstance(context.applicationContext)

    fun search(linkInput: String, period: VkDatePeriod,
        zoneId: String = ZoneId.systemDefault().id): String? {
        val link = VkCommunityLink.parse(linkInput) ?: return null
        if (tokens.read() == null) return null
        val id = database.createJob(linkInput.trim(), period, zoneId)
        schedule(id, VkCommunityWorker.MODE_SCAN)
        return id
    }

    fun continueSearch(jobId: String) {
        if (database.job(jobId)?.scanComplete != false) return
        database.phase(jobId, VkJobPhase.SCANNING)
        schedule(jobId, VkCommunityWorker.MODE_SCAN)
    }

    fun download(jobId: String, retryFailures: Boolean = false) {
        if (database.job(jobId)?.scanComplete != true) return
        if (retryFailures) database.retryFailed(jobId)
        database.phase(jobId, VkJobPhase.DOWNLOADING)
        schedule(jobId, VkCommunityWorker.MODE_DOWNLOAD)
    }

    suspend fun reconcileMissingSelected(jobId: String): Int =
        VkCommunityRepair(appContext, database).requeueMissingSelected(jobId)

    fun cancel(jobId: String) {
        database.phase(jobId, VkJobPhase.CANCELLED)
        work.cancelUniqueWork(uniqueName(jobId))
    }

    fun repeat(job: VkCommunityJob): String? =
        search(job.link, VkHistoryPolicy.repeatPeriod(job), job.zoneId)

    fun deleteHistoryJob(id: String): Boolean = database.deleteJob(id)

    fun clearHistory(): Int = database.clearHistory()

    fun checkFavoritePublications(): VkFavoriteCheckBatch? {
        if (tokens.read() == null) return null
        val saved = favorites.list()
        if (saved.isEmpty()) return null
        val previous = favoriteChecks.current()
        if (previous?.entries?.any { entry ->
                database.job(entry.jobId)?.phase == VkJobPhase.SCANNING
            } == true) return previous
        val today = LocalDate.now()
        val zone = ZoneId.systemDefault()
        val through = System.currentTimeMillis() / 1000 - 1
        val entries = saved.map { favorite ->
            val started = LocalDate.parse(favorite.sinceDate)
            val jobId = database.createJob(favorite.link, VkDatePeriod.of(started, today), zone.id)
            VkFavoriteCheckEntry(favorite.key, favorite.label, favorite.link, jobId,
                favorite.sinceDate, favorite.reviewedThroughEpoch, through)
        }
        val batch = VkFavoriteCheckBatch(UUID.randomUUID().toString(), entries)
        if (!favoriteChecks.save(batch)) return null
        scheduleFavoriteCheck(batch.id)
        return batch
    }

    fun retryFavoriteCheck(): Boolean {
        val batch = favoriteChecks.current() ?: return false
        val failed = batch.entries.filter { !it.reviewed &&
            database.job(it.jobId)?.let { job -> !job.scanComplete &&
                job.phase in setOf(VkJobPhase.ERROR, VkJobPhase.CANCELLED) } == true }
        if (failed.isEmpty()) return false
        failed.forEach { database.phase(it.jobId, VkJobPhase.SCANNING) }
        scheduleFavoriteCheck(batch.id)
        return true
    }

    fun completeFavoriteReview(jobId: String): Boolean {
        val entry = favoriteChecks.current()?.entries?.firstOrNull { it.jobId == jobId }
            ?: return false
        if (entry.reviewed) return true
        val job = database.job(jobId) ?: return false
        val favorite = favorites.list().firstOrNull { it.key == entry.favoriteKey }
        if (!VkFavoriteCheckStore.canReview(entry, favorite, job.scanComplete))
            return false
        if (!favorites.markReviewed(entry.favoriteKey, entry.throughEpoch)) return false
        return favoriteChecks.reviewed(jobId) != null
    }

    private fun scheduleFavoriteCheck(batchId: String) {
        val request = OneTimeWorkRequestBuilder<VkFavoriteCheckWorker>()
            .setInputData(Data.Builder().putString(VkFavoriteCheckWorker.KEY_BATCH, batchId).build())
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        work.enqueueUniqueWork("vk-favorite-check", ExistingWorkPolicy.REPLACE, request)
    }

    private fun schedule(jobId: String, mode: String) {
        val request = OneTimeWorkRequestBuilder<VkCommunityWorker>()
            .setInputData(Data.Builder()
                .putString(VkCommunityWorker.KEY_JOB, jobId)
                .putString(VkCommunityWorker.KEY_MODE, mode).build())
            .setConstraints(Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        work.enqueueUniqueWork(uniqueName(jobId), ExistingWorkPolicy.REPLACE, request)
    }

    private fun uniqueName(jobId: String) = "vk-community-$jobId"
}
