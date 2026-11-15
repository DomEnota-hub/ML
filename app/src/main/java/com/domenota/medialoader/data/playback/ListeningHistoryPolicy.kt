package com.domenota.medialoader.data.playback

import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import kotlin.math.abs
import kotlin.math.roundToLong

internal enum class ListeningPeriod(val label: String, val days: Long?) {
    WEEK("7 дней",7), MONTH("30 дней",30), ALL("Всё время",null);
    fun cutoff(now: Long): Long = days?.let { (now - it * 86_400_000L).coerceAtLeast(0L) } ?: 0L
}

internal data class ListeningRank(val rowId: String, val trackId: String, val plays: Long, val lastPlayed: Long)
internal data class ListeningExcluded(val trackId: String, val rowId: String?)
internal data class ListeningSelection(val ranked: List<ListeningRank> = emptyList(), val excluded: List<ListeningExcluded> = emptyList())

internal object ListeningHistoryPolicy {
    const val LIMIT = 30
    fun threshold(duration: Long): Long = if (duration > 0) minOf(30_000L,(duration / 2).coerceAtLeast(1L)) else 30_000L
    fun rank(candidates: List<ListeningRank>): List<ListeningRank> = candidates.filter { it.plays >= 2 }
        .sortedWith(compareByDescending<ListeningRank> { it.plays }.thenByDescending { it.lastPlayed }.thenBy { it.rowId })
        .distinctBy { it.trackId }.take(LIMIT)
    fun mayResume(saved: ListeningProgress, position: Long, now: Long): Boolean =
        !saved.closed && position > 0L && now >= saved.lastAt && now - saved.lastAt <= 86_400_000L &&
            abs(position - saved.position) <= 3000L

    /** No title/artist matching. App IDs are durable; phone locators require a metadata
     * fingerprint, or a captured physical stat identity. Ambiguity stays unresolved. */
    fun keys(row: DownloadEntity, aliases: Set<String>): Set<String> {
        if (row.isPrivate || row.hidden || row.mediaType != MediaType.AUDIO ||
            row.state != DownloadState.COMPLETED || row.savedUri.isNullOrBlank()) return emptySet()
        return buildSet {
            if (!row.id.startsWith("phone:") && row.id.isNotBlank()) add("app:${row.id}")
            aliases.filter { it.startsWith("file-stat:") }.forEach(::add)
            val size = row.sizeBytes
            val date = row.createdAtEpochMillis
            if (size != null && size > 0 && date > 0) {
                (aliases + "uri:${row.savedUri}").filter { it.startsWith("media:") || it.startsWith("uri:") || it.startsWith("file:") }
                    .forEach { add("locator:$it:$size:$date") }
            }
        }
    }
}

internal data class ListeningProgress(val sessionId: String, val trackId: String, val startedAt: Long,
    val lastAt: Long, val mediaMs: Long = 0, val wallMs: Long = 0, val creditedAt: Long? = null,
    val position: Long = 0, val closed: Boolean = false, val generation: Long = 0,
    val wallMinutes: Map<Long,Long> = emptyMap())

/** Only continuous media-position progress while actually playing counts. A seek
 * changes the anchor, not the counter. Repeated loops get separate session IDs. */
internal class ListeningCounter(initial: ListeningProgress, position: Long, elapsed: Long,
    playing: Boolean, speed: Float, duration: Long) {
    var progress = initial
        private set
    private var anchorPosition = position
    private var anchorElapsed = elapsed
    private var wasPlaying = playing
    private var previousSpeed = speed
    private var knownDuration = duration
    private val wallMinutes = initial.wallMinutes.toMutableMap()

    fun sample(position: Long, elapsed: Long, now: Long, playing: Boolean, speed: Float, duration: Long = knownDuration) {
        if (duration > 0) knownDuration = duration
        val delta = position - anchorPosition
        val span = elapsed - anchorElapsed
        val rate = previousSpeed.toDouble().takeIf { it.isFinite() && it > 0 } ?: 1.0
        if (wasPlaying && span in 1..10_000L && delta > 0 && delta <= (span * rate).roundToLong() + 250L) {
            val media = progress.mediaMs + delta
            val added = minOf(span,(delta / rate).roundToLong().coerceAtLeast(0L))
            val wall = progress.wallMs + added
            // Monotonic elapsed time determines counted duration. Only use a
            // calendar interval if the wall clock did not jump during the sample.
            val wallSpan=now-progress.lastAt
            if(added>0 && wallSpan>0 && kotlin.math.abs(wallSpan-span)<=1000L) {
                ListeningWallMinutes.split(progress.lastAt,now,added).forEach{(minute,ms)->wallMinutes[minute]=(wallMinutes[minute]?:0L)+ms}
            }
            progress = progress.copy(mediaMs=media,wallMs=wall,wallMinutes=wallMinutes.toMap(),
                creditedAt=progress.creditedAt ?: now.takeIf { media >= ListeningHistoryPolicy.threshold(knownDuration) })
        }
        progress = progress.copy(lastAt=maxOf(progress.lastAt,now),position=position.coerceAtLeast(0))
        reset(position,elapsed,playing,speed)
    }

    fun reset(position: Long, elapsed: Long, playing: Boolean, speed: Float) {
        anchorPosition = position; anchorElapsed = elapsed; wasPlaying = playing; previousSpeed = speed
    }

    fun close() { progress = progress.copy(closed=true) }
}

/** UTC-minute buckets preserve measured wall duration across midnight and DST.
 * The cumulative integer allocation conserves every millisecond; it does not
 * interpret seek jumps or pauses as listened time. */
internal object ListeningWallMinutes {
    const val MINUTE=60_000L
    fun split(start:Long,end:Long,wall:Long):Map<Long,Long>{
        if(start<0||end<=start||wall<=0||end-start>11_000L)return emptyMap()
        val span=end-start
        var at=start;var allocated=0L
        return buildMap{
            while(at<end){
                val minute=at-at%MINUTE
                val until=minOf(end,minute+MINUTE)
                val cumulative=wall*(until-start)/span
                val portion=cumulative-allocated
                if(portion>0)put(minute,portion)
                allocated=cumulative;at=until
            }
        }
    }
}
