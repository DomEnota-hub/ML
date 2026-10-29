package com.domenota.medialoader.data.vkcommunity

import android.content.Context
import java.time.LocalDate
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject

data class VkFavoriteCheckEntry(
    val favoriteKey: String,
    val label: String,
    val link: String,
    val jobId: String,
    val sinceDate: String,
    val reviewedThroughAtStart: Long?,
    val throughEpoch: Long,
    val reviewed: Boolean = false,
)

data class VkFavoriteCheckBatch(val id: String, val entries: List<VkFavoriteCheckEntry>)

/** Persists the latest check so the summary survives leaving the screen and process restart. */
class VkFavoriteCheckStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("vk_favorite_checks", 0)

    fun current(): VkFavoriteCheckBatch? = decode(prefs.getString("batch", "").orEmpty())

    @Synchronized fun save(batch: VkFavoriteCheckBatch): Boolean =
        prefs.edit().putString("batch", encode(batch)).commit()

    @Synchronized fun reviewed(jobId: String): VkFavoriteCheckBatch? {
        val batch = current() ?: return null
        val updated = batch.copy(entries = batch.entries.map {
            if (it.jobId == jobId) it.copy(reviewed = true) else it
        })
        return updated.takeIf { save(it) }
    }

    companion object {
        fun afterEpoch(favorite: VkFavorite, zone: ZoneId): Long =
            favorite.reviewedThroughEpoch ?: LocalDate.parse(favorite.sinceDate)
                .atStartOfDay(zone).toEpochSecond() - 1

        fun canReview(entry: VkFavoriteCheckEntry, favorite: VkFavorite?, scanComplete: Boolean): Boolean =
            favorite != null && entry.favoriteKey == favorite.key &&
                entry.sinceDate == favorite.sinceDate &&
                entry.reviewedThroughAtStart == favorite.reviewedThroughEpoch &&
                scanComplete

        fun encode(batch: VkFavoriteCheckBatch): String = JSONObject().apply {
            put("id", batch.id)
            put("entries", JSONArray().apply { batch.entries.forEach { entry ->
                put(JSONObject().put("favorite_key", entry.favoriteKey).put("label", entry.label)
                    .put("link", entry.link).put("job_id", entry.jobId)
                    .put("since_date", entry.sinceDate)
                    .put("reviewed_through_at_start", entry.reviewedThroughAtStart)
                    .put("through_epoch", entry.throughEpoch).put("reviewed", entry.reviewed))
            } })
        }.toString()

        fun decode(raw: String): VkFavoriteCheckBatch? = runCatching {
            val obj = JSONObject(raw)
            val items = obj.getJSONArray("entries")
            VkFavoriteCheckBatch(obj.getString("id"), buildList {
                for (index in 0 until items.length()) {
                    val entry = items.getJSONObject(index)
                    add(VkFavoriteCheckEntry(entry.getString("favorite_key"),
                        entry.getString("label"), entry.getString("link"),
                        entry.getString("job_id"), entry.getString("since_date"),
                        entry.optLong("reviewed_through_at_start").takeIf { it > 0 },
                        entry.getLong("through_epoch"), entry.optBoolean("reviewed")))
                }
            })
        }.getOrNull()
    }
}
