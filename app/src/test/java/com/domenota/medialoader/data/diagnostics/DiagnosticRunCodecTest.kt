package com.domenota.medialoader.data.diagnostics

import com.domenota.medialoader.core.diagnostics.DiagnosticCheckSpec
import com.domenota.medialoader.core.diagnostics.DiagnosticComponent
import com.domenota.medialoader.core.diagnostics.DiagnosticEvidence
import com.domenota.medialoader.core.diagnostics.DiagnosticFinding
import com.domenota.medialoader.core.diagnostics.DiagnosticImpact
import com.domenota.medialoader.core.diagnostics.DiagnosticMode
import com.domenota.medialoader.core.diagnostics.DiagnosticOutcome
import com.domenota.medialoader.core.diagnostics.DiagnosticRun
import com.domenota.medialoader.core.diagnostics.DiagnosticScope
import com.domenota.medialoader.core.diagnostics.SourceDiagnosticRules
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticRunCodecTest {
    private val local = DiagnosticCheckSpec(
        "queue.snapshot", DiagnosticComponent.DOWNLOAD_QUEUE, DiagnosticImpact.REQUIRED)
    private val remote = DiagnosticCheckSpec(
        "source.muzmo.http", DiagnosticComponent.SOURCES, DiagnosticImpact.OPTIONAL,
        scope = DiagnosticScope.NETWORK)
    private val specs = listOf(local, remote)

    private fun sample() = DiagnosticRun(
        DiagnosticMode.LOCAL,
        startedAtMillis = 1_000L,
        finishedAtMillis = 1_080L,
        findings = listOf(
            DiagnosticFinding.fromOutcome(local,
                DiagnosticOutcome(com.domenota.medialoader.core.diagnostics.DiagnosticState.OK,
                    DiagnosticEvidence.VERIFIED, "Очередь прочитана.", "QUEUE_SNAPSHOT_READ"),
                1_020L, 20L),
            DiagnosticFinding.notChecked(remote, "Сетевая проверка не запускалась."),
        ),
    )

    @Test fun savedRunRestoresVerifiedFactsAndSkipsWithoutCreatingNetworkSuccess() {
        val stored = DiagnosticRunCodec.encode(sample())
        val loaded = DiagnosticRunCodec.decode(String(stored, Charsets.UTF_8), specs)
        assertEquals(sample(), loaded)
        assertEquals(null, loaded.findings[1].checkedAtMillis)
        assertEquals(DiagnosticEvidence.NONE, loaded.findings[1].evidence)
        assertTrue(loaded.findings[0].isStale(1_020L + local.maxAgeMillis + 1L))
    }

    @Test fun previousVersionUnknownChecksMustNotBeDisplayedAsCurrentResults() {
        val old = JSONObject(String(DiagnosticRunCodec.encode(sample()), Charsets.UTF_8))
        old.getJSONArray("findings").getJSONObject(0).put("code", "queue.deleted-feature")
        assertTrue(runCatching {
            DiagnosticRunCodec.decode(old.toString(), specs)
        }.isFailure)
    }

    @Test fun rejectMalformedCheckDataAndInvalidVersion() {
        val old = JSONObject(String(DiagnosticRunCodec.encode(sample()), Charsets.UTF_8))
        old.put("schema", 500)
        assertTrue(runCatching { DiagnosticRunCodec.decode(old.toString(), specs) }.isFailure)
        val invalid = JSONObject(String(DiagnosticRunCodec.encode(sample()), Charsets.UTF_8))
        invalid.getJSONArray("findings").getJSONObject(0)
            .put("reason", "https://example.org/?sessionid=secret")
        assertTrue(runCatching { DiagnosticRunCodec.decode(invalid.toString(), specs) }.isFailure)
        val duplicate = JSONObject(String(DiagnosticRunCodec.encode(sample()), Charsets.UTF_8))
        val rows = duplicate.getJSONArray("findings")
        rows.put(rows.getJSONObject(0))
        assertTrue(runCatching { DiagnosticRunCodec.decode(duplicate.toString(), specs) }.isFailure)
    }

    @Test fun historicalHttp403DoesNotBecomeFailureOfLoaderOnRestore() {
        val input = DiagnosticRun(DiagnosticMode.WITH_NETWORK, 1_000L, 1_100L,
            listOf(DiagnosticFinding.fromOutcome(remote, SourceDiagnosticRules.http(403),
                1_080L, 10L)))
        val restored = DiagnosticRunCodec.decode(
            DiagnosticRunCodec.encode(input).toString(Charsets.UTF_8), specs)
        assertEquals(SourceDiagnosticRules.http(403).explanation,
            restored.findings.single().explanation)
        assertEquals("HTTP_ACCESS_DENIED", restored.findings.single().reasonCode)
    }
}
