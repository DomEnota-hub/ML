package com.domenota.medialoader.data.playback

import org.junit.Assert.*
import org.junit.Test

class SmartMusicPolicyTest {
    private val now=100L*SmartMusicPolicy.DAY_MS
    private fun row(id:String,plays:Long,lastDays:Long,artist:String="Artist",favorite:Boolean=false,excluded:Boolean=false) =
        SmartTrackHistory(id,id,artist,favorite,plays,now-lastDays*SmartMusicPolicy.DAY_MS,plays,plays*30_000,excluded)
    @Test fun forgottenRequiresPriorListeningAndPrefersFormerFavorites() {
        val rows=listOf(row("old",5,35),row("older",2,60),row("new",9,2),row("unheard",0,80),row("excluded",20,60,excluded=true))
        assertEquals(listOf("old","older"),SmartMusicPolicy.forgotten(rows,now).map { it.rowId })
        assertEquals(listOf("boundary"),SmartMusicPolicy.forgotten(listOf(row("boundary",1,30)),now).map { it.rowId })
    }
    @Test fun mixIsBoundedDeterministicAndDoesNotRepeatOrIncludeExcluded() {
        val rows=(1..70).map { i -> row("row$i",if(i<=20) 8 else if(i<=40) 2 else 0,if(i in 21..40) 50 else 5,"Artist ${i%4}",excluded=i==5) }
        val mix=SmartMusicPolicy.mix(rows,now,12)
        assertEquals(30,mix.size); assertEquals(30,mix.map {it.rowId}.distinct().size)
        assertEquals(mix,SmartMusicPolicy.mix(rows.reversed(),now,12))
        assertFalse(mix.any { it.excluded })
        assertTrue(mix.zipWithNext().all { (a,b) -> a.artist!=b.artist })
        assertTrue(mix.any {it.plays==0L}); assertTrue(mix.any {it.lastPlayed < now-30*SmartMusicPolicy.DAY_MS})
    }
    @Test fun sliderControlsBalanceAndKeepsTotalThirty() {
        assertArrayEquals(intArrayOf(24,3,3),SmartMusicPolicy.quotas(0))
        assertArrayEquals(intArrayOf(15,8,7),SmartMusicPolicy.quotas(50))
        assertArrayEquals(intArrayOf(6,12,12),SmartMusicPolicy.quotas(100))
        for(value in 0..100 step 5) assertEquals(30,SmartMusicPolicy.quotas(value).sum())
        val rows=(1..90).map { i -> row("r$i",if(i<=30) 5 else if(i<=60) 0 else 3,if(i>60) 50 else 5,"Artist ${i%4}") }
        assertEquals(24,SmartMusicPolicy.mix(rows,now,10,0).count { it.plays==5L })
        assertEquals(6,SmartMusicPolicy.mix(rows,now,10,100).count { it.plays==5L })
    }
    @Test fun calendarBucketsCoverWeekMonthAndYear() {
        val zone=java.time.ZoneId.of("Europe/Moscow")
        val now=java.time.Instant.parse("2026-11-14T00:00:00Z").toEpochMilli()
        assertEquals(7,SmartStatsPeriod.WEEK.buckets(now,zone).size)
        assertEquals(30,SmartStatsPeriod.MONTH.buckets(now,zone).size)
        assertEquals(java.time.LocalDate.of(2025,12,1),SmartStatsPeriod.YEAR.buckets(now,zone).first())
        assertEquals(java.time.LocalDate.of(2026,11,1),SmartStatsPeriod.YEAR.buckets(now,zone).last())
    }
    @Test fun mixAvoidsVeryRecentTracksWhenEnoughOthersExist() {
        val rows=(1..40).map { row("old$it",2,5,"Artist ${it%3}") } +
            (1..20).map { row("recent$it",9,1,"Artist ${it%3}") }
        assertTrue(SmartMusicPolicy.mix(rows,now).none { it.rowId.startsWith("recent") })
    }
    @Test fun mixWithOneArtistAndSmallLibraryStillUsesAllAvailableTracks() {
        val rows=(1..4).map { row("$it",2,1) }
        assertEquals(rows.map { it.rowId }.toSet(),SmartMusicPolicy.mix(rows,now).map { it.rowId }.toSet())
    }
    @Test fun duplicateAndAmbiguousAliasesFailClosed() {
        fun c(id:String,vararg keys:String)=SmartTrackCandidate(id,keys.toSet(),null,false)
        val candidates=listOf(c("a","x"),c("b","y"),c("conflict","u","v"),c("new","new"),c("shared1","s"),c("shared2","s"))
        val resolved=SmartMusicPolicy.resolve(candidates,mapOf("x" to "same","y" to "same","u" to "one","v" to "two"))
        assertEquals(listOf("new"),resolved.map { it.first.rowId }); assertNull(resolved.single().second)
    }
    @Test fun statisticsCountRealTimeAndNormalizeKnownArtists() {
        val rows=listOf(row("a",2,1,"Artist"),row("b",3,2," artist "),row("c",1,3,"",excluded=true))
        val stats=SmartMusicPolicy.snapshot(rows,now)
        assertEquals(6L,stats.plays); assertEquals(180_000L,stats.wallMs); assertEquals(3,stats.tracksPlayed)
        assertEquals(1,stats.artistsPlayed); assertEquals(5L,stats.topArtists.single().plays)
        assertEquals("b",stats.topTracks.first().rowId); assertFalse(stats.mix.any { it.excluded })
    }
    @Test fun partialListeningArtistsAndTracksUseSameCriterionAsTheirTops() {
        val partial=row("partial",0,1,"Other").copy(periodWallMs=5_000)
        val stats=SmartMusicPolicy.snapshot(listOf(row("credited",1,1),partial),now)
        assertEquals(2,stats.artistsPlayed);assertEquals(2,stats.tracksPlayed)
        assertEquals(2,stats.topArtists.size);assertEquals(2,stats.topTracks.size)
        assertEquals(1L,stats.plays)
    }
}
