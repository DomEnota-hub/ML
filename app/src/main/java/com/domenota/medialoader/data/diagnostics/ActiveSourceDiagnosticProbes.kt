package com.domenota.medialoader.data.diagnostics

import android.content.Context
import com.domenota.medialoader.core.diagnostics.DiagnosticCheckSpec
import com.domenota.medialoader.core.diagnostics.DiagnosticComponent
import com.domenota.medialoader.core.diagnostics.DiagnosticImpact
import com.domenota.medialoader.core.diagnostics.DiagnosticProbe
import com.domenota.medialoader.core.diagnostics.DiagnosticScope
import com.domenota.medialoader.core.diagnostics.DiagnosticSource
import com.domenota.medialoader.core.diagnostics.FullDiagnosticInput
import com.domenota.medialoader.core.diagnostics.SourceWorkflowRules
import com.domenota.medialoader.core.provider.ProviderException
import com.domenota.medialoader.data.MediaRepository
import com.domenota.medialoader.data.search.HitMozSearchClient
import kotlinx.coroutines.CancellationException

/** Calls the application's real metadata/search paths using user-supplied fixtures. */
object ActiveSourceDiagnosticProbes {
    fun specifications(): List<DiagnosticCheckSpec> = DiagnosticSource.entries.map { source ->
        DiagnosticCheckSpec("source.${source.id}.workflow", DiagnosticComponent.SOURCES,
            DiagnosticImpact.OPTIONAL, DiagnosticScope.FULL,
            timeoutMillis = if (source == DiagnosticSource.RESERVE_MUSIC) 32_000 else 60_000,
            maxAgeMillis = 2 * 60_000)
    }

    fun probes(context: Context, repository: MediaRepository,
        input: () -> FullDiagnosticInput): List<DiagnosticProbe> =
        DiagnosticSource.entries.zip(specifications()).map { (source, check) ->
            object : DiagnosticProbe {
                override val spec = check
                override suspend fun check() = when (source) {
                    DiagnosticSource.PRIMARY_MUSIC -> music(input(), reserve = false)
                    DiagnosticSource.RESERVE_MUSIC -> music(input(), reserve = true,
                        context = context.applicationContext)
                    else -> {
                        val url = input().linkFor(source)
                        if (url == null) SourceWorkflowRules.missingInput(source) else try {
                            val items = repository.diagnosticResolve(url)
                            SourceWorkflowRules.extracted(items.count { it.providerId == source.id })
                        } catch (error: ProviderException) {
                            when (error.reason) {
                                ProviderException.Reason.ACCESS_REQUIRED -> SourceWorkflowRules.authorizationRequired()
                                ProviderException.Reason.UNSUPPORTED -> SourceWorkflowRules.unavailableMaterial()
                                ProviderException.Reason.RATE_LIMITED -> SourceWorkflowRules.rateLimited()
                                ProviderException.Reason.TEMPORARY_FAILURE -> SourceWorkflowRules.blocked()
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            SourceWorkflowRules.failed()
                        }
                    }
                }
            }
        }

    private suspend fun music(input: FullDiagnosticInput, reserve: Boolean,
        context: Context? = null): com.domenota.medialoader.core.diagnostics.DiagnosticOutcome {
        val source = if (reserve) DiagnosticSource.RESERVE_MUSIC else DiagnosticSource.PRIMARY_MUSIC
        if (input.artist.isBlank() && input.title.isBlank())
            return SourceWorkflowRules.missingInput(source)
        return try {
            val count = if (reserve) MuzmoDiagnosticSearch(context!!).search(
                input.artist, input.title)
            else HitMozSearchClient().search(input.artist, input.title).size
            SourceWorkflowRules.extracted(count)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (timeout: MuzmoDiagnosticTimeout) {
            SourceWorkflowRules.musicTimeout(timeout.stage)
        } catch (_: Exception) {
            SourceWorkflowRules.blocked()
        }
    }
}
