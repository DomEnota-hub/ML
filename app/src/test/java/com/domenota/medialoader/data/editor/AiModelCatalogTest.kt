package com.domenota.medialoader.data.editor

import com.domenota.medialoader.data.editor.AiModelUpdateChecker.Kind
import org.junit.Assert.*
import org.junit.Test

class AiModelCatalogTest {
    @Test fun catalogCoversAllFiveModelsAndExactKnownPins() {
        val rows = AiModelCatalog.entries
        assertEquals(Kind.entries.toSet(), rows.map { it.kind }.toSet())
        assertEquals(5, rows.size)
        assertTrue(rows.all { row -> row.files.isNotEmpty() && row.files.all { it.sha256.matches(Regex("[0-9a-f]{64}")) } })
        val mobile = AiModelCatalog.entry(Kind.MOBILE_SAM)
        assertEquals(listOf(MobileSamModelStore.ENCODER_SHA256, MobileSamModelStore.DECODER_SHA256), mobile.files.map { it.sha256 })
        assertEquals(MobileSamModelStore.TOTAL_BYTES, mobile.files.sumOf { requireNotNull(it.bytes) })
        assertEquals(ReflectionDExModelStore.EXPECTED_SHA256, AiModelCatalog.entry(Kind.REFLECTION).files.single().sha256)
        assertEquals(SpecularSrNetModelStore.MODEL_SHA256, AiModelCatalog.entry(Kind.GLARE).files.single().sha256)
    }

    @Test fun offlineAndImportModelsNeverPretendToHavePublishedNetworkUpdates() {
        val sr = AiModelCatalog.entry(Kind.GLARE)
        val dex = AiModelCatalog.entry(Kind.REFLECTION)
        assertNull(sr.metadataRepository)
        assertNull(dex.metadataRepository)
        assertEquals("Скачать", AiModelCatalog.installAction(sr, false))
        assertEquals("Импортировать", AiModelCatalog.installAction(dex, false))
        assertEquals("Установить", AiModelCatalog.installAction(dex, true))
        assertTrue(AiModelCatalog.status(dex, true, false, null, false).contains("импорт"))
    }

    @Test fun changedOrUnavailableUpstreamNeverClaimsAnApprovedUpdate() {
        val model = AiModelCatalog.entry(Kind.LAMA)
        assertEquals("Скачать", AiModelCatalog.installAction(model, false))
        assertTrue(AiModelCatalog.status(model, true, false,
            ModelUpdateResult(ModelUpdateState.REVIEW_REQUIRED, ""), false).contains("на проверке"))
        assertTrue(AiModelCatalog.status(model, true, false,
            ModelUpdateResult(ModelUpdateState.UNAVAILABLE, ""), false).contains("не проверена"))
        assertEquals("Установлена · актуальна", AiModelCatalog.status(model, true, false,
            ModelUpdateResult(ModelUpdateState.CURRENT, ""), false))
        assertEquals("Не установлена", AiModelCatalog.status(model, false, false, null, false))
    }
    @Test fun ownerCatalogMatchesAllFiveAndRejectsChangedOrMissingWeights() {
        val records = AiModelCatalog.entries.map { entry ->
            OwnerModelCatalog.Record(entry.kind.key, entry.files.map {
                OwnerModelCatalog.Blob(it.filename.substringAfterLast('/'), it.sha256, it.bytes)
            })
        }
        val installed = records.associate { it.id to true }
        assertTrue(OwnerModelCatalog.compare(records, installed).values.all { it.state == ModelUpdateState.CURRENT })
        val altered = records.map { record -> if (record.id == "glare")
            record.copy(files = record.files.map { it.copy(sha256 = "f".repeat(64)) }) else record }
        assertEquals(ModelUpdateState.REVIEW_REQUIRED, OwnerModelCatalog.compare(altered, installed).getValue("glare").state)
        assertTrue(OwnerModelCatalog.compare(records.dropLast(1), installed).values.all { it.state == ModelUpdateState.UNVERIFIABLE })
    }
}
