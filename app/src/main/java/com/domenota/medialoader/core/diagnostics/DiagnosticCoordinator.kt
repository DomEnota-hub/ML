package com.domenota.medialoader.core.diagnostics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout

/**
 * Executes registered read-only check adapters. The coordinator itself performs
 * no network requests, disk writes, model initialization, downloads or mutations.
 *
 * A network check can run only under BOTH explicit NETWORK mode AND explicit
 * user consent. Local mode does not invoke network probes even when registered.
 */
class DiagnosticCoordinator(
    probes: List<DiagnosticProbe>,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    // Match execution and visible card order. Stable within each component, so
    // the on-screen 'Проверяется' indicator and persisted report agree.
    private val checks = probes.withIndex().sortedWith(
        compareBy<IndexedValue<DiagnosticProbe>> {
            when (it.value.spec.component) {
                DiagnosticComponent.SOURCES -> 0
                DiagnosticComponent.MEDIA_RUNTIME -> 1
                DiagnosticComponent.DOWNLOAD_QUEUE -> 2
                DiagnosticComponent.STORAGE -> 3
                DiagnosticComponent.PHOTO_EDITOR -> 4
                DiagnosticComponent.FAULT_JOURNAL -> 5
                DiagnosticComponent.DEVICE -> 6
            }
        }.thenBy { it.index }
    ).map { it.value }
    private val running = Mutex()

    init {
        require(checks.map { it.spec.code }.distinct().size == checks.size) {
            "Duplicate diagnostic check code"
        }
    }

    val specifications: List<DiagnosticCheckSpec> get() = checks.map { it.spec }

    fun specificationsFor(mode: DiagnosticMode?): List<DiagnosticCheckSpec> =
        DiagnosticModeChecks.visible(specifications, mode)

    /**
     * Cancellation propagates to the caller, never to the app's download queue.
     * Diagnostic timeout/exception does not establish a fault in the component:
     * it produces UNAVAILABLE rather than a false ERROR.
     */
    suspend fun run(
        mode: DiagnosticMode = DiagnosticMode.LOCAL,
        networkConsent: Boolean = false,
        onFinding: (DiagnosticFinding) -> Unit = {},
    ): DiagnosticRun {
        require(mode == DiagnosticMode.LOCAL || networkConsent) {
            "Explicit approval is required for network diagnostics"
        }
        if (!running.tryLock()) throw DiagnosticAlreadyRunningException()
        try {
            val started = clockMillis()
            val findings = mutableListOf<DiagnosticFinding>()
            val selectedCodes = specificationsFor(mode).mapTo(HashSet()) { it.code }
            for (probe in checks) {
                currentCoroutineContext().ensureActive()
                val spec = probe.spec
                if (spec.code !in selectedCodes) continue
                val finding = if (spec.scope == DiagnosticScope.NETWORK &&
                    mode == DiagnosticMode.LOCAL) {
                    DiagnosticFinding.notChecked(spec,
                        "Сетевая проверка не запускалась.")
                } else {
                    val checkStarted = clockMillis()
                    val outcome = try {
                        withTimeout(spec.timeoutMillis) { probe.check() }
                    } catch (_: TimeoutCancellationException) {
                        DiagnosticOutcome.unavailable("Превышено время диагностической проверки.",
                            "CHECK_TIMEOUT")
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // Never leak Throwable.message, URL parameters or credentials.
                        DiagnosticOutcome.unavailable("Проверка не смогла завершиться.",
                            "CHECK_EXCEPTION")
                    }
                    if (outcome.state == DiagnosticState.UNCHECKED)
                        DiagnosticFinding.notChecked(spec, outcome.explanation)
                    else DiagnosticFinding.fromOutcome(spec, outcome,
                        checkedAtMillis = clockMillis(),
                        durationMillis = (clockMillis() - checkStarted).coerceAtLeast(0L))
                }
                findings.add(finding)
                onFinding(finding)
            }
            return DiagnosticRun(mode, started,
                finishedAtMillis = maxOf(started, clockMillis()), findings = findings)
        } finally {
            running.unlock()
        }
    }
}

class DiagnosticAlreadyRunningException :
    IllegalStateException("Диагностическая проверка уже выполняется.")
