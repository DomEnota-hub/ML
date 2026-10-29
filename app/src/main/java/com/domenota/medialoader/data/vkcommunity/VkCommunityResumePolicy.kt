package com.domenota.medialoader.data.vkcommunity

import android.content.Context
import com.domenota.medialoader.data.storage.SavedFilePresence
import com.domenota.medialoader.data.storage.SavedFileStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object VkCommunityResumePolicy {
    fun shouldRequeue(item: VkStoredImage, status: SavedFileStatus): Boolean =
        item.selected && item.phase in setOf(VkItemPhase.DONE, VkItemPhase.DUPLICATE) &&
            status == SavedFileStatus.MISSING
}

/** Rechecks actual files, in bounded batches, before a VK job resumes. */
class VkCommunityRepair(private val context: Context, private val db: VkCommunityDatabase) {
    suspend fun requeueMissingSelected(jobId: String): Int = withContext(Dispatchers.IO) {
        var cursor = ""
        var restored = 0
        while (true) {
            val batch = db.completedSelected(jobId, cursor)
            if (batch.isEmpty()) break
            for (item in batch) {
                val status = SavedFilePresence.check(context, item.savedUri)
                if (VkCommunityResumePolicy.shouldRequeue(item, status) &&
                    db.requeueMissing(jobId, item.candidate.photoKey)) restored++
            }
            cursor = batch.last().candidate.photoKey
        }
        restored
    }
}
