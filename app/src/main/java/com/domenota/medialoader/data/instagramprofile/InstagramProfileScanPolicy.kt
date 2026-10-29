package com.domenota.medialoader.data.instagramprofile

import com.domenota.medialoader.core.model.MediaItem
import java.time.LocalDate
import java.time.ZoneId

data class InstagramProfilePageDecision(val posts: List<InstagramProfilePost>, val reachedStart: Boolean)

object InstagramProfileScanPolicy {
    const val DEFAULT_BATCH_SIZE = 300

    fun filter(posts: List<InstagramProfilePost>, start: LocalDate?, end: LocalDate?,
        zone: ZoneId): InstagramProfilePageDecision {
        val lower = start?.atStartOfDay(zone)?.toEpochSecond()
        val upper = end?.plusDays(1)?.atStartOfDay(zone)?.toEpochSecond()
        return InstagramProfilePageDecision(
            posts.filter { post -> (lower == null || post.epochSecond >= lower) &&
                (upper == null || post.epochSecond < upper) },
            lower != null && posts.any { it.epochSecond > 0 && it.epochSecond < lower },
        )
    }

    fun reachedBatchLimit(scanned: Int, limit: Int): Boolean = scanned >= limit

    fun selectedVisuals(visuals: List<MediaItem>, excludedIds: Set<String>): List<MediaItem> =
        visuals.filter { it.id !in excludedIds }

    fun merge(existing: List<InstagramProfilePost>, incoming: List<InstagramProfilePost>): List<InstagramProfilePost> =
        (existing + incoming).groupBy { it.code }.values.map { duplicates ->
            duplicates.reduce { old, new -> old.copy(
                video = old.video || new.video,
                reel = old.reel || new.reel,
                previewUrl = old.previewUrl ?: new.previewUrl,
                mediaCount = maxOf(old.mediaCount, new.mediaCount),
            ) }
        }
}
