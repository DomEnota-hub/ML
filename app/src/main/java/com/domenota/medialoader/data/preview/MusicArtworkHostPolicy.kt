package com.domenota.medialoader.data.preview

import java.net.URI
import java.util.Locale

/** Strict, source-hint-independent allowlist for Shazam/Apple cover art in history. */
internal object MusicArtworkHostPolicy {
    fun allowed(address: String): Boolean = runCatching {
        val uri = URI(address)
        val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
        uri.scheme.equals("https", ignoreCase = true) &&
            uri.userInfo == null && uri.port in listOf(-1, 443) &&
            uri.path.isNotBlank() && uri.path != "/" &&
            (host == "mzstatic.com" || host.endsWith(".mzstatic.com") ||
                host == "shazam.com" || host.endsWith(".shazam.com"))
    }.getOrDefault(false)
}
