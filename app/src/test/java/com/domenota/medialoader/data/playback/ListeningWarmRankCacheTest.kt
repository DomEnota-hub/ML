package com.domenota.medialoader.data.playback

import org.junit.Assert.*
import org.junit.Test

class ListeningWarmRankCacheTest {
    private val period=ListeningPeriod.MONTH
    private fun key(access:Any = "public-access-7", digest:String = "all-audio-ids",
        generation:Long = 4L,zone:String = "Europe/Moscow") =
        ListeningWarmRankKey(access,digest,period,generation,zone)

    @Test fun returningToFrequentListeningDisplaysPreviousVerifiedRankingImmediately() {
        val cache=ListeningWarmRankCache()
        val selected=ListeningSelection(
            ranked=listOf(ListeningRank("track-row-1","strong-id-1",7,1000)),
            validUntil=100_000,
        )
        assertNull(cache.get(key(),1_000))
        cache.put(key(),selected,1_000)
        assertSame(selected,cache.get(key(),1_001))
        // A new view instance reads the same process-local verified snapshot.
        assertSame(selected,cache.get(key(),10_000))
    }

    @Test fun noStaleRanksAcrossPermissionPrivacyExclusionOrCatalogChange() {
        val cache=ListeningWarmRankCache()
        cache.put(key(),ListeningSelection(ranked=listOf(
            ListeningRank("one","id",3,1000)),validUntil=100_000),1_000)
        assertNull(cache.get(null,2_000))
        assertNull(cache.get(key(access="revoked-epoch"),2_000))
        assertNull(cache.get(key(digest="one-deleted"),2_000))
        assertNull(cache.get(key(generation=5),2_000))
        assertNull(cache.get(key(zone="Europe/Berlin"),2_000))
        assertNotNull(cache.get(key(),2_000))
        cache.clear()
        assertNull(cache.get(key(),2_000))
    }

    @Test fun expiredRankMustNotBeReusedEvenIfAllIdsStillPresent() {
        val cache=ListeningWarmRankCache()
        cache.put(key(),ListeningSelection(validUntil=3_000),2_000)
        assertNotNull(cache.get(key(),2_999))
        assertNull(cache.get(key(),3_000))
        cache.put(key(),ListeningSelection(validUntil=3_001),3_002)
        assertNull(cache.get(key(),3_002))
    }
}
