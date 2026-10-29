package com.domenota.medialoader.core.provider

/** Fixed labels only: yt-dlp stderr can contain URLs, cookie values and request tokens. */
object YouTubeWarningDiagnostics {
    fun classify(stderr: String): List<String> = buildList {
        val lower = stderr.lowercase()
        if ("no supported javascript runtime could be found" in lower ||
            "javascript runtime is unavailable" in lower ||
            ("yt-dlp-ejs" in lower && ("not installed" in lower || "not found" in lower))
        ) {
            add("javascript-runtime-unavailable")
        }
        if ("signature extraction failed" in lower || "n challenge" in lower || "nsig" in lower) {
            add("signature-challenge-failed")
        }
        if ("po token" in lower || "po_token" in lower) add("po-token-required")
        if ("sabr" in lower || "missing a url" in lower) add("adaptive-streams-skipped")
        if ("rate limit" in lower || "rate-limit" in lower) add("rate-limited")
    }
}
