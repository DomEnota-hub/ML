package com.domenota.medialoader.data.playback

import java.util.Locale
import kotlin.random.Random
import kotlin.math.roundToInt
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** All smart views use the exact same physical identities as the listening recorder. */
internal data class SmartTrackCandidate(val rowId: String, val keys: Set<String>, val artist: String?, val favorite: Boolean)
internal data class SmartTrackHistory(val rowId: String, val trackId: String?, val artist: String?, val favorite: Boolean,
    val plays: Long = 0, val lastPlayed: Long = 0, val periodPlays: Long = 0, val periodWallMs: Long = 0,
    val excluded: Boolean = false)
internal data class SmartArtistRank(val artist: String, val plays: Long, val wallMs: Long)
internal data class SmartChartPoint(val label: String,val plays: Long,val wallMs: Long,val date: LocalDate? = null)
internal enum class SmartStatsPeriod(val label: String) {
    WEEK("Неделя"), MONTH("Месяц"), YEAR("Год"), ALL("Всё время");
    fun date(now: Long,zone: ZoneId) = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    fun first(now: Long,zone: ZoneId): LocalDate = when(this) {
        WEEK -> date(now,zone).minusDays(6)
        MONTH -> date(now,zone).minusDays(29)
        YEAR -> date(now,zone).withDayOfMonth(1).minusMonths(11)
        ALL -> LocalDate.of(1970,1,1)
    }
    fun cutoff(now: Long,zone: ZoneId): Long = if(this==ALL) 0L else first(now,zone).atStartOfDay(zone).toInstant().toEpochMilli()
    fun bucket(at: Long,zone: ZoneId): LocalDate = date(at,zone).let { when(this){ YEAR->it.withDayOfMonth(1); ALL->it.withDayOfYear(1); else->it } }
    fun buckets(now: Long,zone: ZoneId): List<LocalDate> =
        (0 until when(this){WEEK->7; MONTH->30; YEAR->12; ALL->0}).map {
            if(this==YEAR) first(now,zone).plusMonths(it.toLong()) else first(now,zone).plusDays(it.toLong())
        }
    fun label(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern(when(this){YEAR->"MM.yyyy"; ALL->"yyyy"; else->"dd.MM"},Locale.ROOT))
}
internal data class SmartMusicSnapshot(val forgotten: List<SmartTrackHistory> = emptyList(),
    val mix: List<SmartTrackHistory> = emptyList(), val topTracks: List<SmartTrackHistory> = emptyList(),
    val topArtists: List<SmartArtistRank> = emptyList(), val plays: Long = 0, val wallMs: Long = 0,
    val tracksPlayed: Int = 0, val artistsPlayed: Int = 0, val chart: List<SmartChartPoint> = emptyList(),
    val statsInterval:SmartStatsInterval?=null,val statsPeriod:SmartStatsPeriod?=null)

internal object SmartMusicPolicy {
    const val LIMIT = 30
    const val DAY_MS = 86_400_000L
    const val FORGOTTEN_DAYS = 30L
    fun artistKey(artist: String?): String? = artist?.trim()?.replace(Regex("\\s+")," ")
        ?.takeIf { it.isNotEmpty() }?.lowercase(Locale.ROOT)

    /** Don't count two catalog rows for one file or guess ownership of an ambiguous alias. */
    fun resolve(candidates: List<SmartTrackCandidate>, aliases: Map<String,String>): List<Pair<SmartTrackCandidate,String?>> {
        val uniqueRows = candidates.filter { it.rowId.isNotBlank() && it.keys.isNotEmpty() }
            .groupBy { it.rowId }.values.mapNotNull { it.singleOrNull() }
        val keyCounts = uniqueRows.flatMap { it.keys }.groupingBy { it }.eachCount()
        val mapped = uniqueRows.mapNotNull { row ->
            val tracks = row.keys.mapNotNull(aliases::get).distinct()
            if (tracks.size > 1 || row.keys.any { (keyCounts[it] ?: 0) > 1 }) null
            else row to tracks.singleOrNull()
        }
        val trackCounts = mapped.mapNotNull { it.second }.groupingBy { it }.eachCount()
        return mapped.filter { (_,track) -> track == null || trackCounts[track] == 1 }
    }

    fun forgotten(rows: List<SmartTrackHistory>, now: Long, days: Long = FORGOTTEN_DAYS): List<SmartTrackHistory> {
        require(days in 30..60)
        val cutoff = now - days * DAY_MS
        return rows.filter { !it.excluded && it.plays > 0 && it.lastPlayed in 1..cutoff }
            .sortedWith(compareByDescending<SmartTrackHistory> { it.plays }
                .thenBy { it.lastPlayed }.thenBy { it.rowId }).take(LIMIT)
    }

    /** Slider 0 = mostly familiar (80/10/10%), 100 = mostly discovery
     * (20/40/40%). Empty buckets are refilled, never duplicate a file. */
    fun quotas(diversity: Int): IntArray {
        require(diversity in 0..100 && diversity % 5 == 0)
        val familiar = (24 - 18 * diversity / 100.0).roundToInt()
        val other = (30 - familiar + 1) / 2
        return intArrayOf(familiar,other,LIMIT-familiar-other)
    }
    fun mix(rows: List<SmartTrackHistory>, now: Long, seed: Long = now / DAY_MS, diversity: Int = 50): List<SmartTrackHistory> {
        val all = rows.filterNot { it.excluded }.distinctBy { it.rowId }
        val notRecent = all.filter { it.lastPlayed <= now - 2*DAY_MS }
        val eligible = if(notRecent.size >= LIMIT) notRecent else all
        val oldIds = eligible.filter { it.plays > 0 && it.lastPlayed in 1..(now - FORGOTTEN_DAYS*DAY_MS) }.mapTo(hashSetOf()) { it.rowId }
        val old = eligible.filter { it.rowId in oldIds }
        val favorite = eligible.filter { it.rowId !in oldIds && (it.favorite || it.plays >= 2) }
        val other = eligible.filter { it.rowId !in oldIds && !it.favorite && it.plays < 2 }
        val random = Random(seed)
        fun ordered(group: List<SmartTrackHistory>) = group.sortedBy { it.rowId }.shuffled(random)
            .sortedBy { if (it.lastPlayed > now - 2 * DAY_MS) 1 else 0 }.toMutableList()
        val buckets = listOf(ordered(favorite),ordered(other),ordered(old))
        val quotas = quotas(diversity)
        val chosen = mutableListOf<SmartTrackHistory>()
        val picked = mutableSetOf<String>()
        fun take(bucket: Int): Boolean {
            val queue = buckets[bucket]
            if (queue.isEmpty()) return false
            val prev = artistKey(chosen.lastOrNull()?.artist)
            val index = queue.indexOfFirst { prev == null || artistKey(it.artist) != prev }.let { if (it < 0) 0 else it }
            val row = queue.removeAt(index)
            if (picked.add(row.rowId)) chosen += row
            return true
        }
        while (chosen.size < LIMIT && quotas.any { it > 0 }) {
            var progress = false
            for (i in buckets.indices) {
                if (chosen.size >= LIMIT) break
                if (quotas[i] > 0 && take(i)) { quotas[i]--; progress = true }
                else if (buckets[i].isEmpty()) quotas[i] = 0
            }
            if (!progress) break
        }
        while (chosen.size < LIMIT) {
            val prev = artistKey(chosen.lastOrNull()?.artist)
            val next = buckets.indices.firstOrNull { i -> buckets[i].any { prev == null || artistKey(it.artist) != prev } }
                ?: buckets.indices.firstOrNull { buckets[it].isNotEmpty() } ?: break
            take(next)
        }
        // Stable reordering avoids artist repetition across bucket boundaries too.
        val remaining = chosen.toMutableList()
        val result = mutableListOf<SmartTrackHistory>()
        while (remaining.isNotEmpty()) {
            val prev = artistKey(result.lastOrNull()?.artist)
            val index = remaining.indexOfFirst { prev == null || artistKey(it.artist) != prev }.let { if (it < 0) 0 else it }
            result += remaining.removeAt(index)
        }
        return result
    }

    fun snapshot(rows: List<SmartTrackHistory>, now: Long, seed: Long = now / DAY_MS, diversity: Int = 50, forgottenDays: Long = FORGOTTEN_DAYS): SmartMusicSnapshot {
        val played = rows.filter { it.periodPlays > 0 || it.periodWallMs > 0 }
        val artists = rows.filter { it.periodPlays > 0 || it.periodWallMs > 0 }
            .mapNotNull { row -> artistKey(row.artist)?.let { it to row } }.groupBy({it.first},{it.second})
        val artistRanks = artists.values.map { group ->
            SmartArtistRank(group.first().artist!!.trim(), group.sumOf { it.periodPlays }, group.sumOf { it.periodWallMs })
        }.sortedWith(compareByDescending<SmartArtistRank> { it.plays }.thenByDescending { it.wallMs }.thenBy { artistKey(it.artist) })
        return SmartMusicSnapshot(forgotten(rows,now,forgottenDays),mix(rows,now,seed,diversity),
            played.sortedWith(compareByDescending<SmartTrackHistory> { it.periodPlays }.thenByDescending { it.lastPlayed }.thenBy { it.rowId }).take(LIMIT),
            artistRanks.take(LIMIT),rows.sumOf { it.periodPlays },rows.sumOf { it.periodWallMs },played.size,
            artists.size)
    }
}
