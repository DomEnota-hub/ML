package com.domenota.medialoader.data.diagnostics

import com.domenota.medialoader.core.diagnostics.DiagnosticScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import com.domenota.medialoader.core.diagnostics.DiagnosticState
import org.junit.Test

class ActiveLocalDiagnosticProbesTest {
    @Test fun fullModeHasExplicitRuntimeQueueStorageAndEditorChecks() {
        val specs = ActiveLocalDiagnosticProbes.specifications()
        assertEquals(specs.size, specs.map { it.code }.distinct().size)
        assertTrue(specs.all { it.scope == DiagnosticScope.FULL })
        assertTrue(listOf("runtime.ytdlp.active", "runtime.ffmpeg.active",
            "runtime.quickjs.active", "queue.synthetic", "queue.service.active",
            "storage.write", "editor.onnx.active").all { code ->
                specs.any { it.code == code }
            })
    }

    @Test fun leftoverProbeFileCannotBeReportedAsCleanSuccess() {
        val result = ActiveLocalDiagnosticProbes.storageResult(readBack = true,
            cleanupSucceeded = false)
        assertEquals(DiagnosticState.WARNING, result.state)
        assertEquals("STORAGE_CLEANUP_FAILED", result.reasonCode)
    }
}
