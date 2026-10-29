package com.domenota.medialoader.data.instagramprofile

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class InstagramProfileController(context: Context) {
    val store = InstagramProfileStore(context)
    val favorites = InstagramFavoriteStore(context)
    private val work = WorkManager.getInstance(context.applicationContext)

    fun search(input: String, start: LocalDate?, end: LocalDate?, includeReels: Boolean,
        favoriteCheck: Boolean = false, scheduleImmediately: Boolean = true): String? {
        val profile = InstagramProfileLink.parse(input) ?: return null
        val id = store.create(profile.username, start, end, includeReels, favoriteCheck)
        if (scheduleImmediately) schedule(id, InstagramProfileWorker.MODE_SCAN)
        return id
    }

    fun continueSearch(id: String) {
        val job = store.job(id) ?: return
        if (job.scanComplete) return
        store.continueBatch(id)
        schedule(id, InstagramProfileWorker.MODE_SCAN)
    }

    fun download(id: String) {
        if (store.job(id) == null) return
        store.phase(id, InstagramProfilePhase.DOWNLOADING)
        schedule(id, InstagramProfileWorker.MODE_DOWNLOAD)
    }

    fun cancel(id: String) {
        store.phase(id, InstagramProfilePhase.CANCELLED)
        work.cancelUniqueWork("ig-profile-$id")
    }

    fun repeat(job: InstagramProfileJob): String? = search(job.username,
        job.periodStart, job.periodEnd, job.includeReels, job.favoriteCheck)

    fun checkFavorites(): List<String> {
        val pending = store.jobs().filter { it.favoriteCheck && it.phase == InstagramProfilePhase.SCANNING }
        if (pending.isNotEmpty()) {
            return pending.map { it.id }
        }
        if (store.jobs().any { it.favoriteCheck && it.scanComplete && !it.reviewed })
            return emptyList()
        val today = LocalDate.now()
        val jobs = favorites.list().mapNotNull { favorite ->
            val initial = LocalDate.parse(favorite.sinceDate)
            val reviewed = favorite.reviewedThrough.takeIf { it > 0 }?.let {
                Instant.ofEpochSecond(it).atZone(ZoneId.systemDefault()).toLocalDate().minusDays(1)
            }
            search(favorite.username, maxOf(initial, reviewed ?: initial), today,
                favorite.includeReels, favoriteCheck = true, scheduleImmediately = false)
        }
        jobs.firstOrNull()?.let { schedule(it, InstagramProfileWorker.MODE_SCAN) }
        return jobs
    }

    fun scheduleNextFavoriteCheck(finishedId: String) {
        store.jobs().lastOrNull { it.favoriteCheck && it.id != finishedId &&
            it.phase == InstagramProfilePhase.SCANNING }?.let {
            schedule(it.id, InstagramProfileWorker.MODE_SCAN)
        }
    }

    fun finishReview(id: String): Boolean {
        val job = store.job(id) ?: return false
        if (!job.favoriteCheck || !job.scanComplete) return false
        store.markReviewed(id)
        favorites.markReviewed(job.username, job.createdAt / 1000)
        return true
    }

    private fun schedule(id: String, mode: String) {
        val request = OneTimeWorkRequestBuilder<InstagramProfileWorker>()
            .setInputData(Data.Builder().putString(InstagramProfileWorker.KEY_JOB, id)
                .putString(InstagramProfileWorker.KEY_MODE, mode).build())
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        work.enqueueUniqueWork("ig-profile-$id", ExistingWorkPolicy.REPLACE, request)
    }
}
