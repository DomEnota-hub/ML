package com.domenota.medialoader.data.playback

import com.domenota.medialoader.data.library.PersistentProjectionCache
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

internal enum class ListeningProjectionDomain(val id: Int) { STATS(1), RANK(2), RESUME(3) }

internal data class CachedListeningProjection<T>(val value: T, val revision: Long, val currentRevision: Long, val computedAt: Long) {
    fun reusable(now: Long, maxAge: Long): Boolean = revision == currentRevision && now >= computedAt && now - computedAt <= maxAge
}

/** Explicit, versioned public-result codec. No raw media, names of hidden rows,
 * paths or source URLs are persisted by this projection. */
internal object ListeningProjectionCodec {
    private fun <T> array(values: List<T>, encode: (T) -> JSONObject) = JSONArray().also { a -> values.forEach { a.put(encode(it)) } }
    private fun <T> JSONObject.list(key: String, decode: (JSONObject) -> T): List<T> = getJSONArray(key).let { a ->
        (0 until a.length()).map { decode(a.getJSONObject(it)) }
    }
    private fun JSONObject.string(key: String): String? = if (isNull(key)) null else getString(key)
    private fun JSONObject.number(key: String): Long? = if (isNull(key)) null else getLong(key)
    private fun track(row: SmartTrackHistory) = JSONObject().put("row", row.rowId).put("track", row.trackId)
        .put("artist", row.artist).put("favorite", row.favorite).put("plays", row.plays).put("last", row.lastPlayed)
        .put("periodPlays", row.periodPlays).put("wall", row.periodWallMs).put("excluded", row.excluded)
    private fun track(value: JSONObject) = SmartTrackHistory(value.getString("row"), value.string("track"), value.string("artist"),
        value.getBoolean("favorite"), value.getLong("plays"), value.getLong("last"), value.getLong("periodPlays"), value.getLong("wall"), value.getBoolean("excluded"))
    private fun interval(value: SmartStatsInterval) = JSONObject().put("first", value.first.toString()).put("end", value.end.toString())
        .put("granularity", value.granularity.name).put("title", value.title)
    private fun interval(value: JSONObject) = SmartStatsInterval(LocalDate.parse(value.getString("first")), LocalDate.parse(value.getString("end")),
        StatsGranularity.valueOf(value.getString("granularity")), value.getString("title"))

    fun encode(value: SmartMusicSnapshot): JSONObject = JSONObject().put("schema", 1)
        .put("forgotten", array(value.forgotten, ::track)).put("mix", array(value.mix, ::track))
        .put("top", array(value.topTracks, ::track)).put("played", array(value.playedTracks, ::track))
        .put("artists", array(value.topArtists) { JSONObject().put("artist", it.artist).put("plays", it.plays).put("wall", it.wallMs) })
        .put("plays", value.plays).put("wall", value.wallMs).put("tracksPlayed", value.tracksPlayed).put("artistsPlayed", value.artistsPlayed)
        .put("chart", array(value.chart) { JSONObject().put("label", it.label).put("plays", it.plays).put("wall", it.wallMs).put("date", it.date?.toString()) })
        .put("interval", value.statsInterval?.let(::interval)).put("period", value.statsPeriod?.name)
        .put("sessions", array(value.daySessions) { JSONObject().put("session", it.sessionId).put("row", it.rowId).put("start", it.startedAt)
            .put("last", it.lastAt).put("credit", it.creditedAt).put("wall", it.wallMs).put("legacy", it.legacyAttribution).put("minute", it.firstMinute) })

    fun smart(value: JSONObject): SmartMusicSnapshot {
        require(value.getInt("schema") == 1)
        return SmartMusicSnapshot(forgotten = value.list("forgotten", ::track), mix = value.list("mix", ::track),
            topTracks = value.list("top", ::track), topArtists = value.list("artists") { SmartArtistRank(it.getString("artist"), it.getLong("plays"), it.getLong("wall")) },
            plays = value.getLong("plays"), wallMs = value.getLong("wall"), tracksPlayed = value.getInt("tracksPlayed"), artistsPlayed = value.getInt("artistsPlayed"),
            chart = value.list("chart") { SmartChartPoint(it.getString("label"), it.getLong("plays"), it.getLong("wall"), it.string("date")?.let(LocalDate::parse)) },
            statsInterval = value.optJSONObject("interval")?.let(::interval), statsPeriod = value.string("period")?.let(SmartStatsPeriod::valueOf),
            playedTracks = value.list("played", ::track), daySessions = value.list("sessions") { SmartDaySession(it.getString("session"), it.getString("row"),
                it.getLong("start"), it.getLong("last"), it.number("credit"), it.getLong("wall"), it.getBoolean("legacy"), it.number("minute")) })
    }

    fun encode(value: ListeningSelection): JSONObject = JSONObject().put("schema", 2).put("validUntil",value.validUntil)
        .put("ranked", array(value.ranked) { JSONObject().put("row", it.rowId).put("track", it.trackId).put("plays", it.plays).put("last", it.lastPlayed) })
        .put("excluded", array(value.excluded) { JSONObject().put("track", it.trackId).put("row", it.rowId) })
    fun selection(value: JSONObject): ListeningSelection {
        require(value.getInt("schema") == 2)
        return ListeningSelection(value.list("ranked") { ListeningRank(it.getString("row"), it.getString("track"), it.getLong("plays"), it.getLong("last")) },
            value.list("excluded") { ListeningExcluded(it.getString("track"), it.string("row")) },value.getLong("validUntil"))
    }
    private fun candidate(row: SmartTrackCandidate) = JSONObject().put("row", row.rowId).put("keys", JSONArray(row.keys.sorted()))
        .put("artist", row.artist).put("favorite", row.favorite)
    fun candidateDigest(candidates: List<SmartTrackCandidate>): String = PersistentProjectionCache.digest(
        array(candidates.sortedBy { it.rowId }, ::candidate).toString())
    fun rankDigest(keys: Map<String,Set<String>>): String = PersistentProjectionCache.digest(array(keys.entries.sortedBy { it.key }) {
        JSONObject().put("row", it.key).put("keys", JSONArray(it.value.sorted()))
    }.toString())
    fun smartKey(candidates: List<SmartTrackCandidate>, period: SmartStatsPeriod, day: Long, seed: Long, diversity: Int,
        forgottenDays: Long, selected: SmartStatsInterval?, zone: ZoneId): String = smartKey(candidateDigest(candidates),period,day,seed,diversity,forgottenDays,selected,zone)
    fun smartKey(digest: String, period: SmartStatsPeriod, day: Long, seed: Long, diversity: Int,
        forgottenDays: Long, selected: SmartStatsInterval?, zone: ZoneId): String = PersistentProjectionCache.digest(
        JSONObject().put("kind", "smart-v2").put("period", period.name).put("day", day).put("zone", zone.id).put("seed", seed)
            .put("diversity", diversity).put("forgottenDays", forgottenDays).put("interval", selected?.let(::interval))
            .put("candidates", digest).toString())
    fun selectionKey(keys: Map<String, Set<String>>, period: ListeningPeriod): String = selectionKey(rankDigest(keys),period)
    fun selectionKey(digest: String, period: ListeningPeriod): String = PersistentProjectionCache.digest(
        JSONObject().put("kind", "selection-v2").put("period", period.name).put("rows", digest).toString())
    fun resumeKey(sessions: List<ResumeQueueSession>, candidates: List<SmartTrackCandidate>): String = resumeKey(sessions,candidateDigest(candidates))
    fun resumeKey(sessions: List<ResumeQueueSession>, digest: String): String = PersistentProjectionCache.digest(
        JSONObject().put("kind", "resume-v3").put("candidates", digest)
            .put("queues", array(sessions.sortedBy{it.id}) { session -> JSONObject().put("id",session.id)
                .put("entries",JSONArray().also { a -> session.entries.forEach { a.put(JSONArray(it.sorted())) } })
                .put("occurrences",JSONArray(session.occurrenceIds)) }).toString())
    fun encodeResume(value: List<ResolvedResumeQueue>): JSONObject = JSONObject().put("schema",1)
        .put("queues",array(value) { row -> JSONObject().put("id",row.session.id)
            .put("ids",JSONArray().also { a -> row.ids.forEach { a.put(it ?: JSONObject.NULL) } }) })
    fun resume(value: JSONObject, sessions: List<ResumeQueueSession>): List<ResolvedResumeQueue> {
        require(value.getInt("schema")==1)
        val rows=value.list("queues") { row -> row.getString("id") to row.getJSONArray("ids").let { a ->
            (0 until a.length()).map { if(a.isNull(it))null else a.getString(it) }
        } }.toMap()
        require(rows.keys==sessions.mapTo(mutableSetOf()){it.id})
        return sessions.map { session -> ResolvedResumeQueue(session,rows.getValue(session.id).also { require(it.size==session.entries.size) }) }
    }

}
