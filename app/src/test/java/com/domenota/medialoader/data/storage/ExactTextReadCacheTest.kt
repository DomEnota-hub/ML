package com.domenota.medialoader.data.storage

import org.junit.Assert.*
import org.junit.Test

class ExactTextReadCacheTest {
    @Test fun exactRawReusesParseButWhitespaceAndRawReplacementInvalidate() {
        val cache = ExactTextReadCache<Any>()
        var parses = 0
        fun read(raw: String) = cache.read(raw) { parses++; Any() }
        val first = read("{\"n\":1}")
        assertSame(first, read("{\"n\":1}"))
        assertNotSame(first, read("{ \"n\":1}"))
        assertNotSame(first, read("{\"n\":1}")) // Single current document, no historical cache.
        assertEquals(3, parses)
        cache.clear()
        assertNotSame(first, read("{\"n\":1}"))
        assertEquals(4, parses)
    }

    @Test fun parseFailurePropagatesAndSameFailedRawIsRetriedFresh() {
        val cache = ExactTextReadCache<String>()
        cache.read("valid") { "parsed:$it" }
        var failures = 0
        repeat(2) {
            try {
                cache.read("broken") { failures++; throw IllegalArgumentException("Invalid synthetic document") }
                fail("A previous valid parse must never replace an invalid current document")
            } catch (_: IllegalArgumentException) { }
        }
        assertEquals(2, failures)
        assertEquals("repaired:broken", cache.read("broken") { "repaired:$it" })
    }

    @Test fun oversizedCurrentDocumentIsReparsedAndEvictsPreviouslyCachedDocument() {
        val cache = ExactTextReadCache<Any>(maxChars = 4)
        var parses = 0
        fun read(raw: String) = cache.read(raw) { parses++; Any() }
        val first = read("1234")
        assertSame(first, read("1234"))
        assertNotSame(read("12345"), read("12345"))
        assertNotSame(first, read("1234"))
        assertEquals(4, parses)
    }

    @Test fun callerReadsCurrentTextEveryTimeEvenWhenTheParseCanBeReused() {
        val cache = ExactTextReadCache<String>()
        var liveRaw = "before"
        var reads = 0
        var parses = 0
        fun freshRead(): String {
            reads++
            return cache.read(liveRaw) { parses++; it.uppercase() }
        }
        assertEquals("BEFORE", freshRead())
        assertEquals("BEFORE", freshRead())
        liveRaw = "after"
        assertEquals("AFTER", freshRead())
        assertEquals(3, reads)
        assertEquals(2, parses)
    }
}
