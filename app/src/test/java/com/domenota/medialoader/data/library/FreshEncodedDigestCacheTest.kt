package com.domenota.medialoader.data.library

import org.junit.Assert.*
import org.junit.Test

class FreshEncodedDigestCacheTest {
    @Test fun identicalFreshFullDigestCanReuseEncodedPayload() {
        val cache=FreshEncodedDigestCache()
        cache.remember("same",100,1,7,"encoded")
        assertEquals("encoded",cache.find("same",100,2,7))
        assertNull(cache.find("different",100,2,7))
        assertNull(cache.find("same",101,2,7))
    }

    @Test fun absentStampDigestOrDifferentPrivacyEpochCannotReuse() {
        val cache=FreshEncodedDigestCache()
        cache.remember("same",100,null,7,"encoded")
        assertNull(cache.find("same",100,1,7))
        cache.remember("same",100,1,7,null)
        assertNull(cache.find("same",100,1,7))
        cache.remember("same",100,1,7,"encoded")
        assertNull(cache.find("same",100,null,7))
        assertNull(cache.find("same",100,1,8))
    }

    @Test fun cacheIsBoundedAndDoesNotPersistAcrossScans() {
        val cache=FreshEncodedDigestCache(2)
        cache.remember("a",100,1,7,"one")
        cache.remember("b",100,1,7,"two")
        assertEquals("one",cache.find("a",100,1,7))
        cache.remember("c",100,1,7,"three")
        assertNull(cache.find("b",100,1,7))
        assertEquals("one",cache.find("a",100,1,7))
        assertNull(FreshEncodedDigestCache().find("a",100,1,7))
    }
}
