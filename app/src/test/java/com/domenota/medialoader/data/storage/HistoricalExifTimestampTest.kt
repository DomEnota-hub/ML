package com.domenota.medialoader.data.storage

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Regression for removing ExifInterface RestrictedApi accessors (API 26-30). */
class HistoricalExifTimestampTest {
    @Test fun publicTagsWithNoOffsetMatchLegacyUtcInterpretation() {
        assertEquals(Instant.parse("2020-06-01T12:34:56Z").toEpochMilli(),
            parseHistoricalExifTimestamp("2020:06:01 12:34:56", null, null))
        assertEquals(Instant.parse("2020-06-01T12:34:56Z").toEpochMilli(),
            parseHistoricalExifTimestamp("2020-06-01 12:34:56", null, null))
    }

    @Test fun honoursExplicitOffsetAndSubsecondPrecision() {
        assertEquals(Instant.parse("2020-06-01T09:34:56.700Z").toEpochMilli(),
            parseHistoricalExifTimestamp("2020:06:01 12:34:56", "7", "+03:00"))
        assertEquals(Instant.parse("2020-06-01T13:04:56.003Z").toEpochMilli(),
            parseHistoricalExifTimestamp("2020:06:01 12:34:56", "0039", "-00:30"))
    }

    @Test fun malformedAndMissingDatesFailClosed() {
        assertNull(parseHistoricalExifTimestamp(null, null, null))
        assertNull(parseHistoricalExifTimestamp("0000:00:00 00:00:00", null, null))
        assertNull(parseHistoricalExifTimestamp("2020:02:30 12:34:56", null, null))
        assertNull(parseHistoricalExifTimestamp("2020:06:01 12:34:56", null, "+99:00"))
        assertNull(parseHistoricalExifTimestamp("2020:06:01 12:34:56", "not-ms", null))
    }
}
