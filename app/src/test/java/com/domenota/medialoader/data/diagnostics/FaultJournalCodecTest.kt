package com.domenota.medialoader.data.diagnostics

import com.domenota.medialoader.core.diagnostics.DiagnosticComponent
import com.domenota.medialoader.core.diagnostics.FaultJournal
import com.domenota.medialoader.core.diagnostics.FaultObservation
import com.domenota.medialoader.core.diagnostics.FaultSeverity
import com.domenota.medialoader.core.diagnostics.FaultSignal
import com.domenota.medialoader.core.diagnostics.FaultStatus
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FaultJournalCodecTest {
    @Test fun journalRoundTripPreservesLifecycleAndOpaqueLinks() {
        val first = FaultJournal.apply(emptyList(), FaultObservation.Failure(FaultSignal(
            DiagnosticComponent.SOURCES, "source.muzmo.http", "HTTP_ACCESS_DENIED",
            FaultSeverity.WARNING, 100, "search-1", listOf("op:1", "op:2")))) { "fault-1" }
        val solved = FaultJournal.apply(first, FaultObservation.Recovered(
            DiagnosticComponent.SOURCES, "source.muzmo.http", 150, "search-1")) { "not-used" }
        val next = FaultJournal.apply(solved, FaultObservation.Failure(FaultSignal(
            DiagnosticComponent.STORAGE, "storage.selected_folder", "FOLDER_PERMISSION_MISSING",
            FaultSeverity.ERROR, 200))) { "fault-2" }
        val encoded = FaultJournalCodec.encode(next)
        val restored = FaultJournalCodec.decode(encoded.toString(Charsets.UTF_8))
        assertEquals(next, restored)
        assertEquals(FaultStatus.RESOLVED, restored.first().status)
        assertEquals(150L, restored.first().resolvedAtMillis)
        assertEquals(null, restored.last().operationId)
        assertEquals(listOf("op:1", "op:2"), restored.first().eventIds)
        assertFalse(String(encoded).contains("https://"))
    }

    @Test fun rejectsCorruptSchemaDuplicateIdsAndInvalidCodes() {
        val sample = FaultJournal.apply(emptyList(), FaultObservation.Failure(FaultSignal(
            DiagnosticComponent.SOURCES, "source.muzmo.http", "HTTP_ACCESS_DENIED",
            FaultSeverity.WARNING, 100L))) { "fault-1" }
        val encoded = String(FaultJournalCodec.encode(sample))
        assertTrue(runCatching { FaultJournalCodec.decode(encoded.replace(
            "\"schema\":1", "\"schema\":99")) }.isFailure)
        val row = JSONObject(encoded).getJSONArray("records").getJSONObject(0)
        val duplicate = JSONObject()
            .put("schema", 1)
            .put("records", org.json.JSONArray().put(row).put(row))
        assertTrue(runCatching { FaultJournalCodec.decode(duplicate.toString()) }.isFailure)
        val unsafe = JSONObject(encoded)
        unsafe.getJSONArray("records").getJSONObject(0).put(
            "reasonCode", "token=https://secret.invalid")
        assertTrue(runCatching { FaultJournalCodec.decode(unsafe.toString()) }.isFailure)
        assertTrue(runCatching { FaultJournalCodec.decode("not-json") }.isFailure)
    }

    @Test fun boundedSerializationRejectsOversizedUserSuppliedRecords() {
        val sample = FaultJournal.apply(emptyList(), FaultObservation.Failure(FaultSignal(
            DiagnosticComponent.SOURCES, "source.muzmo.http", "HTTP_ACCESS_DENIED",
            FaultSeverity.WARNING, 100L))) { "fault-1" }.single()
        val duplicateId = listOf(sample, sample)
        assertTrue(runCatching { FaultJournalCodec.encode(duplicateId) }.isFailure)
        assertTrue(runCatching {
            FaultJournalCodec.encode(List(FaultJournal.MAX_RECORDS + 1) { i ->
                sample.copy(id = "issue-$i")
            })
        }.isFailure)
        val empty = FaultJournalCodec.decode(String(FaultJournalCodec.encode(emptyList())))
        assertTrue(empty.isEmpty())
    }
}
