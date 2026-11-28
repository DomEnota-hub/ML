package com.domenota.medialoader.data.editor

import com.domenota.medialoader.data.editor.AiModelUpdateChecker.Kind
import org.junit.Assert.*
import org.junit.Test

class AiModelCatalogTest {
    @Test fun catalogCoversAllSixModelsAndExactKnownPins() {
        val rows = AiModelCatalog.entries
        assertEquals(Kind.entries.toSet(), rows.map { it.kind }.toSet())
        assertEquals(6, rows.size)
        assertEquals(GigaAmModelStore.SHA256,AiModelCatalog.entry(Kind.GIGAAM).files.single().sha256)
        assertEquals(224721181L,AiModelCatalog.entry(Kind.GIGAAM).files.single().bytes)
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
        assertEquals("Скачать", AiModelCatalog.installAction(dex, false))
        assertEquals("Установить", AiModelCatalog.installAction(dex, true))
        assertTrue(AiModelCatalog.status(dex, false, false, null, false).contains("сборка"))
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
    @Test fun ownerCatalogMatchesAllSixAndRejectsChangedOrMissingWeights() {
        // Literal published catalog names and sizes, not generated from comparison code.
        val records = listOf(
            OwnerModelCatalog.Record("gigaam", listOf(OwnerModelCatalog.Blob("v3_ctc.int8.onnx", GigaAmModelStore.SHA256, 224721181L))),
            OwnerModelCatalog.Record("lama", listOf(OwnerModelCatalog.Blob("lama_fp32.onnx", LaMaModelStore.SHA256, 208_044_816L))),
            OwnerModelCatalog.Record("mobile", listOf(
                OwnerModelCatalog.Blob("mobile_sam_image_encoder.onnx", MobileSamModelStore.ENCODER_SHA256, 28_157_093L),
                OwnerModelCatalog.Blob("sam_mask_decoder_single.onnx", MobileSamModelStore.DECODER_SHA256, 16_501_323L))),
            OwnerModelCatalog.Record("people", listOf(OwnerModelCatalog.Blob("efficientdet_lite0.tflite", PersonDetectorModelStore.SHA256, 13_836_895L))),
            OwnerModelCatalog.Record("glare", listOf(OwnerModelCatalog.Blob("specular_srnet_256.onnx", SpecularSrNetModelStore.MODEL_SHA256, 230_221L))),
            OwnerModelCatalog.Record("reflection", listOf(OwnerModelCatalog.Blob("dexnet_vgg19_reflection_128.onnx", ReflectionDExModelStore.EXPECTED_SHA256, 91_341_128L))),
        )
        val installed = records.associate { it.id to true }
        assertTrue(OwnerModelCatalog.compare(records, installed).values.all { it.state == ModelUpdateState.CURRENT })
        val altered = records.map { record -> if (record.id == "glare")
            record.copy(files = record.files.map { it.copy(sha256 = "f".repeat(64)) }) else record }
        assertEquals(ModelUpdateState.REVIEW_REQUIRED, OwnerModelCatalog.compare(altered, installed).getValue("glare").state)
        assertTrue(OwnerModelCatalog.compare(records.dropLast(1), installed).values.all { it.state == ModelUpdateState.UNVERIFIABLE })
    }
}
