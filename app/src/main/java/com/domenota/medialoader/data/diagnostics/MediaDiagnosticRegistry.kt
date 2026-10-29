package com.domenota.medialoader.data.diagnostics

import android.content.Context
import com.domenota.medialoader.core.diagnostics.DiagnosticCoordinator
import com.domenota.medialoader.core.diagnostics.DiagnosticProbe
import com.domenota.medialoader.core.diagnostics.FullDiagnosticInput
import com.domenota.medialoader.data.MediaRepository

/**
 * Single assembly point for locally available and opt-in network diagnostics.
 * Creating a coordinator never runs probes or contacts any source.
 */
object MediaDiagnosticRegistry {
    fun probes(
        context: Context,
        repository: MediaRepository,
        transport: SourceDocumentTransport = AnonymousSourceDocumentTransport(),
        fullInput: () -> FullDiagnosticInput = { FullDiagnosticInput.EMPTY },
    ): List<DiagnosticProbe> =
        LocalDiagnosticProbes.probes(context, repository) +
            ActiveLocalDiagnosticProbes.probes(context) +
            SourceDiagnosticProbes.probes(context, repository, transport) +
            ActiveSourceDiagnosticProbes.probes(context, repository, fullInput) +
            ActiveSessionDiagnosticProbes.probes(context, repository)

    fun create(
        context: Context,
        repository: MediaRepository,
        transport: SourceDocumentTransport = AnonymousSourceDocumentTransport(),
        fullInput: () -> FullDiagnosticInput = { FullDiagnosticInput.EMPTY },
    ): DiagnosticCoordinator =
        DiagnosticCoordinator(probes(context, repository, transport, fullInput))
}
