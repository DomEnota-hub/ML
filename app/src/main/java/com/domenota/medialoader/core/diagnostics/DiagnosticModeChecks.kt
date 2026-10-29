package com.domenota.medialoader.core.diagnostics

/** Full diagnostics use active checks instead of repeating preliminary proxies. */
object DiagnosticModeChecks {
    private val superseded = setOf(
        "runtime.ytdlp.library", "runtime.ffmpeg.library", "runtime.quickjs.bundle",
        "queue.service_manifest", "queue.foreground", "storage.selected_folder",
        "editor.onnx.library",
    )

    fun visible(specs: List<DiagnosticCheckSpec>, mode: DiagnosticMode?): List<DiagnosticCheckSpec> =
        specs.filter { spec ->
            (spec.scope != DiagnosticScope.FULL || mode == DiagnosticMode.FULL) &&
                (mode != DiagnosticMode.FULL ||
                    (spec.code !in superseded && !isPreliminarySourceCheck(spec.code)))
        }

    private fun isPreliminarySourceCheck(code: String): Boolean =
        code.startsWith("source.") && code.substringAfterLast('.') in
            setOf("auth", "history", "http")
}
