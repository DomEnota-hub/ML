package com.domenota.medialoader.core.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticModeChecksTest {
    @Test fun fullModeShowsFunctionalChecksInsteadOfTheirPreliminaryProxies() {
        val codes = listOf("source.vk.auth", "source.vk.history", "source.vk.http",
            "source.vk.workflow", "source.vk.session", "runtime.ytdlp.library",
            "runtime.ytdlp.active", "queue.service_manifest", "queue.service.active",
            "storage.selected_folder", "storage.write", "storage.free_space")
        val specs = codes.map { DiagnosticCheckSpec(it, DiagnosticComponent.SOURCES,
            DiagnosticImpact.OPTIONAL) }

        assertEquals(codes, DiagnosticModeChecks.visible(specs, DiagnosticMode.WITH_NETWORK)
            .map { it.code })
        assertEquals(listOf("source.vk.workflow", "source.vk.session", "runtime.ytdlp.active",
            "queue.service.active", "storage.write", "storage.free_space"),
            DiagnosticModeChecks.visible(specs, DiagnosticMode.FULL).map { it.code })
    }
}
