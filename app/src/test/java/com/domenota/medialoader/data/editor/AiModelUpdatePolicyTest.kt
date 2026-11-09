package com.domenota.medialoader.data.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiModelUpdatePolicyTest {
    private val encoder = ExpectedModelBlob(
        "encoder.onnx", "a".repeat(64), 28_157_093L)
    private val decoder = ExpectedModelBlob(
        "decoder.onnx", "b".repeat(64), 16_501_323L)
    private val revision = "c".repeat(40)

    private fun published() = listOf(
        RemoteModelBlob("encoder.onnx", "a".repeat(64), 28_157_093L),
        RemoteModelBlob("decoder.onnx", "b".repeat(64), 16_501_323L),
    )

    @Test fun installedAndMatchingDigestsMeansCurrent() {
        val result = ModelUpdatePolicy.evaluate(true, listOf(encoder, decoder),
            revision, published())
        assertEquals(ModelUpdateState.CURRENT, result.state)
        assertEquals(44_658_416L, result.publishedBytes)
        assertTrue(result.detail.contains("SHA-256"))
    }

    @Test fun missingLocalInstallationIsNotReportedAsUpToDate() {
        val result = ModelUpdatePolicy.evaluate(false, listOf(encoder, decoder),
            revision, published())
        assertEquals(ModelUpdateState.NOT_INSTALLED, result.state)
    }

    @Test fun changedWeightsRequireReviewAndCannotBeAutoInstalled() {
        val changed = published().toMutableList()
        changed[1] = changed[1].copy(sha256 = "d".repeat(64))
        val result = ModelUpdatePolicy.evaluate(true, listOf(encoder, decoder),
            revision, changed)
        assertEquals(ModelUpdateState.REVIEW_REQUIRED, result.state)
        assertTrue(result.detail.contains("Требуется проверка"))
    }

    @Test fun changedSizeRequiresReviewEvenIfDigestAppearsUnchanged() {
        val changed = published().toMutableList()
        changed[0] = changed[0].copy(bytes = 28_157_094L)
        assertEquals(ModelUpdateState.REVIEW_REQUIRED,
            ModelUpdatePolicy.evaluate(true, listOf(encoder, decoder),
                revision, changed).state)
    }

    @Test fun incompleteManifestIsUnverifiable() {
        assertEquals(ModelUpdateState.UNVERIFIABLE,
            ModelUpdatePolicy.evaluate(true, listOf(encoder, decoder),
                revision, published().take(1)).state)
    }

    @Test fun duplicateFilenameIsUnverifiable() {
        assertEquals(ModelUpdateState.UNVERIFIABLE,
            ModelUpdatePolicy.evaluate(true, listOf(encoder),
                revision, listOf(published()[0], published()[0])).state)
    }

    @Test fun malformedRevisionAndShaCannotClaimNoUpdates() {
        assertEquals(ModelUpdateState.UNVERIFIABLE,
            ModelUpdatePolicy.evaluate(true, listOf(encoder),
                "main", published()).state)
        assertEquals(ModelUpdateState.UNVERIFIABLE,
            ModelUpdatePolicy.evaluate(true, listOf(encoder),
                revision, listOf(RemoteModelBlob("encoder.onnx", "bad", 100L))).state)
    }

    @Test fun emptyOrInvalidRemoteSizeCannotClaimNoUpdates() {
        assertEquals(ModelUpdateState.UNVERIFIABLE,
            ModelUpdatePolicy.evaluate(true, listOf(encoder),
                revision, emptyList()).state)
        assertEquals(ModelUpdateState.UNVERIFIABLE,
            ModelUpdatePolicy.evaluate(true, listOf(encoder),
                revision, listOf(RemoteModelBlob("encoder.onnx", "a".repeat(64), 0L))).state)
    }

    @Test fun metadataCheckDoesNotRequireInstalledModelOrNetworkInPolicy() {
        val result = ModelUpdatePolicy.evaluate(false, listOf(encoder),
            revision, published().take(1))
        assertFalse(result.state == ModelUpdateState.CURRENT)
    }
}
